# Runs the on-device instrumented suite through adb.
#
# AGP's own :app:connectedDebugAndroidTest task reports results over gRPC+TLS, which local
# antivirus HTTPS interception breaks ("Failed to receive the UTP test results"), so the suite
# is driven with `am instrument` instead.
#
# Usage:
#   .\scripts\run-instrumented-tests.ps1
#   .\scripts\run-instrumented-tests.ps1 -Filter com.anaalarm.ui.HomeScreenInstrumentedTest
#   .\scripts\run-instrumented-tests.ps1 -Filter com.anaalarm.ui.HomeScreenInstrumentedTest#emptyStateExplainsWhatToDoNext
param(
    [string]$Filter,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) { Write-Error "adb not found at $adb - set ANDROID_HOME" }

$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

# Local TLS interception breaks Java's bundled cacerts during dependency resolution.
$env:JAVA_TOOL_OPTIONS = '-Djavax.net.ssl.trustStoreType=Windows-ROOT'

$devices = & $adb devices
if (-not ($devices -match 'device$')) {
    Write-Error 'No Android device/emulator in state "device". Start one and retry.'
}

if (-not $SkipBuild) {
    Write-Host 'Building + installing app and androidTest APKs...' -ForegroundColor Cyan
    & .\gradlew.bat :app:installDebug :app:installDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

Write-Host 'Preparing device state...' -ForegroundColor Cyan
& $adb shell pm grant com.anaalarm android.permission.POST_NOTIFICATIONS 2>$null
& $adb shell pm grant com.anaalarm android.permission.RECORD_AUDIO 2>$null
& $adb shell appops set com.anaalarm SCHEDULE_EXACT_ALARM allow
& $adb shell appops set com.anaalarm USE_FULL_SCREEN_INTENT allow
& $adb shell settings put global window_animation_scale 0
& $adb shell settings put global transition_animation_scale 0
& $adb shell settings put global animator_duration_scale 0
# Keep the screen on and unlocked so full-screen alarm activities can appear.
& $adb shell svc power stayon true 2>$null
& $adb shell wm dismiss-keyguard 2>$null

$runner = 'com.anaalarm.test/androidx.test.runner.AndroidJUnitRunner'
$args = @('shell', 'am', 'instrument', '-w', '-r', '-e', 'debug', 'false')
if ($Filter) { $args += @('-e', 'class', $Filter) }
$args += $runner

$out = Join-Path $root 'adb-instrument.log'
Write-Host "Running instrumentation$(if ($Filter) { " (filter: $Filter)" })..." -ForegroundColor Cyan
& $adb @args | Tee-Object -FilePath $out | Out-Null

$text = Get-Content $out -Raw

# Surface failures inline; the raw stream is verbose.
if ($text -match 'FAILURES!!!' -or $text -notmatch 'OK \((\d+) tests?\)') {
    Write-Host 'FAILED' -ForegroundColor Red
    ($text -split "`n") | Select-String -Pattern 'INSTRUMENTATION_STATUS: stack=|^\s+at com\.anaalarm|Tests run:|Failures:' |
        ForEach-Object { $_.Line }
    Write-Host "Full output: $out"
    exit 1
}

Write-Host "SUCCESS: $($Matches[0])" -ForegroundColor Green
exit 0
