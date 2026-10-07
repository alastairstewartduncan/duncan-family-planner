// Google Calendar sync. The Cloud Functions service account writes events to
// the shared family calendar, so the calendar must be shared with that
// service account ("Make changes to events") — see docs/SETUP.md.
import { logger } from "firebase-functions/v2";
import { google, calendar_v3 } from "googleapis";
import { DateTime } from "luxon";
import { DATE_FMT, calendarEventId } from "./plan";
import { Family, PlanItem } from "./types";

let client: calendar_v3.Calendar | null = null;

function calendar(): calendar_v3.Calendar {
  if (!client) {
    const auth = new google.auth.GoogleAuth({
      scopes: ["https://www.googleapis.com/auth/calendar.events"],
    });
    client = google.calendar({ version: "v3", auth });
  }
  return client;
}

function statusCode(err: unknown): number | undefined {
  const e = err as { code?: number; response?: { status?: number } };
  return e?.response?.status ?? e?.code;
}

function eventBody(family: Family, date: string, item: PlanItem): calendar_v3.Schema$Event {
  const tz = family.timezone || "Europe/London";
  const start = DateTime.fromFormat(`${date} ${item.time}`, `${DATE_FMT} HH:mm`, { zone: tz });
  const end = start.plus({ minutes: item.durationMins || 60 });
  const whoId = item.claimedBy || item.ownerPersonId;
  const who = family.people.find((p) => p.id === whoId)?.name;
  const attendees = (item.attendees ?? [])
    .map((id) => family.people.find((p) => p.id === id)?.name)
    .filter(Boolean);
  const description = [
    item.notes,
    who ? `Responsible: ${who}` : "",
    attendees.length ? `Who: ${attendees.join(", ")}` : "",
    "Added by Duncan Family Planner",
  ]
    .filter(Boolean)
    .join("\n");
  return {
    summary: who ? `${item.title} (${who})` : item.title,
    description,
    start: { dateTime: start.toISO({ includeOffset: false })!, timeZone: tz },
    end: { dateTime: end.toISO({ includeOffset: false })!, timeZone: tz },
    status: "confirmed",
    extendedProperties: { private: { dfpItemId: item.id, dfpDate: date } },
  };
}

export async function upsertEvent(familyId: string, family: Family, date: string, item: PlanItem) {
  const calendarId = family.calendarId!;
  const eventId = calendarEventId(familyId, date, item.id);
  const requestBody = eventBody(family, date, item);
  try {
    await calendar().events.update({ calendarId, eventId, requestBody });
  } catch (err) {
    if (statusCode(err) !== 404) throw err;
    await calendar().events.insert({ calendarId, requestBody: { ...requestBody, id: eventId } });
  }
}

export async function deleteEvent(familyId: string, family: Family, date: string, itemId: string) {
  try {
    await calendar().events.delete({
      calendarId: family.calendarId!,
      eventId: calendarEventId(familyId, date, itemId),
    });
  } catch (err) {
    const code = statusCode(err);
    if (code !== 404 && code !== 410) throw err; // already gone
  }
}

export async function syncItems(
  familyId: string,
  family: Family,
  date: string,
  upserts: PlanItem[],
  deletes: string[],
) {
  if (!family.calendarId) return;
  const jobs = [
    ...upserts.map((i) => upsertEvent(familyId, family, date, i)),
    ...deletes.map((id) => deleteEvent(familyId, family, date, id)),
  ];
  const results = await Promise.allSettled(jobs);
  results
    .filter((r): r is PromiseRejectedResult => r.status === "rejected")
    .forEach((r) => logger.error("Calendar sync failed", { familyId, date, error: String(r.reason) }));
}
