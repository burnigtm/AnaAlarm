param([Parameter(Mandatory=$true)][string]$Serial, [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
Set-Location $repo
$sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$tools = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (!$tools) { throw 'Android build-tools are required.' }
$api = (& $adb -s $Serial shell getprop ro.build.version.sdk).Trim()
if ((& $adb -s $Serial shell getprop ro.kernel.qemu).Trim() -ne '1') { throw 'Release smoke requires an emulator.' }
$artifact = Join-Path $repo "artifacts\release-api-$api\$(Get-Date -Format yyyyMMdd-HHmmss)"
New-Item -ItemType Directory -Force $artifact | Out-Null
$log = Join-Path $artifact 'release-smoke.log'
function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments 2>&1 | Tee-Object -FilePath $log -Append | Write-Output
    if ($LASTEXITCODE -ne 0) { throw "$Executable failed with exit $LASTEXITCODE" }
}
if (!$SkipBuild) { Invoke-Checked (Join-Path $repo 'gradlew.bat') @(':app:assembleRelease','--dependency-verification=strict','--console=plain') }
$unsigned = Join-Path $repo 'app\build\outputs\apk\release\app-release-unsigned.apk'
$mapping = Join-Path $repo 'app\build\outputs\mapping\release\mapping.txt'
if (!(Test-Path $mapping) -or !(Test-Path $unsigned)) { throw 'Minified APK and R8 mapping are required.' }
$env:PYTHONUTF8 = '1'
@'
import sys,zipfile
from pathlib import Path
apk,mapping=sys.argv[1:]
assert 'com.anaalarm.data.DataExport' in Path(mapping).read_text(encoding='utf-8')
with zipfile.ZipFile(apk) as z:
 data=b''.join(z.read(n) for n in z.namelist() if n.startswith('classes') and n.endswith('.dex'))
 for marker in (b'DataExport$Payload$$serializer',b'anaalarm-export',b'sessionMode',b'DailyPriorityExport'):
  assert marker in data, f'Minified export descriptor missing: {marker!r}'
 assert b'ScriptedRecognitionService' not in data and b'ScriptedTtsService' not in data
print('Minified export descriptors retained; scripted speech engines absent.')
'@ | python - $unsigned $mapping | Tee-Object -FilePath $log -Append
if ($LASTEXITCODE -ne 0) { throw 'Minified descriptor verification failed.' }
$aligned = Join-Path $artifact 'release-aligned.apk'
$signed = Join-Path $artifact 'anaalarm-release-smoke.apk'
$keystore = Join-Path $artifact 'ephemeral.p12'
$env:ANA_SMOKE_STORE_PASSWORD = [Guid]::NewGuid().ToString('N')
try {
    Invoke-Checked 'keytool' @('-genkeypair','-noprompt','-keystore',$keystore,'-storetype','PKCS12','-storepass:env','ANA_SMOKE_STORE_PASSWORD','-keypass:env','ANA_SMOKE_STORE_PASSWORD','-alias','anaalarm-smoke','-keyalg','RSA','-keysize','3072','-validity','1','-dname','CN=AnaAlarm Emulator Smoke,O=Local,C=US')
    Invoke-Checked (Join-Path $tools.FullName 'zipalign.exe') @('-f','4',$unsigned,$aligned)
    Invoke-Checked (Join-Path $tools.FullName 'apksigner.bat') @('sign','--ks',$keystore,'--ks-key-alias','anaalarm-smoke','--ks-pass','env:ANA_SMOKE_STORE_PASSWORD','--key-pass','env:ANA_SMOKE_STORE_PASSWORD','--out',$signed,$aligned)
    Invoke-Checked (Join-Path $tools.FullName 'apksigner.bat') @('verify','--verbose','--print-certs',$signed)
    & $adb -s $Serial uninstall com.anaalarm.test 2>&1 | Out-Null
    & $adb -s $Serial uninstall com.anaalarm 2>&1 | Out-Null
    Invoke-Checked $adb @('-s',$Serial,'install',$signed)
    Invoke-Checked $adb @('-s',$Serial,'shell','pm','grant','com.anaalarm','android.permission.RECORD_AUDIO')
    if ([int]$api -ge 33) { Invoke-Checked $adb @('-s',$Serial,'shell','pm','grant','com.anaalarm','android.permission.POST_NOTIFICATIONS') }
    # Some API 26 images disallow clearing the system log. Filter crash evidence by the new PID.
    & $adb -s $Serial logcat -c 2>&1 | Tee-Object -FilePath $log -Append | Out-Null
    $launch = & $adb -s $Serial shell am start -W -n com.anaalarm/.MainActivity
    $launch | Tee-Object -FilePath $log -Append
    if ($LASTEXITCODE -ne 0 -or !($launch -match 'Status: ok') -or !($launch -match 'Activity: com\.anaalarm/.MainActivity')) { throw 'Release launch failed.' }
    Start-Sleep -Seconds 3
    $pidOutput = & $adb -s $Serial shell pidof com.anaalarm
    if (!$pidOutput) { throw 'Release process died after launch.' }
    $package = & $adb -s $Serial shell dumpsys package com.anaalarm
    if ($package -match 'flags=.*DEBUGGABLE') { throw 'Release is unexpectedly debuggable.' }
    $runtime = & $adb -s $Serial logcat -d -v threadtime -s AndroidRuntime
    $runtime | Set-Content (Join-Path $artifact 'runtime.log')
    if ($runtime -match "Process: com\.anaalarm, PID: $($pidOutput.Trim())\b") { throw 'Release crashed.' }
    Invoke-Checked $adb @('-s',$Serial,'shell','screencap','-p','/sdcard/anaalarm-release-smoke.png')
    Invoke-Checked $adb @('-s',$Serial,'pull','/sdcard/anaalarm-release-smoke.png',(Join-Path $artifact 'release.png'))
    "Minified release smoke passed on API $api ($Serial)." | Tee-Object -FilePath $log -Append
} finally {
    Remove-Item Env:\ANA_SMOKE_STORE_PASSWORD -ErrorAction SilentlyContinue
    if (Test-Path $keystore) { Remove-Item -LiteralPath $keystore }
    & $adb -s $Serial logcat -d | Set-Content (Join-Path $artifact 'logcat.txt')
}
