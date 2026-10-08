<#
  Removes the Duncan Family Planner startup task and firewall rule.
  Your data (server\data, including backups) is left untouched.
  Run from an Administrator PowerShell window.
#>
param([string]$TaskName = "Duncan Family Planner")
$ErrorActionPreference = "Stop"
if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
  Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
  Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
  Write-Host "Removed scheduled task '$TaskName'."
}
Get-NetFirewallRule -DisplayName "$TaskName*" -ErrorAction SilentlyContinue | Remove-NetFirewallRule
Write-Host "Removed firewall rule. Data in server\data has been kept."
