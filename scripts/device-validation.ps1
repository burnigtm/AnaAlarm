<#
.SYNOPSIS
    Physical release-gate evidence capture for issue #6.

.DESCRIPTION
    Drives one evidence bundle per attached device for a given validation scenario:
    device props, a clean logcat window around the operator-driven alarm run,
    an optional screen recording, and a pre-filled results checklist under
    artifacts/device-validation-<serial>/. Procedure: docs/RELEASE_GATE_VALIDATION.md

.PARAMETER Scenario
    A = airplane-mode/no-key audible fallback; B = screen-off/keyguard delivery;
    C = Play Console paperwork (no device steps; prints the checklist only).

.PARAMETER Serials
    Optional comma-separated device serials. Defaults to every attached device.

.PARAMETER DurationSeconds
    Logcat/screen-recording window size. Default 240.

.PARAMETER ScreenRecord
    Also capture a screen recording during the window (Scenario B recommended).

.EXAMPLE
    powershell -File scripts\device-validation.ps1 -Scenario A
    powershell -File scripts\device-validation.ps1 -Scenario B -Serials R58N...,PIXEL9 -ScreenRecord
#>
param(
    [ValidateSet('A', 'B', 'C')]
    [string]$Scenario = 'A',
    [string]$Serials = '',
    [int]$DurationSeconds = 240,
    [switch]$ScreenRecord
)

$ErrorActionPreference = 'Stop'

function Resolve-Adb {
    if ($script:AdbPath) { return $script:AdbPath }
    $candidates = @(
        $env:ANAALARM_ADB,
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:ANDROID_HOME\platform-tools\adb.exe",
        "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe"
    ) | Where-Object { $_ -and (Test-Path $_) }
    if ($candidates) { return (Resolve-Path ($candidates | Select-Object -First 1)).Path }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw 'adb not found. Install platform-tools or pass -Adb <path> / set ANAALARM_ADB.'
}

$adb = Resolve-Adb
$repoRoot = Split-Path -Parent $PSScriptRoot
$outRoot = Join-Path $repoRoot 'artifacts'

if (-not $Serials) {
    $listing = & $adb devices | Select-Object -Skip 1
    $Serials = (@($listing | ForEach-Object {
        $parts = ($_ -split '\s+') | Where-Object { $_ }
        if ($parts.Count -ge 2 -and $parts[1] -eq 'device') { $parts[0] }
    }) -join ',')
}
if (-not $Serials) {
    Write-Error 'No attached devices found. Connect hardware (USB debugging on) or pass -Serials.'
}

function Invoke-Adb {
    param([string]$Serial, [string[]]$Arguments)
    & $adb "-s" $Serial @Arguments
}

function Get-Marker {
    param([string]$LogFile, [string]$Pattern)
    if (Test-Path $LogFile) {
        $hit = Select-String -Path $LogFile -Pattern $Pattern -SimpleMatch | Select-Object -First 1
        if ($hit) { return 'FOUND' }
    }
    return 'NOT FOUND'
}

foreach ($serial in ($Serials -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })) {
    Write-Host "`n=== Device $serial ===" -ForegroundColor Cyan
    $outDir = Join-Path $outRoot "device-validation-$serial"
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null

    # --- Device identity -------------------------------------------------------
    $sdk      = (Invoke-Adb $serial @('shell', 'getprop', 'ro.build.version.sdk'))      | Out-String
    $release  = (Invoke-Adb $serial @('shell', 'getprop', 'ro.build.version.release'))  | Out-String
    $model    = (Invoke-Adb $serial @('shell', 'getprop', 'ro.product.model'))          | Out-String
    $brand    = (Invoke-Adb $serial @('shell', 'getprop', 'ro.product.brand'))          | Out-String
    $patch    = (Invoke-Adb $serial @('shell', 'getprop', 'ro.build.version.security_patch')) | Out-String
    @"
serial=$serial
brand=$($brand.Trim())
model=$($model.Trim())
api_level=$($sdk.Trim())
android_release=$($release.Trim())
security_patch=$($patch.Trim())
scenario=$Scenario
captured_at=$(Get-Date -Format o)
"@ | Set-Content (Join-Path $outDir 'device-info.txt')
    Write-Host ("API $($sdk.Trim()) · $($brand.Trim()) $($model.Trim())")

    if ($Scenario -eq 'C') {
        Copy-Item (Join-Path $repoRoot 'docs\RELEASE_GATE_VALIDATION.md') $outDir -Force
        Write-Host 'Scenario C is Play Console paperwork - see docs/RELEASE_GATE_VALIDATION.md §3 copied alongside.'
        continue
    }

    # --- Pre-flight ------------------------------------------------------------
    (Invoke-Adb $serial @('shell', 'dumpsys', 'power')   | Select-String 'mWakefulness')     | Out-String | Set-Content (Join-Path $outDir 'preflight-screen-state.txt')
    (Invoke-Adb $serial @('shell', 'dumpsys', 'window')  | Select-String 'keyguard')         | Out-String | Set-Content (Join-Path $outDir 'preflight-keyguard.txt')

    Invoke-Adb $serial @('logcat', '-c')

    $recording = $null
    if ($ScreenRecord) {
        $remote = "/sdcard/anaalarm-validation-$Scenario.mp4"
        $recording = Start-Process -FilePath $adb -ArgumentList @('-s', $serial, 'shell', 'screenrecord', '--time-limit', "$DurationSeconds", $remote) -PassThru -WindowStyle Hidden
    }

    # --- Operator procedure ----------------------------------------------------
    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    switch ($Scenario) {
        'A' {
            Write-Host "`n[A1] Airplane mode ON, API key removed. Schedule a ONE-SHOT alarm 2-3 minutes ahead in the app, then press POWER (screen off)." -ForegroundColor Yellow
            Read-Host  '[A2] After the alarm DELIVERS (sound/vibration audible), press Enter to stamp the moment'
            $stamp1 = $stopwatch.ElapsedMilliseconds
            Write-Host '[A3] Do NOT touch the device: confirm sound+vibration persist for at least 60 seconds.' -ForegroundColor Yellow
            Read-Host  '[A4] Once confirmed, wake the device, press Snooze (or Stop), and press Enter'
        }
        'B' {
            Write-Host "`n[B1] PIN lock verified working. Schedule a ONE-SHOT alarm 2-3 minutes ahead, then press POWER." -ForegroundColor Yellow
            Read-Host  "[B2] When the wake-up screen shows OVER THE KEYGUARD, press Enter (a screenshot will be taken)"
            Invoke-Adb $serial @('exec-out', 'screencap', '-p') |
                ForEach-Object { [byte[]][char[]]$_ } |
                Set-Content -Encoding Byte (Join-Path $outDir "scenario-B-wake-screen.png")
            Write-Host '[B3] Answer one turn (voice or typed), press Stop, verify the session closes.' -ForegroundColor Yellow
            Read-Host  '[B4] When closed, press Enter'
            $stamp1 = $null
        }
    }

    # --- Evidence window close-out ---------------------------------------------
    while ($stopwatch.Elapsed.TotalSeconds -lt $DurationSeconds) { Start-Sleep -Milliseconds 500 }
    Invoke-Adb $serial @('logcat', '-d', '-v', 'threadtime') |
        Out-String | Set-Content (Join-Path $outDir "scenario-$Scenario-logcat.txt")
    $logcat = Join-Path $outDir "scenario-$Scenario-logcat.txt"

    if ($recording) {
        Wait-Process -Id $recording.Id -ErrorAction SilentlyContinue
        Invoke-Adb $serial @('pull', "/sdcard/anaalarm-validation-$Scenario.mp4", (Join-Path $outDir "scenario-$Scenario-screen.mp4")) | Out-Null
        Invoke-Adb $serial @('shell', 'rm', "-f", "/sdcard/anaalarm-validation-$Scenario.mp4") | Out-Null
    }

    # --- Checklist --------------------------------------------------------------
    $markers = [ordered]@{
        'AlarmReceiver fired'                    = 'AlarmReceiver fired alarmId='
        'alarm_to_first_audio latency recorded'  = 'metric=alarm_to_first_audio'
        'AlarmService foreground start'          = 'startForeground'
        'WakeUpActivity displayed over keyguard' = 'Displayed com.anaalarm/.ui.wakeup.WakeUpActivity'
    }
    $lines = foreach ($name in $markers.Keys) {
        $state = Get-Marker $logcat $markers[$name]
        "- [$(if ($state -eq 'FOUND') {'x'} else {' '})] $name : $state"
    }
    @"
# Validation checklist - scenario $Scenario - $serial

Device: $($brand.Trim()) $($model.Trim()), API $($sdk.Trim())
Captured: $(Get-Date -Format o)
Procedure: docs/RELEASE_GATE_VALIDATION.md section for this scenario.

## Automated logcat markers
$($lines -join "`n")

## Operator observations (fill in)
- [ ] Sound and vibration persisted >= 60 seconds before any interaction (Scenario A)
- [ ] Wake-up screen appeared over the keyguard without user input (Scenario B)
- [ ] Explicit Stop/Snooze silenced everything within ~2 seconds
- [ ] Screenshots/recording attached to issue #6

Notes:
$(
    if ($stamp1) { "Delivery stamped at ${stamp1} ms into the window." } else { '' }
)
"@ | Set-Content (Join-Path $outDir 'results-checklist.md')

    Write-Host "Bundle written: $outDir" -ForegroundColor Green
}
