import { initializeApp } from "firebase-admin/app";
import { FieldValue, getFirestore } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { logger, setGlobalOptions } from "firebase-functions/v2";
import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { DateTime } from "luxon";
import { syncItems } from "./calendar";
import {
  DATE_FMT,
  buildNewDay,
  diffCalendar,
  formatDay,
  meaningfulChange,
  reconcileRecurring,
  shouldSendMorning,
} from "./plan";
import { Day, Family, RecurringItem, WeekdayDefaults } from "./types";

initializeApp();
const db = getFirestore();
setGlobalOptions({ region: "europe-west2", maxInstances: 5 });

const DAYS_AHEAD = 14;
const TZ_DEFAULT = "Europe/London";

const familyRef = (id: string) => db.collection("families").doc(id);

async function loadFamily(familyId: string): Promise<Family> {
  const snap = await familyRef(familyId).get();
  if (!snap.exists) throw new Error(`Family ${familyId} not found`);
  return snap.data() as Family;
}

async function loadRecurring(familyId: string): Promise<Record<string, RecurringItem>> {
  const snap = await familyRef(familyId).collection("recurring").get();
  return Object.fromEntries(snap.docs.map((d) => [d.id, d.data() as RecurringItem]));
}

async function loadDefaults(familyId: string): Promise<Record<string, WeekdayDefaults>> {
  const snap = await familyRef(familyId).collection("defaults").get();
  return Object.fromEntries(snap.docs.map((d) => [d.id, d.data() as WeekdayDefaults]));
}

/** Creates any missing day docs in [from, from+count) and reconciles recurring items. */
async function ensureDays(familyId: string, from: string, count: number): Promise<number> {
  const family = await loadFamily(familyId);
  const [recurring, defaults] = await Promise.all([loadRecurring(familyId), loadDefaults(familyId)]);
  const start = DateTime.fromFormat(from, DATE_FMT);
  let written = 0;
  const batch = db.batch();
  for (let i = 0; i < count; i++) {
    const dt = start.plus({ days: i });
    const date = dt.toFormat(DATE_FMT);
    const ref = familyRef(familyId).collection("days").doc(date);
    const snap = await ref.get();
    if (!snap.exists) {
      const day = buildNewDay(date, family.people, defaults[String(dt.weekday)], recurring);
      batch.set(ref, { ...day, updatedAt: FieldValue.serverTimestamp() });
      written++;
    } else {
      const updated = reconcileRecurring(snap.data() as Day, recurring);
      if (updated) {
        batch.set(ref, { ...updated, updatedBy: "system", updatedAt: FieldValue.serverTimestamp() });
        written++;
      }
    }
  }
  if (written) await batch.commit();
  return written;
}

const todayIn = (family: Family) => DateTime.now().setZone(family.timezone || TZ_DEFAULT).toFormat(DATE_FMT);

async function tokensFor(familyId: string, excludePersonId?: string) {
  const users = await db.collection("users").where("familyId", "==", familyId).get();
  const tokens: { token: string; uid: string }[] = [];
  for (const u of users.docs) {
    if (excludePersonId && u.get("personId") === excludePersonId) continue;
    for (const t of (u.get("fcmTokens") as string[] | undefined) ?? []) tokens.push({ token: t, uid: u.id });
  }
  return tokens;
}

/** Sends a data message; the app builds an expandable notification and refreshes the widget. */
async function push(
  familyId: string,
  data: Record<string, string>,
  excludePersonId?: string,
): Promise<void> {
  const tokens = await tokensFor(familyId, excludePersonId);
  if (!tokens.length) return;
  const res = await getMessaging().sendEachForMulticast({
    tokens: tokens.map((t) => t.token),
    data,
    android: { priority: "high" },
  });
  // Remove tokens for uninstalled apps.
  const stale = res.responses
    .map((r, i) => ({ r, t: tokens[i] }))
    .filter(({ r }) =>
      ["messaging/registration-token-not-registered", "messaging/invalid-registration-token"].includes(
        r.error?.code ?? "",
      ),
    );
  await Promise.all(
    stale.map(({ t }) =>
      db.collection("users").doc(t.uid).update({ fcmTokens: FieldValue.arrayRemove(t.token) }),
    ),
  );
  logger.info("Push sent", { familyId, type: data.type, success: res.successCount, failed: res.failureCount });
}

// ---------------------------------------------------------------------------
// Callable: join the family whose memberEmails contains the caller's email.
// ---------------------------------------------------------------------------
export const joinFamily = onCall(async (req) => {
  const email = (req.auth?.token.email as string | undefined)?.toLowerCase();
  if (!req.auth || !email) throw new HttpsError("unauthenticated", "Sign in with Google first.");
  const families = await db.collection("families").where("memberEmails", "array-contains", email).limit(1).get();
  if (families.empty) {
    throw new HttpsError("permission-denied", `${email} isn't on the family list yet. Ask the family admin to add it.`);
  }
  const fam = families.docs[0];
  const family = fam.data() as Family;
  const person = family.people.find((p) => p.email?.toLowerCase() === email);
  await db.collection("users").doc(req.auth.uid).set(
    { familyId: fam.id, personId: person?.id ?? null, email, updatedAt: FieldValue.serverTimestamp() },
    { merge: true },
  );
  await ensureDays(fam.id, todayIn(family), DAYS_AHEAD);
  return { familyId: fam.id, personId: person?.id ?? null };
});

// ---------------------------------------------------------------------------
// Callable: make sure day docs exist for a date range (used for far-ahead dates).
// ---------------------------------------------------------------------------
export const ensureDayRange = onCall(async (req) => {
  if (!req.auth) throw new HttpsError("unauthenticated", "Sign in first.");
  const user = await db.collection("users").doc(req.auth.uid).get();
  const familyId = user.get("familyId") as string | undefined;
  if (!familyId) throw new HttpsError("permission-denied", "Not a family member.");
  const from = String(req.data?.from ?? "");
  const count = Math.min(Math.max(Number(req.data?.count ?? 1), 1), 62);
  if (!DateTime.fromFormat(from, DATE_FMT).isValid) throw new HttpsError("invalid-argument", "from must be yyyy-MM-dd");
  return { written: await ensureDays(familyId, from, count) };
});

// ---------------------------------------------------------------------------
// Nightly: roll the planning window forward.
// ---------------------------------------------------------------------------
export const materialiseDays = onSchedule({ schedule: "every day 00:15", timeZone: TZ_DEFAULT }, async () => {
  const families = await db.collection("families").get();
  for (const f of families.docs) {
    const written = await ensureDays(f.id, todayIn(f.data() as Family), DAYS_AHEAD);
    logger.info("Materialised days", { familyId: f.id, written });
  }
});

// ---------------------------------------------------------------------------
// Every 5 minutes: send the morning plan when each family's reminder time is due.
// ---------------------------------------------------------------------------
export const morningReminder = onSchedule({ schedule: "every 5 minutes", timeZone: TZ_DEFAULT }, async () => {
  const families = await db.collection("families").get();
  for (const f of families.docs) {
    const family = f.data() as Family;
    const now = DateTime.now().setZone(family.timezone || TZ_DEFAULT);
    if (!shouldSendMorning(family, now)) continue;
    const today = now.toFormat(DATE_FMT);

    // Claim the send first so overlapping runs can't double-send.
    const claimed = await db.runTransaction(async (tx) => {
      const fresh = await tx.get(f.ref);
      if (fresh.get("lastMorningSent") === today) return false;
      tx.update(f.ref, { lastMorningSent: today });
      return true;
    });
    if (!claimed) continue;

    await ensureDays(f.id, today, 1);
    const daySnap = await f.ref.collection("days").doc(today).get();
    const { title, body } = formatDay(daySnap.data() as Day, family);
    await push(f.id, { type: "morning", date: today, title, body });
  }
});

// ---------------------------------------------------------------------------
// Day written: sync Google Calendar and tell the others about edits.
// ---------------------------------------------------------------------------
export const onDayWritten = onDocumentWritten("families/{familyId}/days/{date}", async (event) => {
  const { familyId, date } = event.params;
  const before = event.data?.before.exists ? (event.data.before.data() as Day) : undefined;
  const after = event.data?.after.exists ? (event.data.after.data() as Day) : undefined;
  const family = await loadFamily(familyId);

  const { upserts, deletes } = diffCalendar(before, after);
  if (upserts.length || deletes.length) await syncItems(familyId, family, date, upserts, deletes);

  // Change notifications: only for user edits to today or tomorrow.
  if (!family.notifyOnChange || !after || after.updatedBy === "system") return;
  if (!meaningfulChange(before, after)) return;
  const today = DateTime.fromFormat(todayIn(family), DATE_FMT);
  const tomorrow = today.plus({ days: 1 }).toFormat(DATE_FMT);
  if (date !== today.toFormat(DATE_FMT) && date !== tomorrow) return;

  const editor = family.people.find((p) => p.id === after.updatedBy)?.name ?? "Someone";
  const which = date === tomorrow ? "tomorrow's" : "today's";
  const { title, body } = formatDay(after, family);
  await push(
    familyId,
    { type: "changed", date, title: `${editor} updated ${which} plan · ${title}`, body },
    after.updatedBy,
  );
});

// ---------------------------------------------------------------------------
// Recurring item written: update the upcoming days straight away.
// ---------------------------------------------------------------------------
export const onRecurringWritten = onDocumentWritten("families/{familyId}/recurring/{id}", async (event) => {
  const { familyId } = event.params;
  const family = await loadFamily(familyId);
  await ensureDays(familyId, todayIn(family), DAYS_AHEAD);
});

// ---------------------------------------------------------------------------
// Weekday defaults written: apply to upcoming days that nobody has edited yet.
// ---------------------------------------------------------------------------
export const onDefaultsWritten = onDocumentWritten("families/{familyId}/defaults/{weekday}", async (event) => {
  const { familyId, weekday } = event.params;
  const defaults = event.data?.after.exists ? (event.data.after.data() as WeekdayDefaults) : undefined;
  if (!defaults) return;
  const family = await loadFamily(familyId);
  const start = DateTime.fromFormat(todayIn(family), DATE_FMT);
  const batch = db.batch();
  let n = 0;
  for (let i = 1; i < DAYS_AHEAD; i++) {
    const dt = start.plus({ days: i });
    if (String(dt.weekday) !== weekday) continue;
    const ref = familyRef(familyId).collection("days").doc(dt.toFormat(DATE_FMT));
    const snap = await ref.get();
    if (snap.exists && !(snap.data() as Day).userEdited) {
      batch.update(ref, { people: defaults.people, updatedAt: FieldValue.serverTimestamp() });
      n++;
    }
  }
  if (n) await batch.commit();
});
