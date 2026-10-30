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
    4. Opens the server port in Windows Firewall (the phones reach it over the
       internet through your router, encrypted with the server's own certificate).
    5. Starts the server and shows the security code phones should show.
  Safe to re-run after pulling a new version (it stops, rebuilds and restarts).
#>
param(
  [int]$Port = 8787,
  [string]$TaskName = "Duncan Family Planner"
)

$ErrorActionPreference = "Stop"
$ServerDir = Split-Path -Parent $PSScriptRoot

function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }

# The server uses its own HTTPS certificate, which Windows PowerShell won't accept,
# so ask curl.exe (built into Windows 10/11) and tell it not to check the certificate.
function Get-Health([int]$p) {
  foreach ($url in @("https://localhost:$p/api/health", "http://localhost:$p/api/health")) {
    $out = & curl.exe -sk --max-time 5 $url 2>$null
    if ($LASTEXITCODE -eq 0 -and $out) { try { return ($out | ConvertFrom-Json) } catch { } }
  }
  return $null
}

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
  # Run npm through cmd.exe: npm prints harmless warnings on stderr, which
  # PowerShell (especially the ISE) would otherwise treat as a fatal error.
  # Success or failure is judged on npm's exit code only.
  $install = if (Test-Path package-lock.json) { "npm ci" } else { "npm install" }
  cmd /c "$install --no-audit --no-fund --loglevel=error 2>&1" | Out-Host
  if ($LASTEXITCODE -ne 0) { throw "npm install failed (exit code $LASTEXITCODE) - see the messages above." }
  cmd /c "npm run build 2>&1" | Out-Host
  if ($LASTEXITCODE -ne 0) { throw "Build failed (exit code $LASTEXITCODE) - see the messages above." }
} finally { Pop-Location }

# --- Config ------------------------------------------------------------------------
$config = Join-Path $ServerDir "config.json"
if (-not (Test-Path $config)) {
  $json = @{ port = $Port; dataDir = "data"; googleKeyFile = "google-key.json"; backupsToKeep = 14 } | ConvertTo-Json
  [IO.File]::WriteAllText($config, $json, (New-Object System.Text.UTF8Encoding $false))
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
Step "Opening port $Port in Windows Firewall"
Get-NetFirewallRule -DisplayName "$TaskName*" -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName "$TaskName (HTTPS)" -Direction Inbound -Protocol TCP -LocalPort $Port `
  -Action Allow -Profile Any | Out-Null

# --- Start ---------------------------------------------------------------------------
Step "Starting the server"
Start-ScheduledTask -TaskName $TaskName
Start-Sleep -Seconds 5
$health = Get-Health $Port
if ($health) {
  Write-Host "Server is running (PIN set: $($health.hasPin))." -ForegroundColor Green
} else {
  Write-Warning "The server didn't answer yet. Check $ServerDir\logs\server.log"
}

$codeFile = Join-Path $ServerDir "data\tls\security-code.txt"
$publicIp = $null
try { $publicIp = (& curl.exe -s --max-time 5 https://api.ipify.org 2>$null) } catch { }
Write-Host ""
Write-Host "-----------------------------------------------------------------" -ForegroundColor Green
if ($publicIp) { Write-Host "  Server address for the phones:  $publicIp" -ForegroundColor Green }
if (Test-Path $codeFile) { Write-Host "  Security code phones should show:  $((Get-Content $codeFile -Raw).Trim())" -ForegroundColor Green }
Write-Host "-----------------------------------------------------------------" -ForegroundColor Green
$lan = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
  Where-Object { $_.PrefixOrigin -in @("Dhcp", "Manual") -and $_.IPAddress -notlike "169.254.*" } |
  Select-Object -First 1).IPAddress
Write-Host "On your router, forward TCP port $Port to this PC ($lan) so phones can reach it from outside."
Write-Host "At home on Wi-Fi the phones can also use $lan."
