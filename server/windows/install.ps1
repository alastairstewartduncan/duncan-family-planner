<#
  Duncan Family Planner - install or update the server on Windows.

  Easiest: double-click install.cmd in this folder.
  Or from an *Administrator* PowerShell window:
      Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
      & <repo>\server\windows\install.ps1

  What it does:
    1. Checks Node.js 22.13+ is installed.
    2. Installs packages and builds the server.
    3. Registers a scheduled task that starts the server when Windows starts
       (even before anyone logs in) and restarts it if it stops.
    4. Opens the server port in Windows Firewall for Tailscale and your home network only.
    5. Starts the server.
  Safe to re-run after pulling a new version (it stops, rebuilds and restarts).
#>
param(
  [int]$Port = 8787,
  [string]$TaskName = "Duncan Family Planner"
)

$ErrorActionPreference = "Stop"
$ServerDir = Split-Path -Parent $PSScriptRoot

function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }

# --- Admin check -------------------------------------------------------------
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
  [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) { throw "Please run this from an Administrator PowerShell window (right-click PowerShell > Run as administrator)." }

# --- Node.js -----------------------------------------------------------------
Step "Checking Node.js"
$node = (Get-Command node -ErrorAction SilentlyContinue).Source
if (-not $node) { throw "Node.js isn't installed. Install the LTS version from https://nodejs.org (22.13 or newer), then run this again." }
$ver = (& node --version).TrimStart("v").Split(".")
if ([int]$ver[0] -lt 22 -or ([int]$ver[0] -eq 22 -and [int]$ver[1] -lt 13)) {
  throw "Node.js $(& node --version) is too old. Install 22.13 or newer from https://nodejs.org."
}
Write-Host "Node.js $(& node --version) at $node"

# --- Stop an existing install --------------------------------------------------
if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
  Step "Stopping the running server"
  Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
  Start-Sleep -Seconds 2
}

# --- Build ---------------------------------------------------------------------
Step "Installing packages and building"
Push-Location $ServerDir
try {
  if (Test-Path package-lock.json) { npm ci --no-audit --no-fund } else { npm install --no-audit --no-fund }
  if ($LASTEXITCODE -ne 0) { throw "npm install failed" }
  npm run build
  if ($LASTEXITCODE -ne 0) { throw "Build failed" }
} finally { Pop-Location }

# --- Config ------------------------------------------------------------------------
$config = Join-Path $ServerDir "config.json"
if (-not (Test-Path $config)) {
  @{ port = $Port; dataDir = "data"; googleKeyFile = "google-key.json"; backupsToKeep = 14 } |
    ConvertTo-Json | Set-Content -Encoding UTF8 $config
  Write-Host "Created config.json (port $Port)"
} else {
  $Port = (Get-Content $config -Raw | ConvertFrom-Json).port
}

$db = Join-Path $ServerDir "data\planner.db"
if (-not (Test-Path $db)) {
  Write-Warning "The family isn't set up yet. Before using the app run:  npm run setup   (see docs\SETUP.md step 3)"
}

# --- Scheduled task --------------------------------------------------------------
Step "Registering the '$TaskName' startup task"
New-Item -ItemType Directory -Force -Path (Join-Path $ServerDir "logs") | Out-Null
$cmd = "/c `"`"$node`" --disable-warning=ExperimentalWarning dist\server.js >> logs\server.log 2>&1`""
$action = New-ScheduledTaskAction -Execute "cmd.exe" -Argument $cmd -WorkingDirectory $ServerDir
$trigger = New-ScheduledTaskTrigger -AtStartup
$principal = New-ScheduledTaskPrincipal -UserId "SYSTEM" -LogonType ServiceAccount -RunLevel Highest
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable `
  -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero)
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null

# --- Firewall ------------------------------------------------------------------------
Step "Opening port $Port for Tailscale and the home network"
Get-NetFirewallRule -DisplayName "$TaskName*" -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName "$TaskName (Tailscale + LAN)" -Direction Inbound -Protocol TCP -LocalPort $Port `
  -RemoteAddress @("100.64.0.0/10", "LocalSubnet") -Action Allow -Profile Any | Out-Null

# --- Start ---------------------------------------------------------------------------
Step "Starting the server"
Start-ScheduledTask -TaskName $TaskName
Start-Sleep -Seconds 4
try {
  $health = Invoke-RestMethod -Uri "http://localhost:$Port/api/health" -TimeoutSec 5
  Write-Host "Server is running (PIN set: $($health.hasPin))." -ForegroundColor Green
} catch {
  Write-Warning "The server didn't answer yet. Check $ServerDir\logs\server.log"
}

$ts = Get-Command tailscale -ErrorAction SilentlyContinue
if ($ts) {
  $name = (& tailscale status --json | ConvertFrom-Json).Self.DNSName.TrimEnd(".")
  Write-Host "`nIn the app, use this server address:  http://$($name.Split('.')[0]):$Port" -ForegroundColor Green
  Write-Host "(or the full name http://$($name):$Port)"
} else {
  Write-Warning "Tailscale isn't installed on this PC yet - see docs\SETUP.md step 4."
}
