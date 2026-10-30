<#
  Duncan Family Planner - Windows tray app.

  A small icon by the clock that shows whether the home server is running, lets you
  start, stop and restart it, and shows how the family's phones are doing.

  Install it once by double-clicking install-tray.cmd in this folder. It then starts
  by itself whenever you sign in to Windows. Nothing else to install: it uses the
  PowerShell that comes with Windows.

  Icon colour:  green = running, amber = running but worth a look,
                red = not running, grey = checking.
#>
param(
  [string]$TaskName = "Duncan Family Planner",
  [int]$PollSeconds = 10
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

$ServerDir = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$LogDir = Join-Path $ServerDir "logs"
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null

function Write-TrayLog([string]$msg) {
  try { Add-Content -Path (Join-Path $LogDir "tray.log") -Value "$(Get-Date -Format 's') $msg" } catch { }
}

# A mistake inside the tray app should be logged, not shown as a Windows crash box.
[System.Windows.Forms.Application]::SetUnhandledExceptionMode([System.Windows.Forms.UnhandledExceptionMode]::CatchException)
[System.Windows.Forms.Application]::add_ThreadException({ param($sender, $e) Write-TrayLog "UI error: $($e.Exception.Message)" })

# One tray app at a time.
$created = $false
$mutex = New-Object System.Threading.Mutex($true, "Local\DuncanFamilyPlannerTray", [ref]$created)
if (-not $created) { exit }

# ---- Settings from the server's own config ------------------------------------------------
$script:Port = 8787
$script:Scheme = "https"
try {
  $cfgFile = Join-Path $ServerDir "config.json"
  if (Test-Path $cfgFile) {
    $cfg = (Get-Content $cfgFile -Raw).TrimStart([char]0xFEFF) | ConvertFrom-Json
    if ($cfg.port) { $script:Port = [int]$cfg.port }
    if ($null -ne $cfg.https -and -not [bool]$cfg.https) { $script:Scheme = "http" }
  }
} catch { Write-TrayLog "Couldn't read config.json: $_" }

# ---- Small helpers ----------------------------------------------------------------------------
function From-Ms($ms) {
  if (-not $ms) { return $null }
  return [DateTimeOffset]::FromUnixTimeMilliseconds([int64]$ms).LocalDateTime
}

function Format-Span([TimeSpan]$t) {
  if ($t.TotalMinutes -lt 1) { return "under a minute" }
  if ($t.TotalHours -lt 1) { return "{0} min" -f [int]$t.TotalMinutes }
  if ($t.TotalDays -lt 1) { return "{0} h {1} min" -f [int]$t.TotalHours, $t.Minutes }
  return "{0} d {1} h" -f [int]$t.TotalDays, $t.Hours
}

function Format-Ago($dt) {
  if (-not $dt) { return "never" }
  $span = (Get-Date) - $dt
  if ($span.TotalSeconds -lt 90) { return "just now" }
  return "$(Format-Span $span) ago"
}

function Format-Minutes($m) {
  $m = [int]$m
  if ($m -lt 60) { return "$m min" }
  return "{0} h {1:00} min" -f [int][math]::Floor($m / 60), ($m % 60)
}

function Format-Bytes($b) {
  if ($b -ge 1MB) { return "{0:N1} MB" -f ($b / 1MB) }
  return "{0:N0} KB" -f ([math]::Max(1, $b / 1KB))
}

function Describe-Event($e) {
  switch ($e.type) {
    "brick"            { "bricked their phone" }
    "unbrick"          { if ($e.detail) { "unbricked ($($e.detail))" } else { "unbricked" } }
    "emergency"        { "used the EMERGENCY tag" }
    "denied"           { if ($e.detail) { "tried to unlock during $($e.detail)" } else { "tried to unlock" } }
    "tamper"           { "switched their app blocker OFF" }
    "focus"            { "started Family Focus" }
    "focus-ended"      { "ended Family Focus" }
    "pass"             { "gave a pass ($($e.detail))" }
    "pass-cancelled"   { "cancelled a pass" }
    "tag-added"        { "added the Brick `"$($e.detail)`"" }
    "emergency-sealed" { "sealed a new emergency tag" }
    "rearmed"          { "re-armed the emergency tag" }
    "parent-pin-set"   { "set a new parent PIN" }
    "pin-wrong"        { if ($e.detail) { "entered a wrong parent PIN ($($e.detail))" } else { "entered a wrong parent PIN" } }
    "apps-unlocked"    { "unlocked their essential apps with the parent PIN" }
    "apps-managed"     { if ($e.detail) { "had their essential apps set by a parent ($($e.detail))" } else { "had their essential apps set by a parent" } }
    default            { [string]$e.type }
  }
}

# ---- Icons --------------------------------------------------------------------------------------------
function New-StatusIcon([System.Drawing.Color]$color) {
  $bmp = New-Object System.Drawing.Bitmap 32, 32
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
  $g.Clear([System.Drawing.Color]::Transparent)
  $fill = New-Object System.Drawing.SolidBrush $color
  $g.FillEllipse($fill, 1, 1, 30, 30)
  # A little house in white.
  $house = [System.Drawing.Point[]]@(
    (New-Object System.Drawing.Point 16, 7), (New-Object System.Drawing.Point 6, 16),
    (New-Object System.Drawing.Point 9, 16), (New-Object System.Drawing.Point 9, 25),
    (New-Object System.Drawing.Point 23, 25), (New-Object System.Drawing.Point 23, 16),
    (New-Object System.Drawing.Point 26, 16))
  $g.FillPolygon([System.Drawing.Brushes]::White, $house)
  $g.Dispose(); $fill.Dispose()
  $icon = [System.Drawing.Icon]::FromHandle($bmp.GetHicon())
  return $icon
}
$icons = @{
  green = New-StatusIcon ([System.Drawing.Color]::FromArgb(46, 160, 67))
  amber = New-StatusIcon ([System.Drawing.Color]::FromArgb(230, 150, 0))
  red   = New-StatusIcon ([System.Drawing.Color]::FromArgb(205, 49, 49))
  grey  = New-StatusIcon ([System.Drawing.Color]::FromArgb(130, 130, 130))
}

# ---- Asking the server how it is -------------------------------------------------------------------
# The server's own certificate isn't one Windows trusts, so the check uses curl.exe (built into
# Windows 10 and 11) with -k, and only ever talks to this PC. It runs in the background so the
# icon never freezes.
$script:StatusFile = Join-Path $env:TEMP "duncan-tray-status.json"
$script:Poll = $null
$script:LastPoll = [DateTime]::MinValue
$script:Status = $null      # last good answer from the server
$script:Up = $null          # $true / $false / $null (not known yet)
$script:Busy = $false       # a start/stop is in progress
$script:LastNotifiedPause = $false

function Start-Poll {
  $script:LastPoll = Get-Date
  Remove-Item $script:StatusFile -ErrorAction SilentlyContinue
  $psi = New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName = "curl.exe"
  $psi.Arguments = "-sk --max-time 4 -o `"$($script:StatusFile)`" $($script:Scheme)://localhost:$($script:Port)/api/admin/status"
  $psi.UseShellExecute = $false
  $psi.CreateNoWindow = $true
  try { $script:Poll = [System.Diagnostics.Process]::Start($psi) } catch { $script:Poll = $null; Set-State $null $false }
}

function Read-Poll {
  $data = $null
  try {
    if (Test-Path $script:StatusFile) {
      $text = (Get-Content $script:StatusFile -Raw -ErrorAction Stop)
      if ($text) { $data = $text | ConvertFrom-Json }
    }
  } catch { }
  if ($data -and $data.ok) { Set-State $data $true } else { Set-State $null $false }
}

function Set-State($status, [bool]$up) {
  $was = $script:Up
  $script:Status = $status
  $script:Up = $up
  if (-not $script:Busy) {
    if ($was -eq $true -and -not $up) { Show-Balloon "Server stopped" "The Duncan Family Planner server isn't answering." "Warning" }
    if ($was -eq $false -and $up) { Show-Balloon "Server is back" "The Duncan Family Planner server is running again." "Info" }
  }
  if ($status) {
    $paused = [bool]$status.signIn.pausedUntilMs
    if ($paused -and -not $script:LastNotifiedPause) {
      Show-Balloon "Sign-ins paused" "Too many wrong PINs were entered. Sign-in is paused for a few minutes." "Warning"
    }
    $script:LastNotifiedPause = $paused
  }
  Refresh-Ui
}

function Show-Balloon([string]$title, [string]$text, [string]$kind) {
  try {
    $notify.BalloonTipTitle = $title
    $notify.BalloonTipText = $text
    $notify.BalloonTipIcon = $kind
    $notify.ShowBalloonTip(5000)
  } catch { }
}

# ---- Starting and stopping -----------------------------------------------------------------------------
# The server runs as a Windows startup task with full rights, so changing it needs an
# administrator. Windows asks "Do you want to allow this app to make changes?" each time.
function Get-TaskState {
  try {
    $t = Get-ScheduledTask -TaskName $TaskName -ErrorAction Stop
    return [string]$t.State
  } catch { return "Missing" }
}

function Invoke-Elevated([string]$command) {
  try {
    Start-Process -FilePath "powershell.exe" -Verb RunAs -WindowStyle Hidden -Wait `
      -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", "`"$command`"")
    return $true
  } catch {
    Write-TrayLog "Elevation cancelled or failed: $_"
    return $false
  }
}

function Control-Server([string]$action) {
  if ((Get-TaskState) -eq "Missing") {
    [System.Windows.Forms.MessageBox]::Show(
      "The server's startup task isn't installed on this PC yet.`n`nRun server\windows\first-time-setup.cmd first.",
      "Duncan Family Planner", "OK", "Information") | Out-Null
    return
  }
  $name = $TaskName.Replace("'", "''")
  $cmd = switch ($action) {
    "start"   { "Start-ScheduledTask -TaskName '$name'" }
    "stop"    { "Stop-ScheduledTask -TaskName '$name'" }
    "restart" { "Stop-ScheduledTask -TaskName '$name'; Start-Sleep -Seconds 3; Start-ScheduledTask -TaskName '$name'" }
  }
  $script:Busy = $true
  $notify.Icon = $icons.grey
  $notify.Text = "Duncan Planner: working..."
  $ok = Invoke-Elevated $cmd
  if ($ok) { Start-Sleep -Seconds 4 }
  $script:Busy = $false
  $script:Status = $null      # shows "checking" until the next answer arrives
  $script:Up = $null
  Refresh-Ui
  Start-Poll
  if (-not $ok) { Show-Balloon "Nothing changed" "Windows didn't get the go-ahead to $action the server." "Info" }
}

function Open-Path([string]$path) {
  if (Test-Path $path) { Start-Process $path } else {
    [System.Windows.Forms.MessageBox]::Show("Couldn't find $path", "Duncan Family Planner", "OK", "Information") | Out-Null
  }
}

# ---- Status window ----------------------------------------------------------------------------------------
$script:Form = $null
function New-StatusForm {
  $f = New-Object System.Windows.Forms.Form
  $f.Text = "Duncan Family Planner server"
  $f.StartPosition = "CenterScreen"
  $f.Size = New-Object System.Drawing.Size 880, 640
  $f.MinimumSize = New-Object System.Drawing.Size 760, 520
  $f.Font = New-Object System.Drawing.Font("Segoe UI", 9.5)
  $f.ShowInTaskbar = $true

  $events = New-Object System.Windows.Forms.ListView
  $events.View = "Details"; $events.FullRowSelect = $true; $events.GridLines = $true; $events.Dock = "Fill"
  [void]$events.Columns.Add("When", 150); [void]$events.Columns.Add("Who", 100); [void]$events.Columns.Add("What", 520)

  $buttons = New-Object System.Windows.Forms.FlowLayoutPanel
  $buttons.Dock = "Bottom"; $buttons.Height = 46; $buttons.Padding = New-Object System.Windows.Forms.Padding 8
  foreach ($b in @(
      @{ t = "Start";       a = { Control-Server "start" } },
      @{ t = "Stop";        a = { Control-Server "stop" } },
      @{ t = "Restart";     a = { Control-Server "restart" } },
      @{ t = "Family Focus..."; a = { Show-FocusDialog } },
      @{ t = "Phone apps...";   a = { Show-AppsDialog } },
      @{ t = "Open log";    a = { Open-Path (Join-Path $LogDir "server.log") } },
      @{ t = "Backups";     a = { Open-Path (Join-Path $ServerDir "data\backups") } },
      @{ t = "Copy security code"; a = { Copy-SecurityCode } })) {
    $btn = New-Object System.Windows.Forms.Button
    $btn.Text = $b.t; $btn.AutoSize = $true; $btn.Padding = New-Object System.Windows.Forms.Padding 6, 2, 6, 2
    $btn.Add_Click($b.a)
    $buttons.Controls.Add($btn)
  }

  $people = New-Object System.Windows.Forms.ListView
  $people.View = "Details"; $people.FullRowSelect = $true; $people.GridLines = $true
  $people.Dock = "Top"; $people.Height = 150
  foreach ($c in @(@("Person", 90), @("Phone", 130), @("App blocker", 90), @("Uninstall guard", 105), @("Phone-free today", 115), @("This week", 85), @("Streak", 60), @("Last check-in", 120))) {
    [void]$people.Columns.Add($c[0], $c[1])
  }

  $summary = New-Object System.Windows.Forms.Label
  $summary.Dock = "Top"; $summary.Height = 170; $summary.Padding = New-Object System.Windows.Forms.Padding 10
  $summary.Font = New-Object System.Drawing.Font("Segoe UI", 10)

  $evLabel = New-Object System.Windows.Forms.Label
  $evLabel.Text = "Recent activity"; $evLabel.Dock = "Top"; $evLabel.Height = 26
  $evLabel.Padding = New-Object System.Windows.Forms.Padding 8, 6, 0, 0
  $evLabel.Font = New-Object System.Drawing.Font("Segoe UI", 9.5, [System.Drawing.FontStyle]::Bold)

  # Docking: the last control added is placed first, so add the fill area, then bottom, then the tops.
  $f.Controls.Add($events)
  $f.Controls.Add($evLabel)
  $f.Controls.Add($buttons)
  $f.Controls.Add($people)
  $f.Controls.Add($summary)

  # Closing the window just hides it; the tray icon keeps running.
  $f.Add_FormClosing({ param($s, $e) if (-not $script:Exiting) { $e.Cancel = $true; $s.Hide() } })
  $f.Tag = @{ Summary = $summary; People = $people; Events = $events }
  return $f
}

function Copy-SecurityCode {
  $file = Join-Path $ServerDir "data\tls\security-code.txt"
  if (Test-Path $file) {
    $code = (Get-Content $file -Raw).Trim()
    [System.Windows.Forms.Clipboard]::SetText($code)
    Show-Balloon "Security code copied" $code "Info"
  } else {
    Show-Balloon "No security code yet" "The server makes it the first time it starts." "Info"
  }
}

function Show-Status {
  if (-not $script:Form) { $script:Form = New-StatusForm }
  Refresh-Ui
  $script:Form.Show()
  $script:Form.WindowState = "Normal"
  $script:Form.Activate()
}

# ---- Keeping everything up to date ----------------------------------------------------------------------
function Get-Colour {
  if ($null -eq $script:Up) { return "grey" }
  if (-not $script:Up) { return "red" }
  $s = $script:Status
  if ($s) {
    if ($s.signIn.pausedUntilMs) { return "amber" }
    foreach ($p in $s.people) { if ($p.phone -and -not $p.phone.serviceOn) { return "amber" } }
  }
  return "green"
}

function Refresh-Ui {
  $colour = Get-Colour
  $s = $script:Status
  if ($script:Busy) { return }
  $notify.Icon = $icons[$colour]

  if ($null -eq $script:Up) { $head = "Checking the server..." }
  elseif (-not $script:Up) { $head = "Server is NOT running" }
  elseif ($s) { $head = "Server running - up $(Format-Span ([TimeSpan]::FromSeconds([double]$s.uptimeSeconds)))" }
  else { $head = "Server running" }
  $menuHeader.Text = $head
  $tip = "Duncan Planner: " + $(if ($null -eq $script:Up) { "checking" } elseif (-not $script:Up) { "not running" } elseif ($colour -eq "amber") { "running - needs a look" } else { "running" })
  $notify.Text = $tip.Substring(0, [math]::Min(63, $tip.Length))

  $menuStart.Enabled = ($script:Up -ne $true)
  $menuStop.Enabled = ($script:Up -eq $true)
  $menuRestart.Enabled = ($script:Up -eq $true)
  $menuFocus.Enabled = ($script:Up -eq $true)
  $menuApps.Enabled = ($script:Up -eq $true)
  $menuEndFocus.Enabled = [bool]($s -and $s.focus -and $s.focus.active)

  if (-not $script:Form -or -not $script:Form.Visible) { return }
  $ui = $script:Form.Tag
  $lines = @($head, "")
  if ($s) {
    $started = From-Ms $s.startedMs
    $lines += "Address:  $($script:Scheme)://localhost:$($s.port)   (phones use your home IP address and this port)"
    $lines += "Started:  $($started.ToString('ddd d MMM, HH:mm'))      Node.js $($s.node)"
    $backupText = if ($s.database.latestBackup) { "$($s.database.backups) kept, latest $((From-Ms $s.database.latestBackup.atMs).ToString('ddd d MMM, HH:mm'))" } else { "none yet" }
    $lines += "Database:  $(Format-Bytes $s.database.bytes)      Backups: $backupText"
    if ($s.signIn.pausedUntilMs) {
      $lines += "Sign-in:  PAUSED until $((From-Ms $s.signIn.pausedUntilMs).ToString('HH:mm')) after too many wrong PINs"
    } else {
      $lines += "Sign-in:  normal ($($s.signIn.recentFailures) wrong PIN(s) in the last 15 minutes)"
    }
    $lines += "Parent PIN:  $(if ($s.parentPinSet) { 'set' } else { 'not set yet' })"
    if ($s.focus -and $s.focus.active) {
      $who = @($s.people | Where-Object { $s.focus.personIds -contains $_.id } | ForEach-Object { $_.name }) -join ", "
      $until = if ($s.focus.untilMs) { "until $((From-Ms $s.focus.untilMs).ToString('HH:mm'))" } else { "until you end it" }
      $lines += "Family Focus:  ON $until ($who)"
    } else {
      $lines += "Family Focus:  off"
    }
  } elseif ($script:Up -eq $false) {
    $lines += "The server isn't answering on port $($script:Port)."
    $lines += "Task state: $(Get-TaskState)"
    $lines += "Use Start above, or open the log to see why it stopped."
  }
  $ui.Summary.Text = ($lines -join "`r`n")

  $ui.People.BeginUpdate(); $ui.People.Items.Clear()
  $ui.Events.BeginUpdate(); $ui.Events.Items.Clear()
  if ($s) {
    foreach ($p in $s.people) {
      if ($p.phone) {
        $phone = if ($p.phone.locked) { "Bricked ($($p.phone.reason))" } else { "Free" }
        $blocker = if ($p.phone.serviceOn) { "On" } else { "OFF" }
        $guard = if ($p.phone.adminOn) { "On" } else { "OFF" }
        $checkin = Format-Ago (From-Ms $p.phone.lastCheckInMs)
      } else { $phone = "No check-in yet"; $blocker = "-"; $guard = "-"; $checkin = "never" }
      $row = New-Object System.Windows.Forms.ListViewItem $p.name
      foreach ($cell in @($phone, $blocker, $guard, (Format-Minutes $p.phoneFreeTodayMinutes), (Format-Minutes $p.phoneFreeWeekMinutes), "$($p.streakDays) d", $checkin)) {
        [void]$row.SubItems.Add([string]$cell)
      }
      if ($p.phone -and (-not $p.phone.serviceOn -or -not $p.phone.adminOn)) { $row.ForeColor = [System.Drawing.Color]::FromArgb(180, 40, 40) }
      [void]$ui.People.Items.Add($row)
    }
    foreach ($e in $s.events) {
      $when = (From-Ms $e.atMs)
      $item = New-Object System.Windows.Forms.ListViewItem $(if ($when) { $when.ToString("ddd d MMM, HH:mm") } else { "" })
      [void]$item.SubItems.Add([string]$e.person)
      [void]$item.SubItems.Add((Describe-Event $e))
      if ($e.type -in @("emergency", "tamper", "pin-wrong")) { $item.ForeColor = [System.Drawing.Color]::FromArgb(180, 40, 40) }
      [void]$ui.Events.Items.Add($item)
    }
  }
  $ui.People.EndUpdate(); $ui.Events.EndUpdate()
}

# ---- Controls: Family Focus and each phone's essential apps -----------------------------------------------
# These go to the server's own admin address, which only answers programs on this PC.
function Invoke-Admin([string]$method, [string]$path, $body = $null) {
  $reply = Join-Path $env:TEMP "duncan-tray-reply.json"
  $sent = Join-Path $env:TEMP "duncan-tray-request.json"
  Remove-Item $reply, $sent -ErrorAction SilentlyContinue
  $curlArgs = @("-sk", "--max-time", "10", "-X", $method, "-H", "X-Dfp-Admin: tray", "-o", $reply, "-w", "%{http_code}")
  if ($null -ne $body) {
    [IO.File]::WriteAllText($sent, (ConvertTo-Json -InputObject $body -Depth 6 -Compress), (New-Object System.Text.UTF8Encoding $false))
    $curlArgs += @("-H", "Content-Type: application/json", "--data-binary", "@$sent")
  }
  $curlArgs += "$($script:Scheme)://localhost:$($script:Port)$path"
  $code = ((& curl.exe @curlArgs) | Out-String).Trim()
  $data = $null
  if (Test-Path $reply) { try { $data = (Get-Content $reply -Raw) | ConvertFrom-Json } catch { } }
  Remove-Item $sent -ErrorAction SilentlyContinue
  if ($code -ne "200") {
    if ($data -and $data.error) { throw [string]$data.error }
    if (-not $code -or $code -eq "000") { throw "The server isn't answering." }
    throw "The server said $code. Update and restart the server so it has the new controls."
  }
  return $data
}

function Show-Problem([string]$text) {
  [System.Windows.Forms.MessageBox]::Show($text, "Duncan Family Planner", "OK", "Warning") | Out-Null
}

function New-DialogForm([string]$title, [int]$w, [int]$h) {
  $f = New-Object System.Windows.Forms.Form
  $f.Text = $title
  $f.StartPosition = "CenterScreen"
  $f.ClientSize = New-Object System.Drawing.Size $w, $h
  $f.Font = New-Object System.Drawing.Font("Segoe UI", 9.5)
  $f.MaximizeBox = $false
  $f.MinimizeBox = $false
  $f.FormBorderStyle = "FixedDialog"
  return $f
}

function New-Label([string]$text, [int]$x, [int]$y, [int]$w, [int]$h) {
  $l = New-Object System.Windows.Forms.Label
  $l.Text = $text
  $l.Location = New-Object System.Drawing.Point $x, $y
  $l.Size = New-Object System.Drawing.Size $w, $h
  return $l
}

# -- Family Focus --
$script:FocusUi = $null

function Show-FocusDialog {
  if (-not $script:Status) { Show-Problem "The server isn't answering, so Family Focus can't be started."; return }
  $people = @($script:Status.people)
  $f = New-DialogForm "Start Family Focus" 380 350
  $f.Controls.Add((New-Label "Who should put their phone down?" 14 12 350 22))

  $list = New-Object System.Windows.Forms.CheckedListBox
  $list.Location = New-Object System.Drawing.Point 14, 38
  $list.Size = New-Object System.Drawing.Size 350, 150
  $list.CheckOnClick = $true
  foreach ($p in $people) { [void]$list.Items.Add([string]$p.name, [bool](-not $p.parent)) }
  $f.Controls.Add($list)

  $f.Controls.Add((New-Label "For how long?" 14 204 350 22))
  $combo = New-Object System.Windows.Forms.ComboBox
  $combo.DropDownStyle = "DropDownList"
  $combo.Location = New-Object System.Drawing.Point 14, 230
  $combo.Size = New-Object System.Drawing.Size 220, 28
  foreach ($d in @("30 minutes", "1 hour", "2 hours", "3 hours", "Until I end it")) { [void]$combo.Items.Add($d) }
  $combo.SelectedIndex = 1
  $f.Controls.Add($combo)

  $note = New-Label "Their phones lock within about 30 seconds. A parent's tag or the emergency tag still works." 14 266 350 40
  $note.ForeColor = [System.Drawing.Color]::DimGray
  $f.Controls.Add($note)

  $start = New-Object System.Windows.Forms.Button
  $start.Text = "Start Family Focus"
  $start.Location = New-Object System.Drawing.Point 14, 312
  $start.Size = New-Object System.Drawing.Size 160, 28
  $start.Add_Click({ Submit-Focus })
  $f.Controls.Add($start)
  $cancel = New-Object System.Windows.Forms.Button
  $cancel.Text = "Cancel"
  $cancel.Location = New-Object System.Drawing.Point 184, 312
  $cancel.Size = New-Object System.Drawing.Size 90, 28
  $cancel.DialogResult = [System.Windows.Forms.DialogResult]::Cancel
  $f.Controls.Add($cancel)
  $f.CancelButton = $cancel

  $script:FocusUi = @{ Form = $f; List = $list; Combo = $combo; People = $people }
  [void]$f.ShowDialog()
  $f.Dispose()
  $script:FocusUi = $null
}

function Submit-Focus {
  $ui = $script:FocusUi
  $ids = @()
  foreach ($i in $ui.List.CheckedIndices) { $ids += [string]$ui.People[$i].id }
  if ($ids.Count -eq 0) { Show-Problem "Tick at least one person."; return }
  $minutes = switch ([string]$ui.Combo.SelectedItem) {
    "30 minutes" { 30 } "1 hour" { 60 } "2 hours" { 120 } "3 hours" { 180 } default { $null }
  }
  try {
    [void](Invoke-Admin "POST" "/api/admin/focus" @{ minutes = $minutes; personIds = $ids })
  } catch {
    Show-Problem "Family Focus didn't start.`n`n$($_.Exception.Message)"
    return
  }
  $ui.Form.DialogResult = [System.Windows.Forms.DialogResult]::OK
  $ui.Form.Close()
  Show-Balloon "Family Focus started" "Phones lock within about 30 seconds." "Info"
  $script:LastPoll = [DateTime]::MinValue
}

function End-Focus {
  try {
    [void](Invoke-Admin "DELETE" "/api/admin/focus")
    Show-Balloon "Family Focus ended" "Phones unlock within about 30 seconds." "Info"
    $script:LastPoll = [DateTime]::MinValue
  } catch {
    Show-Problem "Family Focus didn't end.`n`n$($_.Exception.Message)"
  }
}

# -- Each phone's essential apps --
$script:AppsUi = $null

function Show-AppsDialog {
  try { $data = Invoke-Admin "GET" "/api/admin/apps" } catch { Show-Problem "Couldn't get the phones' apps.`n`n$($_.Exception.Message)"; return }
  $people = @($data.people)
  if ($people.Count -eq 0) { Show-Problem "No family members are set up yet."; return }

  $f = New-DialogForm "Phone apps" 560 640
  $f.MaximizeBox = $false
  $f.Controls.Add((New-Label "Choose which apps stay open on a phone while it is bricked. Phone, messages and the planner always work." 14 10 530 40))

  $f.Controls.Add((New-Label "Whose phone?" 14 56 100 22))
  $combo = New-Object System.Windows.Forms.ComboBox
  $combo.DropDownStyle = "DropDownList"
  $combo.Location = New-Object System.Drawing.Point 120, 52
  $combo.Size = New-Object System.Drawing.Size 200, 28
  foreach ($p in $people) { [void]$combo.Items.Add([string]$p.name) }
  $f.Controls.Add($combo)

  $manage = New-Object System.Windows.Forms.CheckBox
  $manage.Text = "Choose these from this PC"
  $manage.Location = New-Object System.Drawing.Point 14, 90
  $manage.Size = New-Object System.Drawing.Size 300, 24
  $f.Controls.Add($manage)

  $info = New-Label "" 14 118 530 38
  $info.ForeColor = [System.Drawing.Color]::DimGray
  $f.Controls.Add($info)

  $check = New-Object System.Windows.Forms.CheckedListBox
  $check.Location = New-Object System.Drawing.Point 14, 160
  $check.Size = New-Object System.Drawing.Size 530, 410
  $check.CheckOnClick = $true
  $f.Controls.Add($check)

  $save = New-Object System.Windows.Forms.Button
  $save.Text = "Save"
  $save.Location = New-Object System.Drawing.Point 14, 592
  $save.Size = New-Object System.Drawing.Size 100, 30
  $save.Add_Click({ Save-Apps })
  $f.Controls.Add($save)
  $close = New-Object System.Windows.Forms.Button
  $close.Text = "Close"
  $close.Location = New-Object System.Drawing.Point 124, 592
  $close.Size = New-Object System.Drawing.Size 100, 30
  $close.DialogResult = [System.Windows.Forms.DialogResult]::Cancel
  $f.Controls.Add($close)
  $f.CancelButton = $close

  $script:AppsUi = @{ Form = $f; Person = $combo; Manage = $manage; Info = $info; Check = $check; People = $people; Pkgs = @() }
  $combo.Add_SelectedIndexChanged({ Load-AppsPerson })
  $combo.SelectedIndex = 0
  [void]$f.ShowDialog()
  $f.Dispose()
  $script:AppsUi = $null
}

function Load-AppsPerson {
  $ui = $script:AppsUi
  if (-not $ui -or $ui.Person.SelectedIndex -lt 0) { return }
  $p = $ui.People[$ui.Person.SelectedIndex]
  $installed = @($p.installed)
  $allowed = @($p.allowed)
  $ui.Check.Items.Clear()
  $pkgs = @()
  foreach ($a in $installed) {
    [void]$ui.Check.Items.Add("$($a.label)   ($($a.pkg))", [bool]($p.managed -and ($allowed -contains $a.pkg)))
    $pkgs += [string]$a.pkg
  }
  # Apps that are allowed but no longer reported by the phone stay on the list until someone unticks them.
  foreach ($pkg in $allowed) {
    if ($pkgs -notcontains $pkg) {
      [void]$ui.Check.Items.Add("(not on the phone)   ($pkg)", $true)
      $pkgs += [string]$pkg
    }
  }
  $ui.Pkgs = $pkgs
  $ui.Manage.Checked = [bool]$p.managed
  if ($installed.Count -eq 0) {
    $ui.Info.Text = "$($p.name)'s phone hasn't listed its apps yet. It does that when the app blocker is on and the phone has the latest version of the app and can reach this server."
  } elseif ($p.managed) {
    $ui.Info.Text = "These are set from this PC. $($p.name) can't change them on the phone."
  } else {
    $ui.Info.Text = "$($p.name) chooses on their own phone. Tick the box above to take over and choose from this list."
  }
}

function Save-Apps {
  $ui = $script:AppsUi
  $p = $ui.People[$ui.Person.SelectedIndex]
  $allowed = $null
  if ($ui.Manage.Checked) {
    $allowed = @()
    foreach ($i in $ui.Check.CheckedIndices) { $allowed += [string]$ui.Pkgs[$i] }
  }
  try {
    [void](Invoke-Admin "PUT" "/api/admin/apps/$($p.id)" @{ allowed = $allowed })
    $fresh = Invoke-Admin "GET" "/api/admin/apps"
  } catch {
    Show-Problem "Couldn't save.`n`n$($_.Exception.Message)"
    return
  }
  $keep = $ui.Person.SelectedIndex
  $ui.People = @($fresh.people)
  Load-AppsPerson
  $ui.Person.SelectedIndex = $keep
  $msg = if ($ui.Manage.Checked) { "$($p.name)'s phone picks this up within about 30 seconds." } else { "$($p.name) chooses their own apps again." }
  Show-Balloon "Saved" $msg "Info"
}

# ---- The tray icon and its menu ----------------------------------------------------------------------------
$notify = New-Object System.Windows.Forms.NotifyIcon
$notify.Icon = $icons.grey
$notify.Text = "Duncan Planner: checking"
$notify.Visible = $true

$menu = New-Object System.Windows.Forms.ContextMenuStrip
$menuHeader = $menu.Items.Add("Checking the server...")
$menuHeader.Enabled = $false
[void]$menu.Items.Add("-")
$menuShow = $menu.Items.Add("Show status...")
$menuShow.Font = New-Object System.Drawing.Font($menuShow.Font, [System.Drawing.FontStyle]::Bold)
$menuFocus = $menu.Items.Add("Start Family Focus...")
$menuEndFocus = $menu.Items.Add("End Family Focus")
$menuApps = $menu.Items.Add("Phone apps...")
[void]$menu.Items.Add("-")
$menuStart = $menu.Items.Add("Start server")
$menuStop = $menu.Items.Add("Stop server")
$menuRestart = $menu.Items.Add("Restart server")
[void]$menu.Items.Add("-")
$menuLog = $menu.Items.Add("Open server log")
$menuFolder = $menu.Items.Add("Open server folder")
$menuCode = $menu.Items.Add("Copy security code")
[void]$menu.Items.Add("-")
$menuExit = $menu.Items.Add("Exit tray app (server keeps running)")

$menuShow.Add_Click({ Show-Status })
$menuFocus.Add_Click({ Show-FocusDialog })
$menuEndFocus.Add_Click({ End-Focus })
$menuApps.Add_Click({ Show-AppsDialog })
$menuStart.Add_Click({ Control-Server "start" })
$menuStop.Add_Click({ Control-Server "stop" })
$menuRestart.Add_Click({ Control-Server "restart" })
$menuLog.Add_Click({ Open-Path (Join-Path $LogDir "server.log") })
$menuFolder.Add_Click({ Open-Path $ServerDir })
$menuCode.Add_Click({ Copy-SecurityCode })
$script:Exiting = $false
$menuExit.Add_Click({
  $script:Exiting = $true
  $timer.Stop()
  $notify.Visible = $false
  $notify.Dispose()
  if ($script:Form) { $script:Form.Dispose() }
  [System.Windows.Forms.Application]::Exit()
})
$notify.ContextMenuStrip = $menu
$notify.Add_DoubleClick({ Show-Status })

# One timer drives everything: start a check every few seconds, collect the answer when curl finishes.
$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 1000
$timer.Add_Tick({
  try {
    if ($script:Busy) { return }
    if ($script:Poll) {
      if ($script:Poll.HasExited) { $script:Poll = $null; Read-Poll }
    } elseif (((Get-Date) - $script:LastPoll).TotalSeconds -ge $PollSeconds) {
      Start-Poll
    }
  } catch { Write-TrayLog "Tick failed: $_" }
})
$timer.Start()
Start-Poll

Write-TrayLog "Tray app started (port $($script:Port), $($script:Scheme))"
try {
  [System.Windows.Forms.Application]::Run()
} catch {
  Write-TrayLog "Tray app crashed: $_"
  throw
} finally {
  $mutex.ReleaseMutex()
}
