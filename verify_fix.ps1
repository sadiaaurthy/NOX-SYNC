param(
    [switch]$RunGame
)

$ErrorActionPreference = "Stop"

function Step($message) {
    Write-Host ""
    Write-Host "=== $message ===" -ForegroundColor Cyan
}

Step "Repository check"
if (-not (Test-Path ".\gradlew.bat")) {
    throw "gradlew.bat was not found. Open PowerShell in the NOX-SYNC repository root."
}

$branch = (git branch --show-current).Trim()
Write-Host "Current branch: $branch"
if ($branch -ne "fix/fabian-level1-bugs") {
    throw "Wrong branch. Expected fix/fabian-level1-bugs, found $branch."
}

Step "Working-tree check"
git status --short

Step "Whitespace and conflict-marker check"
git diff --check

$conflictMarkers = git grep -n -E "^(<<<<<<<|=======|>>>>>>>)" -- `
    ":(exclude)NOX_SYNC_Fabian_Fix_Kit/*" 2>$null
if ($LASTEXITCODE -eq 0 -and $conflictMarkers) {
    Write-Host $conflictMarkers -ForegroundColor Red
    throw "Git conflict markers were found."
}
$global:LASTEXITCODE = 0

Step "Core compile"
.\gradlew.bat core:compileJava

Step "Launcher compile"
.\gradlew.bat launcher:compileJava

Step "LWJGL3 compile"
.\gradlew.bat lwjgl3:compileJava

Step "Tests"
.\gradlew.bat test

Step "Changed-file summary"
git diff --stat
git status --short

if ($RunGame) {
    Step "Launching JavaFX launcher"
    .\gradlew.bat launcher:run
}

Write-Host ""
Write-Host "Automated verification completed. Gameplay tests in TEST_MATRIX.md are still required." -ForegroundColor Green
