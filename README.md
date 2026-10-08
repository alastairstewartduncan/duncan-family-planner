# Duncan Family Planner

A shared daily family planner for Android, running on **your own Windows PC**. It
replaces the morning WhatsApp "Family daily reminders" message: everyone sees
who's where, what's for tea and who's taking who, gets the plan as a notification
each morning, and timed events can sync to a shared Google Calendar.

- **User guide:** [docs/USER-GUIDE.md](docs/USER-GUIDE.md)
- **Setup guide (one-off):** [docs/SETUP.md](docs/SETUP.md)

## Features

- Daily plan per person (where / extras / travel), tea, timed and untimed items, notes
- Owner/driver on each item, and **I'll do it** to claim unassigned jobs
- **Usual week** templates and **regular events** (e.g. ice hockey on Tuesdays) that fill new days automatically
- Copy previous day, copy as text
- Week view with teas and a shared shopping list
- Morning notification at a set time, scheduled by each phone itself (no push service)
- Home screen widget showing today's plan
- Works offline with the last plan seen; changes from others appear within ~15 seconds
- Optional Google Calendar sync for timed items
- Family PIN sign-in; all data stays on your PC; nightly backups

## How it fits together

```
Android app (Kotlin, Jetpack Compose, Glance widget)
   │  HTTP over Tailscale (WireGuard-encrypted, private to your devices)
   ▼
Windows PC — server/ (Node.js 22, no framework)
   ├─ data/planner.db   SQLite (built into Node) — days, regular events, usual week, shopping
   ├─ data/backups/     nightly copy, 14 kept
   ├─ REST API          /api/days, /api/recurring, /api/defaults, /api/shopping, /api/family …
   ├─ housekeeping      keeps the next 14 days ready from the usual week + regular events
   └─ Google Calendar   optional, via a service-account key
```

## Repository layout

| Path | What |
|---|---|
| `android/` | The Android app (open this folder in Android Studio) |
| `server/` | Home server: `src/` code and tests, `windows/install.ps1`, `family.example.json` |
| `.github/workflows/` | APK build (artifact + GitHub Release on `v*` tags) and server tests on Linux + Windows |
| `docs/` | User guide and setup guide |

## Development

```bash
# Server
cd server && npm install && npm test
cp family.example.json family.json && npm run build && npm run setup && npm start

# App: open android/ in Android Studio and run. On the emulator use server address 10.0.2.2
```
