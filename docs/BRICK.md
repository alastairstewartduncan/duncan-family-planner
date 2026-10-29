# Brick — phone-free time

Brick locks everything on your phone except the apps you really need, until you
tap the phone on a **Brick**: a small NFC tag stuck somewhere at home. Because
you have to get up and walk to the tag to unlock, scrolling stops being the easy
option.

It lives in the planner's **Brick** tab and works without a signal: each phone
decides for itself when to lock, and the family PC just keeps everyone's rules
and shows what each phone is doing.

## What's better than a bought Brick

| | Bought Brick | Duncan Family Brick |
|---|---|---|
| Tags | One plastic block per phone | As many cheap stickers as you like, shared by the whole family |
| Family | Each phone on its own | **Family Focus** bricks every phone at once; parents see everyone's status |
| Schedules | — | Automatic phone-free times (school nights), even offline |
| Emergencies | 5 "emergency unbricks" anyone can use | **One sealed emergency tag**; using it tells the family and it must be re-armed |
| Getting round it | Delete the app | Uninstall protection, Settings blocked while bricked, parents told if the blocker is switched off |
| Passes | — | A parent can unlock someone for 15 min – 2 h from their own phone |
| Motivation | Time bricked | Today, this week, a daily streak and a 7-day chart for everyone |
| Copied tags | — | Tags are checked against their factory serial number *and* a secret, and can be locked read-only |

## What to buy

**NTAG215 NFC stickers or cards** (NTAG213 and 216 work too). A pack of 10–20
costs a few pounds. Cards or key fobs are tougher than stickers; one for the
emergency tag is a good idea.

## Setting up (parents)

1. Update the app and the home server (see *Updating* below).
2. On a parent's phone open **Brick → Parents & emergency settings** and tick
   who's in charge (Claire and Alastair). Until you do, *anyone* can change the
   rules — so do this first. (Or add `"brick": { "guardians": ["claire", "alastair"] }`
   to `family.json` and run `npm run setup` again.)
3. **Add a Brick**: tap **Add a Brick**, name where it'll live ("Kitchen"), leave
   **Lock the tag** on, tap **Next** and hold a blank tag flat against the back
   of the phone until it buzzes. Stick it up. Add as many as you like.
4. **Seal the emergency tag**: tap **Seal an emergency tag**, hold a blank card to
   the phone, then put it in an envelope somewhere sensible. There can only ever
   be one — making a new one retires the old one on every phone.
5. Optional: **Add a phone-free time**, e.g. *School nights*, Alfie, Sun–Thu,
   21:30 → 07:00.

## Setting up each phone

Open **Brick**. A checklist shows what's left:

1. **Turn on NFC.**
2. **Switch on the app blocker.** This opens Settings → Accessibility → *Duncan
   Family Brick* → on. It only sees *which* app is open — never what's on screen,
   what you type or your messages.
   - On Android 13 and later it may be greyed out because the app wasn't installed
     from the Play Store. Tap **App info** → ⋮ (top right) → **Allow restricted
     settings**, then try again.
3. **Protect against uninstalling.** Stops the planner being deleted without
   first switching this off in Settings — which Brick blocks while bricked.
4. **Choose your essential apps.** Phone, Messages, the keyboard and this planner
   always work. Pick anything else you'd need: Maps, the bus app, the camera.
5. **Allow notifications** so emergency alerts get through.

## Using it

- **Brick your phone:** hold it against any Brick. Two buzzes = locked.
- **Unbrick:** hold it against a Brick again. One buzz = unlocked, and it tells
  you how long you were phone-free.
- While bricked, opening any other app shows the Brick screen instead, with your
  essential apps one tap away and a **Phone** button.
- You can lock the screen, restart the phone or lose signal — it stays bricked.

### Family Focus

A parent taps **Start Family Focus** and picks 30 minutes, 1 hour, 2 hours or
*until I end it*. Every phone bricks within about 30 seconds. Tapping a Brick
won't end it for a child — the Brick screen says who to ask. A parent who taps a
Brick can choose **Unlock just me** or **End Family Focus for everyone**.

### Phone-free times

Schedules lock and unlock phones on their own, with no signal needed. A parent
tapping a Brick during one can unlock just their own phone until it ends.

### Passes

Tap a person (or the timer icon next to them) to give a 15-minute to 2-hour pass.
It unlocks their phone straight away, even during Family Focus or a schedule, and
relocks it when the time is up. Tap again to cancel it.

## The emergency tag

- Unlocks any phone at once for the set time (60 minutes by default), even during
  Family Focus or a schedule, and even with no signal.
- Every other phone gets an alert: *"Alfie used the emergency tag"*.
- It then stops working until a parent taps **Re-arm** (you can turn this off
  under *Parents & emergency settings*).
- Calls, including 999, work while bricked anyway — the tag is for when you need
  the rest of the phone.

## What parents see

The **Brick** tab shows each person: bricked or not, for how long, any pass, how
long they've been phone-free this week, and when their phone last checked in.
**⚠ App blocker is off** (and a notification) means someone switched it off in
Settings. **Recent** lists the last emergency taps, passes, refused unlocks and
so on.

## Limits worth knowing

- A phone with **no NFC** can't use Bricks, but can still be included in Family
  Focus, schedules and passes.
- Brick relies on Android's accessibility service. Booting into **safe mode**
  turns it off; the parents' screen will show the blocker as off.
- If someone switches off uninstall protection while *unlocked*, they could then
  remove the app. The family is told when protection or the blocker goes off.
- A person can only change their essential apps while unlocked.
- Signing out of the planner ends a manual brick, so no phone is ever stuck.

## Updating

- **Server:** on the PC, in the planner folder run `git pull`, then double-click
  `server\windows\install.cmd` again. It rebuilds and restarts the server and
  keeps all your data.
- **App:** install the new APK from the GitHub release / Actions artifact over the
  old one. Nobody needs to sign in again.
