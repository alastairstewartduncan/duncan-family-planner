<#
  Duncan Family Planner - make the permanent signing key, once.

  Why: Android only installs an update over an app if the new APK is signed with
  the SAME key as the one on the phone. Until now every GitHub build was signed
  with a throwaway key, so each release meant uninstalling the app first. After
  this runs, every build uses your permanent key and installs over the top: your
  sign-in, settings, Brick permissions and uninstall protection all stay.

  Easiest: double-click create-signing-key.cmd in this folder.

  What it does:
    1. Finds keytool (it comes with Android Studio and Java).
    2. Makes the key in  %USERPROFILE%\DuncanPlannerSigningKey  with a random password.
    3. Saves the four GitHub secrets the build needs. If the GitHub command line (gh)
       is installed and signed in it does this for you; otherwise it writes the
       values to a file and opens the GitHub page so you can paste them.

  KEEP THAT FOLDER. If the key is lost, the next build will need one more uninstall
  on every phone. Back it up somewhere private (not in the repo, not in a public place).
  Anyone who has the key could make an APK that installs over the family's app, so
  never put it on GitHub, in the repo, or in a chat.

  If the key already exists this will not replace it. Use -Resend to save the
  GitHub secrets again from the existing key.
#>
param(
  [string]$Repo = "alastairstewartduncan/duncan-family-planner",
  [string]$Folder = (Join-Path $env:USERPROFILE "DuncanPlannerSigningKey"),
  [switch]$Resend
)

$ErrorActionPreference = "Stop"
$alias = "planner"
$jks = Join-Path $Folder "duncan-planner.jks"
$info = Join-Path $Folder "signing-key-info.txt"

function Step($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }

function Find-Keytool {
  $found = @()
  if ($env:JAVA_HOME) { $found += (Join-Path $env:JAVA_HOME "bin\keytool.exe") }
  $cmd = Get-Command keytool.exe -ErrorAction SilentlyContinue
  if ($cmd) { $found += $cmd.Source }
  $found += "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
  $found += "C:\Program Files\Android\Android Studio\jre\bin\keytool.exe"
  foreach ($root in @("C:\Program Files\Eclipse Adoptium", "C:\Program Files\Java", "C:\Program Files\Microsoft", "C:\Program Files\Zulu")) {
    if (Test-Path $root) {
      $found += @(Get-ChildItem -Path $root -Filter keytool.exe -Recurse -Depth 3 -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
    }
  }
  foreach ($c in $found) { if ($c -and (Test-Path $c)) { return $c } }
  throw "Couldn't find keytool. Install Android Studio or Java (JDK 17), then run this again."
}

# Letters and digits only, leaving out look-alikes, so it is easy to read back if ever needed.
function New-Password([int]$length = 24) {
  $alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
  $rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
  $chars = New-Object System.Text.StringBuilder
  $buf = New-Object byte[] 1
  while ($chars.Length -lt $length) {
    $rng.GetBytes($buf)
    if ($buf[0] -lt 224) { [void]$chars.Append($alphabet[$buf[0] % $alphabet.Length]) }  # 224 = 4 x 56, so no bias
  }
  return $chars.ToString()
}

function Read-Info {
  $values = @{}
  foreach ($line in (Get-Content $info)) {
    if ($line -match "^(SIGNING_[A-Z_]+)=(.*)$") { $values[$Matches[1]] = $Matches[2] }
  }
  return $values
}

# ---- Make the key (or reuse it) --------------------------------------------------------------
if (Test-Path $jks) {
  if (-not $Resend) {
    Write-Host ""
    Write-Host "A signing key already exists at:" -ForegroundColor Yellow
    Write-Host "  $jks"
    Write-Host "Not replacing it: a new key would mean uninstalling the app on every phone again."
    Write-Host "To save the GitHub secrets again from this key, run this with -Resend."
    return
  }
  if (-not (Test-Path $info)) { throw "Found the key but not $info, so I don't know its password." }
  Step "Using the existing key"
} else {
  if ($Resend) { throw "There is no key at $jks yet. Run this without -Resend first." }
  Step "Finding keytool"
  $keytool = Find-Keytool
  Write-Host $keytool

  Step "Making the signing key"
  New-Item -ItemType Directory -Force -Path $Folder | Out-Null
  $password = New-Password 24
  # A modern keystore uses one password for the store and the key.
  & $keytool -genkeypair -keystore $jks -storetype PKCS12 -storepass $password -keypass $password `
    -alias $alias -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Duncan Family Planner" 2>&1 | Out-Null
  if ($LASTEXITCODE -ne 0 -or -not (Test-Path $jks)) { throw "keytool couldn't make the key." }

  $b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($jks))
  $lines = @(
    "Duncan Family Planner signing key - KEEP PRIVATE, KEEP A BACKUP",
    "Made $(Get-Date -Format 'yyyy-MM-dd HH:mm') for $Repo. Never put this in the repo or share it.",
    "SIGNING_KEYSTORE_BASE64=$b64",
    "SIGNING_STORE_PASSWORD=$password",
    "SIGNING_KEY_ALIAS=$alias",
    "SIGNING_KEY_PASSWORD=$password"
  )
  [IO.File]::WriteAllLines($info, $lines, (New-Object System.Text.UTF8Encoding $false))
  # Only you should be able to read the folder's files.
  try { & icacls $Folder /inheritance:r /grant:r "$($env:USERNAME):(OI)(CI)F" 2>&1 | Out-Null } catch { }
  Write-Host "Key saved in $Folder"
}

# ---- Give the secrets to GitHub -----------------------------------------------------------------
Step "Saving the four secrets in GitHub"
$values = Read-Info
foreach ($k in @("SIGNING_KEYSTORE_BASE64", "SIGNING_STORE_PASSWORD", "SIGNING_KEY_ALIAS", "SIGNING_KEY_PASSWORD")) {
  if (-not $values[$k]) { throw "$info is missing $k." }
}

$gh = Get-Command gh -ErrorAction SilentlyContinue
$signedIn = $false
if ($gh) {
  & gh auth status 2>&1 | Out-Null
  $signedIn = ($LASTEXITCODE -eq 0)
}

if ($signedIn) {
  foreach ($k in @("SIGNING_KEYSTORE_BASE64", "SIGNING_STORE_PASSWORD", "SIGNING_KEY_ALIAS", "SIGNING_KEY_PASSWORD")) {
    & gh secret set $k --repo $Repo --body $values[$k] 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "GitHub wouldn't save $k. Make sure you are signed in to gh with access to $Repo." }
    Write-Host "  saved $k"
  }
  Write-Host "`nDone. GitHub now has your key." -ForegroundColor Green
} else {
  Write-Host "The GitHub command line (gh) isn't installed or signed in, so do this part by hand:" -ForegroundColor Yellow
  Write-Host "  1. A browser page is opening: $Repo > Settings > Secrets and variables > Actions."
  Write-Host "  2. Click 'New repository secret' four times. Names and values are in this file:"
  Write-Host "       $info"
  Write-Host "     (SIGNING_KEYSTORE_BASE64 is one very long line; copy the whole line after the = sign.)"
  Start-Process "https://github.com/$Repo/settings/secrets/actions"
  Start-Process "notepad.exe" $info
}

Write-Host ""
Write-Host "-----------------------------------------------------------------" -ForegroundColor Green
Write-Host "  NEXT" -ForegroundColor Green
Write-Host "  1. Back up the folder $Folder somewhere private."
Write-Host "  2. Build a new release (push a version tag). It will be signed with this key."
Write-Host "  3. ONE LAST TIME on each phone: uninstall the app first, then install the new APK."
Write-Host "     (Turn off 'uninstall protection' first: Settings > Security > Device admin apps.)"
Write-Host "  4. From then on every release installs over the top, nothing to redo."
Write-Host "-----------------------------------------------------------------" -ForegroundColor Green
