# Captures product-tour screenshots from a debug emulator build.
# Requires: ANDROID_HOME, a booted emulator/device, and :app:installDebug.
param(
    [string]$OutDir = (Join-Path $PSScriptRoot '..\docs\screenshots')
)

$ErrorActionPreference = 'Continue'
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }

Add-Type -AssemblyName System.Drawing
function Resize-Png([string]$path, [int]$width = 540) {
    $full = (Resolve-Path $path).Path
    $img = [System.Drawing.Image]::FromFile($full)
    $height = [int][Math]::Round($img.Height * $width / $img.Width)
    $bmp = New-Object System.Drawing.Bitmap $width, $height
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.DrawImage($img, 0, 0, $width, $height)
    $g.Dispose()
    $img.Dispose()
    $bmp.Save($full, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}

function Dismiss-Anr {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'SilentlyContinue'
    & $adb shell uiautomator dump /sdcard/uidump.xml | Out-Null
    $tmp = Join-Path $env:TEMP 'anaalarm-uidump.xml'
    & $adb pull /sdcard/uidump.xml $tmp | Out-Null
    $ErrorActionPreference = $prev
    if (-not (Test-Path $tmp)) { return }
    $xml = Get-Content $tmp -Raw
    if ($xml -notmatch 'isn.t responding' -and $xml -notmatch 'isn&#39;t responding') { return }
    if ($xml -match 'text="Wait"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
        $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
        $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
        & $adb shell input tap $x $y | Out-Null
        Start-Sleep -Seconds 1
    }
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$scenes = @(
    @{ scene = 'home_empty'; file = 'home-empty.png' },
    @{ scene = 'home'; file = 'home-morning.png' },
    @{ scene = 'settings_buddy'; file = 'settings-buddy.png' },
    @{ scene = 'alarm_edit'; file = 'alarm-edit.png' },
    @{ scene = 'stats'; file = 'stats.png' },
    @{ scene = 'wake_speaking'; file = 'wake-speaking.png' },
    @{ scene = 'wake_listening'; file = 'wake-listening.png' },
    @{ scene = 'challenge_math'; file = 'challenge-math.png' },
    @{ scene = 'challenge_memory'; file = 'challenge-memory.png' }
)

foreach ($item in $scenes) {
    Write-Host "Capturing $($item.scene)"
    & $adb shell am force-stop com.anaalarm
    Start-Sleep -Seconds 1
    & $adb logcat -c
    & $adb shell am start -n com.anaalarm/.debug.DocsGalleryActivity --es scene $item.scene | Out-Null
    $pattern = 'ready scene=' + $item.scene
    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep -Milliseconds 500
        $logs = & $adb logcat -d -s DocsGallery:I
        if ($logs -match [regex]::Escape($pattern)) { $ready = $true; break }
    }
    if (-not $ready) { Write-Host "  WARN: no ready log for $($item.scene)" }
    Start-Sleep -Seconds 2
    Dismiss-Anr
    Start-Sleep -Seconds 2
    & $adb shell screencap -p /sdcard/anaalarm-docs.png
    $dest = Join-Path $OutDir $item.file
    $ErrorActionPreference = 'SilentlyContinue'
    & $adb pull /sdcard/anaalarm-docs.png $dest | Out-Null
    $ErrorActionPreference = 'Stop'
    Resize-Png $dest 540
    Write-Host "  saved $dest ($((Get-Item $dest).Length) bytes)"
}
