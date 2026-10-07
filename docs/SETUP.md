# Setup guide (one-off, for the family admin)

This gets Duncan Family Planner running from scratch: a Firebase project, the
server functions, the shared Google Calendar and the Android app. Allow about
an hour. Everything happens once; after that the family just uses the app.

## What you need

- A Google account to own the Firebase project (Alastair's).
- A computer with [Node.js 20](https://nodejs.org) and the Firebase CLI
  (`npm install -g firebase-tools`).
- Optional: [Android Studio](https://developer.android.com/studio) if you want to
  build the app locally instead of on GitHub.

## 1. Create the Firebase project

1. Go to <https://console.firebase.google.com> → **Add project** → name it
   `duncan-family-planner` (note the project ID it gives you).
2. **Upgrade to the Blaze plan** (Project settings → Usage and billing). The
   scheduled morning reminder needs Cloud Scheduler, which is Blaze-only. A
   family's usage sits inside the free allowance; set a £5 budget alert to be safe.
3. **Build → Firestore Database → Create database**, location `europe-west2 (London)`,
   production mode.
4. **Build → Authentication → Get started → Google** → enable it.

## 2. Add the Android app to Firebase

1. Project settings → **Add app → Android**.
   - Package name: `uk.co.duncan.familyplanner`
   - Nickname: Duncan Family Planner
2. Create the signing key the app will be signed with (keep it safe and **never
   commit it**):

   ```bash
   keytool -genkeypair -v -keystore duncan-planner.jks -alias planner \
     -keyalg RSA -keysize 2048 -validity 10000
   keytool -list -v -keystore duncan-planner.jks -alias planner   # shows SHA-1 and SHA-256
   ```

3. Back in Firebase, add the **SHA-1** and **SHA-256** fingerprints to the Android
   app (Project settings → your app → Add fingerprint). Google sign-in won't work
   without them.
4. Download **google-services.json**. You'll need it in step 6.

## 3. Configure the family

Edit `firebase/seed/family.json`:

- Replace the `example.com` emails with each person's real Google account. Only
  these emails can join.
- Adjust names, colours, the usual week (`defaults`, 1 = Monday) and the regular
  events (`recurring`). Everything can be changed later in the app.

## 4. Deploy the server

```bash
cd firebase
firebase login
firebase use --add            # pick your project, alias "default"
cd functions && npm install && npm test && cd ..
firebase deploy --only functions,firestore
```

Then load the family:

```bash
gcloud auth application-default login
cd functions
GOOGLE_CLOUD_PROJECT=<your-project-id> npm run seed
```

## 5. Shared Google Calendar

1. In Google Calendar (web) → **Other calendars → + → Create new calendar** →
   "Duncan Family". Share it with Claire and Alfie as normal.
2. Find the server's identity: Google Cloud console → **IAM** → the
   *Default compute service account* (`<number>-compute@developer.gserviceaccount.com`).
3. Calendar settings → **Share with specific people** → add that service account
   with **Make changes to events**.
4. Enable the API: Google Cloud console → **APIs & Services → Enable APIs** →
   *Google Calendar API*.
5. Copy the **Calendar ID** (Calendar settings → Integrate calendar). Paste it into
   the app under **More → Google Calendar** after you've signed in.

## 6. Build the app on GitHub

In the GitHub repo → **Settings → Secrets and variables → Actions**, add:

| Secret | Value |
|---|---|
| `GOOGLE_SERVICES_JSON` | the full contents of google-services.json |
| `SIGNING_KEYSTORE_BASE64` | output of `base64 -w0 duncan-planner.jks` |
| `SIGNING_STORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | `planner` |
| `SIGNING_KEY_PASSWORD` | key password |

Then **Actions → Android app → Run workflow**. When it finishes, download the
`DuncanFamilyPlanner-apk` artifact. To make a proper release with a download
link, push a tag: `git tag v1.0.0 && git push --tags`; the APK is attached to the
GitHub Release.

### Or build locally

Put `google-services.json` in `android/app/`, open the `android` folder in
Android Studio and press Run.

## 7. Install on each phone

Send the APK to each phone (or open the GitHub Release page on the phone),
tap it, and allow "install unknown apps" when asked. Each person signs in with
the Google account listed in `family.json`. Then follow the [user guide](USER-GUIDE.md).

## Optional: deploy from GitHub

The **Firebase functions** workflow tests the server on every change. To deploy
from GitHub as well, add a `FIREBASE_SERVICE_ACCOUNT` secret (a service account
key with Firebase Admin rights) and a `FIREBASE_PROJECT_ID` variable, then run
the workflow manually with *Deploy* ticked.

## Troubleshooting

| Problem | Fix |
|---|---|
| "isn't on the family list" when signing in | Add the email under More → Family (or in family.json and re-seed). |
| Sign-in fails straight away | SHA-1/SHA-256 missing in Firebase, or the APK was signed with a different key. |
| No morning notification | Check notifications are allowed for the app, the reminder days in More, and the `morningReminder` logs in the Firebase console. |
| Events not in the calendar | Calendar ID set? Shared with the compute service account? Calendar API enabled? See `onDayWritten` logs. |
| Widget says "Couldn't load" | Open the app once while online; the widget then works from the offline cache. |
