// Google Calendar sync for timed items. Uses a Google Cloud service account key
// (google-key.json); the shared family calendar must be shared with that
// service account's email with "Make changes to events". See docs/SETUP.md.
import { google, calendar_v3 } from "googleapis";
import { DateTime } from "luxon";
import { DATE_FMT, calendarEventId, diffCalendar } from "./plan";
import { Day, Family, PlanItem } from "./types";

const FAMILY_KEY = "duncan"; // part of each event id, keeps ids stable

function statusCode(err: unknown): number | undefined {
  const e = err as { code?: number; response?: { status?: number } };
  return e?.response?.status ?? e?.code;
}

export class CalendarSync {
  private api: calendar_v3.Calendar;

  constructor(keyFile: string, private getFamily: () => Family) {
    const auth = new google.auth.GoogleAuth({ keyFile, scopes: ["https://www.googleapis.com/auth/calendar.events"] });
    this.api = google.calendar({ version: "v3", auth });
  }

  /** Fire-and-forget: errors are logged, never thrown into the request. */
  dayChanged(date: string, before: Day | undefined, after: Day | undefined) {
    const family = this.getFamily();
    if (!family.calendarId) return;
    const { upserts, deletes } = diffCalendar(before, after);
    if (!upserts.length && !deletes.length) return;
    const jobs = [
      ...upserts.map((i) => this.upsert(family, date, i)),
      ...deletes.map((id) => this.remove(family, date, id)),
    ];
    Promise.allSettled(jobs).then((results) =>
      results
        .filter((r): r is PromiseRejectedResult => r.status === "rejected")
        .forEach((r) => console.error(`[calendar] sync failed for ${date}:`, String(r.reason))),
    );
  }

  private body(family: Family, date: string, item: PlanItem): calendar_v3.Schema$Event {
    const tz = family.timezone;
    const start = DateTime.fromFormat(`${date} ${item.time}`, `${DATE_FMT} HH:mm`, { zone: tz });
    const end = start.plus({ minutes: item.durationMins || 60 });
    const who = family.people.find((p) => p.id === (item.claimedBy || item.ownerPersonId))?.name;
    const attendees = (item.attendees ?? []).map((id) => family.people.find((p) => p.id === id)?.name).filter(Boolean);
    return {
      summary: who ? `${item.title} (${who})` : item.title,
      description: [item.notes, who ? `Responsible: ${who}` : "", attendees.length ? `Who: ${attendees.join(", ")}` : "", "Added by Duncan Family Planner"]
        .filter(Boolean)
        .join("\n"),
      start: { dateTime: start.toISO({ includeOffset: false })!, timeZone: tz },
      end: { dateTime: end.toISO({ includeOffset: false })!, timeZone: tz },
      status: "confirmed",
    };
  }

  private async upsert(family: Family, date: string, item: PlanItem) {
    const calendarId = family.calendarId;
    const eventId = calendarEventId(FAMILY_KEY, date, item.id);
    const requestBody = this.body(family, date, item);
    try {
      await this.api.events.update({ calendarId, eventId, requestBody });
    } catch (err) {
      if (statusCode(err) !== 404) throw err;
      await this.api.events.insert({ calendarId, requestBody: { ...requestBody, id: eventId } });
    }
  }

  private async remove(family: Family, date: string, itemId: string) {
    try {
      await this.api.events.delete({ calendarId: family.calendarId, eventId: calendarEventId(FAMILY_KEY, date, itemId) });
    } catch (err) {
      const code = statusCode(err);
      if (code !== 404 && code !== 410) throw err;
    }
  }
}
