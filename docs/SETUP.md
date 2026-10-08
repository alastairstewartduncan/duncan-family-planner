# Setup guide (one-off, for the family admin)

Duncan Family Planner runs on **your own Windows PC**. The family's data lives in
a single database file on that PC, and the phones reach it over **Tailscale** — a
free private network — so nothing is exposed to the internet. Allow about 45
minutes.

```
 Phones (app + widget)  ──Tailscale (encrypted)──►  Windows PC
                                                    └ Duncan Family Planner server
                                                       ├ data\planner.db  (SQLite)
                                                       ├ data\backups\    (nightly, 14 kept)
                                                       └ ──► Google Calendar (optional)
```

## What you need

- A Windows 10/11 PC that's normally on (it can sleep at night, but the app can't
  load new plans while it's off — phones still show the last plan they saw).
- [Node.js](https://nodejs.org) **LTS, version 22.13 or newer**.
- [Tailscale](https://tailscale.com/download) — free for personal use.
- [Git for Windows](https://git-scm.com/download/win), or download the repo as a ZIP
  from GitHub (Code → Download ZIP).

## 1. Get the code onto the PC

```powershell
cd C:\
git clone https://github.com/alastairstewartduncan/duncan-family-planner
cd duncan-family-planner\server
```

(Any folder works; these steps assume `C:\duncan-family-planner`.)

## 2. Install the server

Open **PowerShell as administrator** (Start → type PowerShell → right-click → Run as
administrator), then:

```powershell
cd C:\duncan-family-planner\server
powershell -ExecutionPolicy Bypass -File .\windows\install.ps1
```

This installs the packages, builds the server, adds a **"Duncan Family Planner"**
task that starts it whenever Windows starts (and restarts it if it stops), and
opens port **8787** in Windows Firewall for Tailscale and your home Wi-Fi only.

## 3. Set up the family

```powershell
copy family.example.json family.json
notepad family.json
```

- Set **`pin`** to a 4–8 digit family PIN. Everyone uses it once when they first
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

## 4. Tailscale

1. Install Tailscale on the **PC** and sign in (a Google account is fine).
2. In the [Tailscale admin console](https://login.tailscale.com/admin/machines),
   note the PC's name (e.g. `family-pc`). **MagicDNS** is on by default, so phones
   can use that name. Optionally turn off key expiry for the PC (⋯ → Disable key
   expiry) so it never drops off.
3. Install the **Tailscale app on each phone** and sign in to the **same Tailscale
   account** (or invite Claire and Alfie to your tailnet from the admin console).
   Leave Tailscale switched on — it uses very little battery.

Check it works: on a phone with Tailscale on, open
`http://family-pc:8787/api/health` in the browser. You should see `"ok":true`.

## 5. Install the app on each phone

1. On GitHub, open **Actions → Android app**, pick the latest green run and download
   **DuncanFamilyPlanner-apk** (or use a Release — see below).
2. Copy the APK to the phone and open it; allow "install unknown apps" when asked.
3. In the app, enter the server address — just the PC's Tailscale name, e.g.
   `family-pc` (the app adds `http://` and `:8787`) — tap **Connect**, pick who you
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
| Update to a new version | `git pull`, then re-run `windows\install.ps1` as administrator. |
| Move to another PC | Copy the whole `server\data` folder and `google-key.json`, run the installer there, update the address in the app. |
| Uninstall | `windows\uninstall.ps1` as administrator (data is kept). |

## Troubleshooting

| Problem | Fix |
|---|---|
| App says it can't reach the server | Tailscale on in the phone? PC on and awake? Try `http://<pc-name>:8787/api/health` in the phone's browser. |
| Works at home but not out and about | The phone is using your Wi-Fi address instead of Tailscale — use the Tailscale name, and check Tailscale is connected. |
| "That PIN isn't right" | Five wrong tries locks sign-in for a minute. Reset the PIN with `npm run setup`. |
| Morning notification didn't come | Notifications allowed for the app? Today ticked under More → Morning reminder? Some phones (Samsung, Xiaomi) need the app set to *Unrestricted* battery use. Use **More → Send me a test**. |
| Events not in Google Calendar | Calendar ID saved in the app? Calendar shared with the service account? `google-key.json` in the server folder and the task restarted? Check `logs\server.log`. |
| PC sleeps | Settings → System → Power → set *Sleep* to Never when plugged in, or accept that the app shows the last plan while it sleeps. |
