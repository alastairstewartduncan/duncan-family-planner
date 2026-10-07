// Pure planning logic (no Firebase calls) so it can be unit tested.
import { createHash } from "crypto";
import { DateTime } from "luxon";
import {
  Day,
  Family,
  Person,
  PersonDay,
  PlanItem,
  RecurringItem,
  WeekdayDefaults,
  emptyPersonDay,
} from "./types";

export const DATE_FMT = "yyyy-MM-dd";

export function isoWeekday(date: string): number {
  return DateTime.fromFormat(date, DATE_FMT).weekday; // 1 = Mon .. 7 = Sun
}

export function recurringApplies(r: RecurringItem, date: string): boolean {
  if (!r.active) return false;
  if (r.startDate && date < r.startDate) return false;
  if (r.endDate && date > r.endDate) return false;
  return (r.daysOfWeek ?? []).includes(isoWeekday(date));
}

/** Deterministic id for a recurring occurrence on a given day. */
export function recurringItemId(recurringId: string, date: string): string {
  return `r_${recurringId}_${date.replace(/-/g, "")}`;
}

export function itemFromRecurring(recurringId: string, r: RecurringItem, date: string): PlanItem {
  return {
    id: recurringItemId(recurringId, date),
    title: r.title,
    time: r.time ?? null,
    durationMins: r.durationMins ?? 60,
    ownerPersonId: r.ownerPersonId ?? null,
    attendees: r.attendees ?? [],
    notes: r.notes ?? "",
    recurringId,
    claimedBy: null,
    done: false,
  };
}

export function sortItems(items: PlanItem[]): PlanItem[] {
  // Untimed items first (in entry order), then timed items by time.
  const untimed = items.filter((i) => !i.time);
  const timed = items.filter((i) => !!i.time).sort((a, b) => a.time!.localeCompare(b.time!));
  return [...untimed, ...timed];
}

/** Builds a brand-new day from weekday defaults and recurring items. */
export function buildNewDay(
  date: string,
  people: Person[],
  defaults: WeekdayDefaults | undefined,
  recurring: Record<string, RecurringItem>,
): Day {
  const peopleDays: Record<string, PersonDay> = {};
  for (const p of people) {
    peopleDays[p.id] = { ...emptyPersonDay(), ...(defaults?.people?.[p.id] ?? {}) };
  }
  const items = Object.entries(recurring)
    .filter(([, r]) => recurringApplies(r, date))
    .map(([id, r]) => itemFromRecurring(id, r, date));
  return {
    date,
    people: peopleDays,
    tea: "",
    items: sortItems(items),
    notes: "",
    skippedRecurring: [],
    updatedBy: "system",
    userEdited: false,
  };
}

/**
 * Brings an existing day's recurring occurrences in line with the current
 * recurring definitions. Returns null when nothing changed.
 * - adds missing occurrences (unless the user removed them from that day)
 * - updates title/time/owner of occurrences whose definition changed
 * - removes occurrences whose recurring item was deleted or no longer applies
 */
export function reconcileRecurring(day: Day, recurring: Record<string, RecurringItem>): Day | null {
  const skipped = new Set(day.skippedRecurring ?? []);
  let changed = false;
  const items: PlanItem[] = [];

  for (const item of day.items ?? []) {
    if (!item.recurringId) {
      items.push(item);
      continue;
    }
    const def = recurring[item.recurringId];
    if (!def || !recurringApplies(def, day.date)) {
      changed = true; // drop it
      continue;
    }
    const updated: PlanItem = {
      ...item,
      title: def.title,
      time: def.time ?? null,
      durationMins: def.durationMins ?? 60,
      ownerPersonId: item.claimedBy ? item.ownerPersonId : (def.ownerPersonId ?? null),
      attendees: def.attendees ?? [],
    };
    if (JSON.stringify(updated) !== JSON.stringify(item)) changed = true;
    items.push(updated);
  }

  const present = new Set(items.filter((i) => i.recurringId).map((i) => i.recurringId));
  for (const [id, r] of Object.entries(recurring)) {
    if (present.has(id) || skipped.has(id) || !recurringApplies(r, day.date)) continue;
    items.push(itemFromRecurring(id, r, day.date));
    changed = true;
  }

  if (!changed) return null;
  return { ...day, items: sortItems(items) };
}

function personName(people: Person[], id?: string | null): string {
  if (!id) return "";
  return people.find((p) => p.id === id)?.name ?? id;
}

/** Plain-text summary in the same shape as the family WhatsApp messages. */
export function formatDay(day: Day, family: Pick<Family, "people">): { title: string; body: string } {
  const dt = DateTime.fromFormat(day.date, DATE_FMT);
  const title = dt.toFormat("cccc d LLLL"); // "Wednesday 7 October"
  const lines: string[] = [];

  for (const p of family.people) {
    const pd = day.people?.[p.id];
    if (!pd) continue;
    const parts = [pd.status, pd.extras, pd.travel].map((s) => (s ?? "").trim()).filter(Boolean);
    if (parts.length) lines.push(`${p.name} – ${parts.join(" / ")}`);
  }

  const extra: string[] = [];
  if (day.tea?.trim()) extra.push(`Tea – ${day.tea.trim()}`);
  for (const item of sortItems(day.items ?? [])) {
    const who = personName(family.people, item.claimedBy || item.ownerPersonId);
    const prefix = item.time ? `${item.time} ` : "";
    extra.push(`${prefix}${item.title}${who ? ` (${who})` : ""}`);
  }
  if (day.notes?.trim()) extra.push(day.notes.trim());

  if (extra.length) {
    if (lines.length) lines.push("");
    lines.push(...extra);
  }
  return { title, body: lines.length ? lines.join("\n") : "Nothing planned yet." };
}

/** Google Calendar event ids must use base32hex chars (0-9, a-v); hex is a subset. */
export function calendarEventId(familyId: string, date: string, itemId: string): string {
  return "dfp" + createHash("sha1").update(`${familyId}|${date}|${itemId}`).digest("hex");
}

export interface CalendarDiff {
  upserts: PlanItem[];
  deletes: string[]; // item ids
}

const calendarRelevant = (i: PlanItem) =>
  JSON.stringify([i.title, i.time, i.durationMins, i.ownerPersonId, i.claimedBy, i.notes, i.attendees]);

export function diffCalendar(before: Day | undefined, after: Day | undefined): CalendarDiff {
  const beforeTimed = new Map((before?.items ?? []).filter((i) => i.time).map((i) => [i.id, i]));
  const afterTimed = new Map((after?.items ?? []).filter((i) => i.time).map((i) => [i.id, i]));
  const upserts: PlanItem[] = [];
  const deletes: string[] = [];
  for (const [id, item] of afterTimed) {
    const prev = beforeTimed.get(id);
    if (!prev || calendarRelevant(prev) !== calendarRelevant(item)) upserts.push(item);
  }
  for (const id of beforeTimed.keys()) if (!afterTimed.has(id)) deletes.push(id);
  return { upserts, deletes };
}

/** Whether a user edit changed anything worth notifying the family about. */
export function meaningfulChange(before: Day | undefined, after: Day | undefined): boolean {
  if (!before || !after) return false;
  const strip = (d: Day) => JSON.stringify([d.people, d.tea, d.items, d.notes]);
  return strip(before) !== strip(after);
}

export function shouldSendMorning(
  family: Pick<Family, "reminderTime" | "reminderDays" | "lastMorningSent">,
  now: DateTime,
): boolean {
  const today = now.toFormat(DATE_FMT);
  if (family.lastMorningSent === today) return false;
  const days = family.reminderDays?.length ? family.reminderDays : [1, 2, 3, 4, 5, 6, 7];
  if (!days.includes(now.weekday)) return false;
  const [h, m] = (family.reminderTime || "07:00").split(":").map(Number);
  const due = now.set({ hour: h, minute: m, second: 0, millisecond: 0 });
  // Send within a 90 minute window after the due time (covers scheduler gaps
  // without sending a stale "morning" message in the afternoon).
  return now >= due && now.diff(due, "minutes").minutes < 90;
}
