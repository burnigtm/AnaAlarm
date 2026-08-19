# Push HEAD to the GitHub CI/signing mirror.
#
# Origin (origin.cursor.com) is the source of truth. GitHub Actions only runs
# when the same commit is on github.com/burnigtm/AnaAlarm. This script does not
# change git identity or force-push.
#
# Usage: .\scripts\push-ci-mirror.ps1
param(
    [string]$RemoteName = "github",
    [string]$MirrorUrl = "https://github.com/burnigtm/AnaAlarm.git"
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

$existing = git remote | Where-Object { $_ -eq $RemoteName }
if (-not $existing) {
    Write-Host "Adding remote '$RemoteName' -> $MirrorUrl"
    git remote add $RemoteName $MirrorUrl
}

$branch = git rev-parse --abbrev-ref HEAD
if ($branch -eq "HEAD") {
    Write-Error "Detached HEAD; check out a branch before mirroring."
}

Write-Host "Pushing $branch to $RemoteName (CI mirror). Origin-primary remotes are unchanged."
git push $RemoteName "HEAD:refs/heads/$branch"
