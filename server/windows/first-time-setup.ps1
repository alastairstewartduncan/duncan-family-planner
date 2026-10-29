<#
  Duncan Family Planner - first-time setup of the home server.
  Run first-time-setup.cmd (it asks for administrator rights).

    1. Installs and starts the server (install.ps1).
    2. Creates family.json from family.example.json if there isn't one.
    3. Asks for the family PIN (typed here, never stored in the repo).
    4. Loads the family into the database (npm run setup) and restarts the server.
    5. Removes the PIN from family.json again.
#>
$ErrorActionPreference = "Stop"
$ServerDir = Split-Path -Parent $PSScriptRoot
$TaskName = "Duncan Family Planner"
$utf8 = New-Object System.Text.UTF8Encoding $false   # no byte-order mark

function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }

& (Join-Path $PSScriptRoot "install.ps1")

Push-Location $ServerDir
try {
  $family = Join-Path $ServerDir "family.json"
  if (-not (Test-Path $family)) {
    Copy-Item (Join-Path $ServerDir "family.example.json") $family
    Write-Host "Created family.json from the example (Claire, Alastair, Alfie)."
  }

  Step "Family PIN"
  Write-Host "Everyone types this once, the first time they sign in on their phone."
  do {
    $pin = Read-Host "Choose a family PIN (4 to 8 digits)"
  } until ($pin -match '^\d{4,8}$')

  $data = Get-Content $family -Raw | ConvertFrom-Json
  $data | Add-Member -NotePropertyName pin -NotePropertyValue $pin -Force
  [IO.File]::WriteAllText($family, ($data | ConvertTo-Json -Depth 20), $utf8)

  Step "Setting up the family"
  try {
    cmd /c "npm run setup 2>&1" | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Family setup failed (exit code $LASTEXITCODE) - see the messages above." }
  } finally {
    # Never leave the PIN lying around in the file.
    $data.PSObject.Properties.Remove("pin")
    [IO.File]::WriteAllText($family, ($data | ConvertTo-Json -Depth 20), $utf8)
  }

  Step "Restarting the server"
  Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
  Start-Sleep -Seconds 2
  Start-ScheduledTask -TaskName $TaskName
  Start-Sleep -Seconds 4
  $port = (Get-Content (Join-Path $ServerDir "config.json") -Raw | ConvertFrom-Json).port
  try {
    $health = Invoke-RestMethod -Uri "http://localhost:$port/api/health" -TimeoutSec 5
    Write-Host "All done. The server is running and the family is set up (PIN set: $($health.hasPin))." -ForegroundColor Green
  } catch {
    Write-Warning "The server didn't answer yet. Check $ServerDir\logs\server.log"
  }
} finally { Pop-Location }
