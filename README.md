# Duncan Family Planner

A shared daily family planner for Android. It replaces the morning WhatsApp
"Family daily reminders" message: everyone sees who's where, what's for tea and
who's taking who, gets the plan as a notification each morning, and timed events
sync to a shared Google Calendar.

- **User guide:** [docs/USER-GUIDE.md](docs/USER-GUIDE.md)
- **Setup guide (one-off):** [docs/SETUP.md](docs/SETUP.md)

## Features

- Daily plan per person (where / extras / travel), tea, timed and untimed items, notes
- Owner/driver on each item, and **I'll do it** to claim unassigned jobs
- **Usual week** templates and **regular events** (e.g. ice hockey on Tuesdays) that fill new days automatically
- Copy previous day, copy as text
- Week view with teas (meal planner) and a shared shopping list
- Morning push notification at a set time, plus change notifications
- Google Calendar sync for timed items to a shared family calendar
- Home screen widget showing today's plan
- Google sign-in; only listed family emails can join

## How it fits together

```
Android app (Kotlin, Jetpack Compose, Glance widget)
   │  Firebase Auth (Google) · Firestore (live sync, offline cache)
   ▼
Firebase (europe-west2)
   ├─ Firestore: families/{id}/days, recurring, defaults, shopping · users/{uid}
   └─ Cloud Functions (TypeScript)
        joinFamily        callable — email allow-list → membership
        ensureDayRange    callable — create days from defaults + regular events
        materialiseDays   nightly — keeps the next 14 days ready
        morningReminder   every 5 min — sends the plan at each family's set time (FCM)
        onDayWritten      Google Calendar sync + change notifications
        onRecurringWritten / onDefaultsWritten — apply changes to upcoming days
   ▼
Google Calendar (shared "Duncan Family" calendar)
```

## Repository layout

| Path | What |
|---|---|
| `android/` | The Android app (open this folder in Android Studio) |
| `firebase/functions/` | Cloud Functions, unit tests (`npm test`) and the seed script |
| `firebase/firestore.rules` | Security rules — members can only see their own family |
| `firebase/seed/family.json` | Initial family, usual week and regular events |
| `.github/workflows/` | APK build (artifact + GitHub Release on `v*` tags) and functions tests/deploy |
| `docs/` | User guide and setup guide |

## Development

```bash
# Server
cd firebase/functions && npm install && npm test
firebase emulators:start   # optional local testing

# App
# put google-services.json in android/app/, then open android/ in Android Studio
```
