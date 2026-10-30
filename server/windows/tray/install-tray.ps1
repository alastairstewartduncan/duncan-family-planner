<#
  Duncan Family Planner - install (or remove) the tray app.

  Easiest: double-click install-tray.cmd in this folder. No administrator needed.

  What it does:
    1. Registers a Windows task that starts the tray app each time YOU sign in.
    2. Starts it now. Look for the little house icon by the clock (you may need to
       click the ^ arrow and drag it onto the taskbar so it stays visible).

  To remove it:  powershell -ExecutionPolicy Bypass -File install-tray.ps1 -Remove
  The server itself is not touched either way.
#>
param(
  [switch]$Remove,
  [string]$TaskName = "Duncan Family Planner Tray"
)

$ErrorActionPreference = "Stop"
$script = Join-Path $PSScriptRoot "DuncanTray.ps1"
$me = "$env:USERDOMAIN\$env:USERNAME"

# Stop any copy that is already running (the tray app keeps no data of its own).
function Stop-Tray {
  Get-CimInstance Win32_Process -Filter "Name = 'powershell.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like "*DuncanTray.ps1*" -and $_.ProcessId -ne $PID } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

if ($Remove) {
  if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
    Write-Host "Removed the '$TaskName' task."
  }
  Stop-Tray
  Write-Host "Tray app removed. The server is still running as before."
  return
}

if (-not (Test-Path $script)) { throw "Can't find DuncanTray.ps1 next to this script." }
Unblock-File -Path $script -ErrorAction SilentlyContinue
Stop-Tray

$arg = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$script`""
$action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument $arg -WorkingDirectory $PSScriptRoot
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $me
$principal = New-ScheduledTaskPrincipal -UserId $me -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable `
  -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null
Start-ScheduledTask -TaskName $TaskName

Write-Host ""
Write-Host "Tray app installed. It will start whenever you sign in to Windows." -ForegroundColor Green
Write-Host "Look for the little house icon by the clock. If you can't see it, click the ^ arrow and drag it onto the taskbar."
