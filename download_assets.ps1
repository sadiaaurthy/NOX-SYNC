$base = "https://raw.githubusercontent.com/sadiaaurthy/NOX-SYNC/main/assets"
$dest = "$PSScriptRoot\core\src\main\resources\assets"

# assets live in the project's assets folder (libGDX default)
$dest = "$PSScriptRoot\assets"

$files = @("Walking.jpg", "Running.jpg", "Hacker_walking.png", "Hacker_run.png", "idea.png")

foreach ($f in $files) {
    $url = "$base/$f"
    $out = Join-Path $dest $f
    Write-Host "Downloading $f ..."
    Invoke-WebRequest -Uri $url -OutFile $out -UseBasicParsing
    Write-Host "  -> saved to $out"
}

Write-Host ""
Write-Host "Done! All 5 asset files downloaded."
Read-Host "Press Enter to close"
