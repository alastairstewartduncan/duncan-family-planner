# Setup guide (one-off, for the family admin)

Duncan Family Planner runs on **your own Windows PC**. The family's data lives in
a single database file on that PC, and the phones reach it over the internet
through your router, at your home's static IP address. Everything is encrypted
(HTTPS) with the server's own certificate, which each phone checks with a short
security code the first time. Allow about 30 minutes.

```
 Phones (app + widget)  ──HTTPS, static IP──► router ──port 8787──► Windows PC
                                                    └ Duncan Family Planner server
                                                       ├ data\planner.db  (SQLite)
                                                       ├ data\backups\    (nightly, 14 kept)
                                                       └ ──► Google Calendar (optional)
```

## What you need

- A Windows 10/11 PC that's normally on (it can sleep at night, but the app can't
  load new plans while it's off — phones still show the last plan they saw).
- [Node.js](https://nodejs.org) **LTS, version 22.13 or newer**.
- A **static IP address** from your broadband provider, and access to your router's
  settings (to forward one port).
- [Git for Windows](https://git-scm.com/download/win), or download the repo as a ZIP
  from GitHub (Code → Download ZIP).

## 1. Get the code onto the PC

```powershell
cd C:\
git clone https://github.com/alastairstewartduncan/duncan-family-planner
cd duncan-family-planner\server
```

(Any folder works; these steps assume `C:\duncan-family-planner`.)

> **Quickest way:** after step 1, double-click **`server\windows\first-time-setup.cmd`**.
> It does steps 2 and 3 for you: installs the server, asks you to type the family
> PIN, sets up the family and restarts the server. Then carry on from step 4.

## 2. Install the server

Open the `server\windows` folder in File Explorer and double-click
**`install.cmd`**. Say **Yes** when Windows asks for administrator rights.

Or, from an **Administrator PowerShell** window:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
& C:\duncan-family-planner\server\windows\install.ps1
```

(Windows blocks scripts by default. The first line relaxes that for this one window
only, so nothing changes on the PC permanently.)

This installs the packages, builds the server, adds a **"Duncan Family Planner"**
task that starts it whenever Windows starts (and restarts it if it stops), and
opens port **8787** in Windows Firewall. At the end it shows your **server address**
(your public IP) and the server's **security code**.

## 3. Set up the family

```powershell
copy family.example.json family.json
notepad family.json
```

- Set **`pin`** to a 6–8 digit family PIN. Everyone uses it once when they first
  sign in on their phone.
- Check names and colours, the usual week (`defaults`, 1 = Monday) and the regular
  events (`recurring`). All of these can be changed later in the app.

Then load it and restart the server:

```powershell
npm run setup
Restart-ScheduledTask "Duncan Family Planner"   # or: Stop-ScheduledTask / Start-ScheduledTask
```

You can now delete the `pin` line from family.json. To change the PIN later, put
a new one in family.json and run `npm run setup` again (everyone signs in again).

## 4. Open the port on your router

1. Sign in to your router's settings page (often `192.168.1.1`; the address and
   password are usually on a sticker on the router).
2. Find **Port forwarding** (sometimes under *Advanced*, *NAT* or *Firewall*).
3. Add a rule: **TCP port 8787** → this PC's local address (shown at the end of the
   installer, e.g. `192.168.1.50`), port 8787.
4. Give the PC a **fixed local address** too (often *DHCP reservation* or *Address
   reservation* on the same router), so the rule keeps pointing at it.

Check it works: on a phone with **Wi-Fi turned off**, open
`https://YOUR.STATIC.IP:8787/api/health` in Chrome. Chrome warns that the connection
isn't private — that's expected (it doesn't know the server's own certificate; the
app does). Tap *Advanced → Proceed* and you should see `"ok":true`.

**How safe is this?** Everything is encrypted; phones only talk to the server whose
security code they checked; the PIN is 6–8 digits; and after 10 wrong PINs in 15
minutes from anywhere, sign-in pauses for everyone for 15 minutes. Only port 8787 is
opened.

## 5. Install the app on each phone

1. On GitHub, open **Actions → Android app**, pick the latest green run and download
   **DuncanFamilyPlanner-apk** (or use a Release — see below).
2. Copy the APK to the phone and open it; allow "install unknown apps" when asked.
3. In the app, enter the server address — your static IP, e.g. `81.2.69.160` (the
   app adds `https://` and `:8787`) — and tap **Connect**. The first time, it shows a
   **security code**: check it matches the one the installer showed (also in
   `server\data\tls\security-code.txt`) and tap **It matches**. Then pick who you
   are and enter the family PIN.
4. Allow notifications when asked, then add the widget. The
   [user guide](USER-GUIDE.md) covers the rest.

**Releases:** push a tag (`git tag v2.0.0 && git push --tags`) and the APK is
attached to a GitHub Release with a download link you can open on each phone.

### Optional: sign the APK with your own key

Unsigned CI builds are debug builds, which is fine for family use. For a release
build (and smooth updates over the top), create a key once and add it to GitHub
**Settings → Secrets and variables → Actions**:

```powershell
keytool -genkeypair -v -keystore duncan-planner.jks -alias planner -keyalg RSA -keysize 2048 -validity 10000
[Convert]::ToBase64String([IO.File]::ReadAllBytes("duncan-planner.jks")) | Set-Clipboard
```

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | paste from the clipboard |
| `SIGNING_STORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | `planner` |
| `SIGNING_KEY_PASSWORD` | key password |

Keep `duncan-planner.jks` safe and out of the repo. Note: switching from debug to
release builds means uninstalling the debug app once first.

## 6. Optional: Google Calendar sync

Timed items (e.g. *19:00 Ice hockey*) can be copied to a shared **Duncan Family**
Google Calendar. This uses a free Google Cloud "service account" — no billing needed.

1. Go to <https://console.cloud.google.com>, create a project (e.g. *duncan-planner*).
2. **APIs & Services → Library** → enable **Google Calendar API**.
3. **IAM & Admin → Service accounts → Create service account** (name it *planner*),
   no roles needed. Open it → **Keys → Add key → JSON**. Save the downloaded file as
   `C:\duncan-family-planner\server\google-key.json`.
4. In Google Calendar (web): **Other calendars → + → Create new calendar** →
   "Duncan Family". In its settings → **Share with specific people** → add the
   service account's email (`planner@…iam.gserviceaccount.com`) with **Make changes
   to events**. Share it with Claire and Alfie as normal too.
5. Copy the **Calendar ID** (calendar settings → Integrate calendar), and paste it in
   the app under **More → Google Calendar → Save calendar**.
6. Restart the server task so it picks up the key file. `logs\server.log` should
   say *Google Calendar sync: on*.

## Day-to-day admin

| Task | How |
|---|---|
| See if it's running | `http://localhost:8787/api/health` in a browser on the PC |
| Logs | `server\logs\server.log` |
| Backups | `server\data\backups\` — one per day, last 14 kept. Copy them somewhere safe (OneDrive, USB) now and then. |
| Restore a backup | Stop the task, copy a backup over `server\data\planner.db` (delete any `planner.db-wal`/`-shm` files), start the task. |
| Update to a new version | `git pull`, then double-click `windows\install.cmd` again. |
| Move to another PC | Copy the whole `server\data` folder and `google-key.json`, run the installer there, update the address in the app. |
| Uninstall | In an Administrator PowerShell: `Set-ExecutionPolicy -Scope Process Bypass -Force; & .\windows\uninstall.ps1` (data is kept). |

## Troubleshooting

| Problem | Fix |
|---|---|
| "running scripts is disabled on this system" | Use `install.cmd`, or run `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force` in the same window first. |
| App says it can't reach the server | PC on and awake? Port 8787 forwarded on the router to the PC? Try `https://YOUR.STATIC.IP:8787/api/health` in the phone's browser with Wi-Fi off. |
| Works at home but not out and about | The router's port forward is missing or points at the wrong PC address. Some routers don't let you reach your own public IP from inside the house; if so, at home use the PC's local address. |
| "Security code has changed" | Only expected if the server was reinstalled and `data	ls` was deleted. Otherwise don't continue — something may be pretending to be the server. |
| "That PIN isn't right" | Five wrong tries locks sign-in for a minute. Reset the PIN with `npm run setup`. |
| Morning notification didn't come | Notifications allowed for the app? Today ticked under More → Morning reminder? Some phones (Samsung, Xiaomi) need the app set to *Unrestricted* battery use. Use **More → Send me a test**. |
| Events not in Google Calendar | Calendar ID saved in the app? Calendar shared with the service account? `google-key.json` in the server folder and the task restarted? Check `logs\server.log`. |
| PC sleeps | Settings → System → Power → set *Sleep* to Never when plugged in, or accept that the app shows the last plan while it sleeps. |

## Optional: tray app for the home server (Windows)

A little house icon by the clock shows whether the server is running, and lets you start,
stop and restart it and see how the family's phones are doing.

1. Double-click `server\windows\tray\install-tray.cmd` (no administrator needed). It starts
   now and again every time you sign in to Windows.
2. Look for the house icon. If it is hidden, click the ^ arrow by the clock and drag it onto
   the taskbar. **Green** = running, **amber** = running but worth a look (sign-in paused, or
   someone's app blocker is off), **red** = not running, **grey** = checking.
3. Double-click it (or right-click → *Show status*) for uptime, backups, sign-in state, each
   phone (bricked or free, blocker on or off, phone-free time today and this week) and recent
   activity.

Start, Stop and Restart ask Windows for permission each time, because the server runs as a
startup task with full rights. Closing the status window only hides it; *Exit tray app* leaves
the server running.

The tray app reads `https://localhost:<port>/api/admin/status`, which the server only answers
to programs on the same PC (never over the network or through a proxy) and which carries no
PINs, tokens or tag secrets. Its own log is `server\logs\tray.log`. To remove it:
`powershell -ExecutionPolicy Bypass -File server\windows\tray\install-tray.ps1 -Remove`.

You need to update and restart the server (see above) for the status page to exist.
