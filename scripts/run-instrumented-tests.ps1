param([string]$Filter, [string]$Serial, [switch]$SkipBuild, [switch]$Coverage, [switch]$KeepData)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb)) { throw "adb not found at $adb" }
if (-not $Serial) {
    $available = @(& $adb devices | Where-Object { $_ -match '^([^\s]+)\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
    if ($available.Count -ne 1) { throw 'Specify -Serial when zero or multiple devices are connected.' }
    $Serial = $available[0]
}
function Invoke-Adb { & $adb -s $Serial @args }
$api = (Invoke-Adb shell getprop ro.build.version.sdk).Trim()
if ($api -notmatch '^\d+$') { throw "Device $Serial is unavailable." }
$artifactDir = Join-Path $root "artifacts\device-api-$api\$(Get-Date -Format yyyyMMdd-HHmmss)"
New-Item -ItemType Directory -Path $artifactDir -Force | Out-Null
$priorJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = "$priorJavaOptions -Djdk.net.unixdomain.tmpdir=$root/.gradle -Djava.net.preferIPv4Stack=true -Djavax.net.ssl.trustStoreType=Windows-ROOT"
    if (-not $SkipBuild) {
        & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
        if ($LASTEXITCODE -ne 0) { throw 'APK build failed.' }
    }
    Invoke-Adb install -r -t app/build/outputs/apk/debug/app-debug.apk | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'App installation failed.' }
    Invoke-Adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Test installation failed.' }
    if (-not $KeepData) {
        foreach ($package in @('com.anaalarm', 'com.anaalarm.test')) {
            if ((Invoke-Adb shell pm clear $package).Trim() -ne 'Success') { throw "Could not reset $package." }
        }
    }
    Invoke-Adb shell pm grant com.anaalarm android.permission.RECORD_AUDIO
    if ($LASTEXITCODE -ne 0) { throw 'Microphone permission was not granted.' }
    if ([int]$api -ge 33) { Invoke-Adb shell pm grant com.anaalarm android.permission.POST_NOTIFICATIONS }
    $operations = @()
    if ([int]$api -ge 31) { $operations += 'SCHEDULE_EXACT_ALARM' }
    if ([int]$api -ge 34) { $operations += 'USE_FULL_SCREEN_INTENT' }
    foreach ($operation in $operations) {
        Invoke-Adb shell appops set com.anaalarm $operation allow
        $confirmed = Invoke-Adb shell appops get com.anaalarm $operation
        if ($LASTEXITCODE -ne 0 -or "$confirmed" -notmatch "${operation}:\s+allow") { throw "Required app-op $operation was not confirmed." }
    }
    foreach ($setting in @('window_animation_scale','transition_animation_scale','animator_duration_scale')) { Invoke-Adb shell settings put global $setting 0 }
    Invoke-Adb shell input keyevent KEYCODE_WAKEUP
    Invoke-Adb shell wm dismiss-keyguard
    $runner = 'com.anaalarm.test/androidx.test.runner.AndroidJUnitRunner'
    $baseArgs = @('shell','am','instrument','-w','-r','-e','debug','false','-e','listener','com.anaalarm.support.TestEvidenceListener')
    if ($Filter) { $baseArgs += @('-e','class',$Filter) }
    $expected = @(Get-Content scripts/ci/instrumented-tests.txt | Where-Object { $_ -and -not $_.StartsWith('#') })
    if ($Filter) {
        $filters = $Filter -split ','
        $expected = @($expected | Where-Object { $test = $_; @($filters | Where-Object { $test -eq $_ -or $test.StartsWith("$_#") }).Count -gt 0 })
    }
    if ($expected.Count -eq 0) { throw 'Filter selects no tests from the reviewed manifest.' }
    $discovery = @(Invoke-Adb @baseArgs -e log true $runner)
    $discovery | Set-Content (Join-Path $artifactDir 'discovery.log')
    $discovered = @(); $fields = @{}
    foreach ($line in $discovery) {
        if ($line -match '^INSTRUMENTATION_STATUS: ([^=]+)=(.*)$') { $fields[$Matches[1]] = $Matches[2].Trim() }
        if ($line -match '^INSTRUMENTATION_STATUS_CODE: 1$') {
            if ($fields.ContainsKey('class') -and $fields.ContainsKey('test')) { $discovered += "$($fields['class'])#$($fields['test'])" }
            $fields = @{}
        } elseif ($line -match '^INSTRUMENTATION_STATUS_CODE:') { $fields = @{} }
    }
    $difference = @(Compare-Object ($expected | Sort-Object -Unique) ($discovered | Sort-Object -Unique))
    if ($difference.Count) { $difference | Format-Table; throw 'Test discovery differs from the reviewed manifest.' }
    $runArgs = $baseArgs
    if ($Coverage) { $runArgs += @('-e','coverage','true','-e','coverageFile','/sdcard/Android/data/com.anaalarm/files/coverage.ec') }
    Write-Host "Running $($expected.Count) tests on $Serial (API $api)..."
    $output = @(Invoke-Adb @runArgs $runner)
    $exitStatus = $LASTEXITCODE
    $output | Set-Content (Join-Path $artifactDir 'instrumentation.log')
    $output | Set-Content (Join-Path $root 'adb-instrument.log')
    python scripts/ci/check-instrumentation.py results (Join-Path $artifactDir 'instrumentation.log') --filter "$Filter"
    $checkStatus = $LASTEXITCODE
    $text = $output -join "`n"
    $skipCount = @($output | Where-Object { $_ -match '^INSTRUMENTATION_STATUS_CODE: -(3|4)\s*$' }).Count
    $success = $text -match 'OK \((\d+) tests?\)'
    if ($checkStatus -ne 0 -or $exitStatus -ne 0 -or -not $success -or [int]$Matches[1] -ne $expected.Count -or $skipCount -ne 0) {
        $output | Select-String 'stack=|^\s+at com\.anaalarm|Tests run:|Failures:' | ForEach-Object { $_.Line }
        throw "Instrumentation failed or skipped required tests. Evidence: $artifactDir"
    }
    if ($Coverage) {
        Invoke-Adb pull /sdcard/Android/data/com.anaalarm/files/coverage.ec (Join-Path $artifactDir 'coverage.ec') | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Coverage measurement was not produced.' }
    }
    Write-Host "SUCCESS: $($expected.Count) tests, zero skips. Evidence: $artifactDir"
} finally {
    if ($Coverage -and -not (Test-Path -LiteralPath (Join-Path $artifactDir 'coverage.ec'))) {
        Invoke-Adb pull /sdcard/Android/data/com.anaalarm/files/coverage.ec (Join-Path $artifactDir 'coverage.ec') 2>$null | Out-Null
    }
    Invoke-Adb logcat -d -v threadtime | Set-Content (Join-Path $artifactDir 'logcat.txt')
    Invoke-Adb shell dumpsys activity activities | Set-Content (Join-Path $artifactDir 'activities.txt')
    Invoke-Adb pull /sdcard/Android/data/com.anaalarm/files/test-evidence (Join-Path $artifactDir 'test-evidence') 2>$null | Out-Null
    $env:JAVA_TOOL_OPTIONS = $priorJavaOptions
}
