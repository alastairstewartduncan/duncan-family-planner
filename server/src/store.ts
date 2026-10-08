// SQLite-backed storage and the planning rules that run on every change.
import { randomBytes, randomUUID, scryptSync, timingSafeEqual } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { DatabaseSync } from "node:sqlite";
import { DateTime } from "luxon";
import { DATE_FMT, buildNewDay, reconcileRecurring, sortItems } from "./plan";
import { Day, Family, PersonDay, PlanItem, RecurringItem, WeekdayDefaults } from "./types";

export const DAYS_AHEAD = 14;

export interface ShoppingItem {
  id: string;
  text: string;
  done: boolean;
  addedBy: string | null;
  createdAt: string;
}

/** Called after a day is written, with the old and new versions (for calendar sync). */
export type DayListener = (date: string, before: Day | undefined, after: Day | undefined) => void;

const DEFAULT_FAMILY: Family = {
  name: "Family",
  timezone: "Europe/London",
  reminderTime: "07:00",
  reminderDays: [1, 2, 3, 4, 5, 6, 7],
  calendarId: "",
  people: [],
};

export class HttpError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

export class Store {
  readonly db: DatabaseSync;
  private rev = Date.now(); // bumps on every write; clients poll it to know when to refresh
  private listeners: DayListener[] = [];

  constructor(file: string) {
    this.db = new DatabaseSync(file);
    this.db.exec(`
      PRAGMA journal_mode = WAL;
      PRAGMA foreign_keys = ON;
      CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS days (date TEXT PRIMARY KEY, json TEXT NOT NULL, updated_at TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS recurring (id TEXT PRIMARY KEY, json TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS defaults (weekday INTEGER PRIMARY KEY, json TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS shopping (
        id TEXT PRIMARY KEY, text TEXT NOT NULL, done INTEGER NOT NULL DEFAULT 0,
        added_by TEXT, created_at TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS tokens (
        token TEXT PRIMARY KEY, person_id TEXT NOT NULL, created_at TEXT NOT NULL, last_used TEXT NOT NULL);
    `);
  }

  onDayChanged(l: DayListener) {
    this.listeners.push(l);
  }

  revision() {
    return this.rev;
  }

  private bump() {
    this.rev = Math.max(this.rev + 1, Date.now());
  }

  private tx<T>(fn: () => T): T {
    this.db.exec("BEGIN IMMEDIATE");
    try {
      const out = fn();
      this.db.exec("COMMIT");
      return out;
    } catch (e) {
      this.db.exec("ROLLBACK");
      throw e;
    }
  }

  private kvGet(key: string): string | undefined {
    const row = this.db.prepare("SELECT value FROM kv WHERE key = ?").get(key) as { value: string } | undefined;
    return row?.value;
  }

  private kvSet(key: string, value: string) {
    this.db.prepare("INSERT INTO kv (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value").run(key, value);
  }

  // ---- Family ---------------------------------------------------------------

  family(): Family {
    const raw = this.kvGet("family");
    return raw ? { ...DEFAULT_FAMILY, ...(JSON.parse(raw) as Partial<Family>) } : DEFAULT_FAMILY;
  }

  saveFamily(update: Partial<Family>): Family {
    const next = { ...this.family(), ...update };
    this.kvSet("family", JSON.stringify(next));
    this.bump();
    return next;
  }

  today(): string {
    return DateTime.now().setZone(this.family().timezone).toFormat(DATE_FMT);
  }

  // ---- PIN & tokens -----------------------------------------------------------

  setPin(pin: string) {
    const salt = randomBytes(16);
    const hash = scryptSync(pin, salt, 32);
    this.kvSet("pin", `${salt.toString("hex")}:${hash.toString("hex")}`);
    // A new PIN signs everyone out.
    this.db.exec("DELETE FROM tokens");
  }

  hasPin() {
    return !!this.kvGet("pin");
  }

  checkPin(pin: string): boolean {
    const stored = this.kvGet("pin");
    if (!stored) return false;
    const [salt, hash] = stored.split(":").map((h) => Buffer.from(h, "hex"));
    const candidate = scryptSync(pin, salt, 32);
    return candidate.length === hash.length && timingSafeEqual(candidate, hash);
  }

  createToken(personId: string): string {
    const token = randomBytes(32).toString("base64url");
    const now = new Date().toISOString();
    this.db.prepare("INSERT INTO tokens (token, person_id, created_at, last_used) VALUES (?, ?, ?, ?)").run(token, personId, now, now);
    return token;
  }

  personForToken(token: string): string | undefined {
    const row = this.db.prepare("SELECT person_id FROM tokens WHERE token = ?").get(token) as { person_id: string } | undefined;
    if (row) this.db.prepare("UPDATE tokens SET last_used = ? WHERE token = ?").run(new Date().toISOString(), token);
    return row?.person_id;
  }

  revokeToken(token: string) {
    this.db.prepare("DELETE FROM tokens WHERE token = ?").run(token);
  }

  // ---- Recurring & defaults -------------------------------------------------

  recurring(): Record<string, RecurringItem> {
    const rows = this.db.prepare("SELECT id, json FROM recurring").all() as { id: string; json: string }[];
    return Object.fromEntries(rows.map((r) => [r.id, JSON.parse(r.json) as RecurringItem]));
  }

  saveRecurring(id: string | undefined, item: RecurringItem): string {
    const key = id || randomUUID();
    this.db.prepare("INSERT INTO recurring (id, json) VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET json = excluded.json")
      .run(key, JSON.stringify(item));
    this.ensureDays(this.today(), DAYS_AHEAD);
    this.bump();
    return key;
  }

  deleteRecurring(id: string) {
    this.db.prepare("DELETE FROM recurring WHERE id = ?").run(id);
    this.ensureDays(this.today(), DAYS_AHEAD);
    this.bump();
  }

  defaults(): Record<string, WeekdayDefaults> {
    const rows = this.db.prepare("SELECT weekday, json FROM defaults").all() as { weekday: number; json: string }[];
    return Object.fromEntries(rows.map((r) => [String(r.weekday), JSON.parse(r.json) as WeekdayDefaults]));
  }

  /** Saves a weekday's usual plan and applies it to upcoming days nobody has edited yet. */
  saveDefaults(weekday: number, people: Record<string, PersonDay>) {
    this.db.prepare("INSERT INTO defaults (weekday, json) VALUES (?, ?) ON CONFLICT(weekday) DO UPDATE SET json = excluded.json")
      .run(weekday, JSON.stringify({ people }));
    const start = DateTime.fromFormat(this.today(), DATE_FMT);
    for (let i = 0; i < DAYS_AHEAD; i++) {
      const dt = start.plus({ days: i });
      if (dt.weekday !== weekday) continue;
      const day = this.getDay(dt.toFormat(DATE_FMT));
      if (day && !day.userEdited) {
        const blank: PersonDay = { status: "", extras: "", travel: "" };
        const peopleDays = Object.fromEntries(this.family().people.map((p) => [p.id, people[p.id] ?? blank]));
        this.writeDay({ ...day, people: peopleDays }, "system");
      }
    }
    this.bump();
  }

  // ---- Days -------------------------------------------------------------------

  getDay(date: string): Day | undefined {
    const row = this.db.prepare("SELECT json FROM days WHERE date = ?").get(date) as { json: string } | undefined;
    return row ? (JSON.parse(row.json) as Day) : undefined;
  }

  daysBetween(from: string, to: string): Day[] {
    const rows = this.db.prepare("SELECT json FROM days WHERE date >= ? AND date <= ? ORDER BY date").all(from, to) as { json: string }[];
    return rows.map((r) => JSON.parse(r.json) as Day);
  }

  private writeDay(day: Day, by: string) {
    const before = this.getDay(day.date);
    const updatedAt = new Date().toISOString();
    const after: Day = { ...day, items: sortItems(day.items ?? []), updatedBy: by, updatedAt };
    this.db.prepare("INSERT INTO days (date, json, updated_at) VALUES (?, ?, ?) ON CONFLICT(date) DO UPDATE SET json = excluded.json, updated_at = excluded.updated_at")
      .run(day.date, JSON.stringify(after), updatedAt);
    for (const l of this.listeners) {
      try {
        l(day.date, before, after);
      } catch (e) {
        console.error("day listener failed", e);
      }
    }
    return after;
  }

  /**
   * Creates missing days from today onwards (from weekday defaults and regular
   * events) and brings existing ones in line with the regular events.
   * Days before today are never created.
   */
  ensureDays(from: string, count: number): number {
    const family = this.family();
    const recurring = this.recurring();
    const defaults = this.defaults();
    const today = this.today();
    let start = DateTime.fromFormat(from, DATE_FMT);
    if (!start.isValid) throw new HttpError(400, "Dates must be yyyy-MM-dd");
    const end = start.plus({ days: Math.min(Math.max(count, 1), 62) - 1 });
    if (start.toFormat(DATE_FMT) < today) start = DateTime.fromFormat(today, DATE_FMT);
    let written = 0;
    this.tx(() => {
      for (let dt = start; dt <= end; dt = dt.plus({ days: 1 })) {
        const date = dt.toFormat(DATE_FMT);
        const existing = this.getDay(date);
        if (!existing) {
          this.writeDay(buildNewDay(date, family.people, defaults[String(dt.weekday)], recurring), "system");
          written++;
        } else {
          const updated = reconcileRecurring(existing, recurring);
          if (updated) {
            this.writeDay(updated, "system");
            written++;
          }
        }
      }
    });
    if (written) this.bump();
    return written;
  }

  saveDay(date: string, input: Partial<Day>, personId: string): Day {
    const existing = this.getDay(date);
    const day: Day = {
      date,
      people: input.people ?? existing?.people ?? {},
      tea: input.tea ?? "",
      items: (input.items ?? []).map(cleanItem),
      notes: input.notes ?? "",
      skippedRecurring: [...new Set(input.skippedRecurring ?? existing?.skippedRecurring ?? [])],
      updatedBy: personId,
      userEdited: true,
    };
    const saved = this.tx(() => this.writeDay(day, personId));
    this.bump();
    return saved;
  }

  /** Changes one item (done / claimed) without overwriting other people's edits. */
  patchItem(date: string, itemId: string, patch: Partial<Pick<PlanItem, "done" | "claimedBy">>, personId: string): Day {
    const saved = this.tx(() => {
      const day = this.getDay(date);
      if (!day) throw new HttpError(404, "Day not found");
      if (!day.items.some((i) => i.id === itemId)) throw new HttpError(404, "Item not found");
      const items = day.items.map((i) => (i.id === itemId ? { ...i, ...patch } : i));
      return this.writeDay({ ...day, items, userEdited: true }, personId);
    });
    this.bump();
    return saved;
  }

  copyPeople(fromDate: string, toDate: string, personId: string): Day {
    const source = this.getDay(fromDate);
    if (!source) throw new HttpError(404, `Nothing planned on ${fromDate}`);
    this.ensureDays(toDate, 1);
    const saved = this.tx(() => {
      const target = this.getDay(toDate) ?? buildNewDay(toDate, this.family().people, undefined, {});
      return this.writeDay({ ...target, people: source.people, userEdited: true }, personId);
    });
    this.bump();
    return saved;
  }

  // ---- Shopping -----------------------------------------------------------------

  shopping(): ShoppingItem[] {
    const rows = this.db.prepare("SELECT id, text, done, added_by, created_at FROM shopping ORDER BY created_at").all() as {
      id: string; text: string; done: number; added_by: string | null; created_at: string;
    }[];
    return rows.map((r) => ({ id: r.id, text: r.text, done: !!r.done, addedBy: r.added_by, createdAt: r.created_at }));
  }

  addShopping(text: string, personId: string) {
    const t = text.trim();
    if (!t) throw new HttpError(400, "Text is required");
    this.db.prepare("INSERT INTO shopping (id, text, done, added_by, created_at) VALUES (?, ?, 0, ?, ?)")
      .run(randomUUID(), t.slice(0, 200), personId, new Date().toISOString());
    this.bump();
  }

  setShoppingDone(id: string, done: boolean) {
    this.db.prepare("UPDATE shopping SET done = ? WHERE id = ?").run(done ? 1 : 0, id);
    this.bump();
  }

  deleteShopping(id: string) {
    this.db.prepare("DELETE FROM shopping WHERE id = ?").run(id);
    this.bump();
  }

  clearDoneShopping() {
    this.db.exec("DELETE FROM shopping WHERE done = 1");
    this.bump();
  }

  // ---- Backups --------------------------------------------------------------------

  /** Writes a consistent copy of the database and keeps the newest `keep` copies. */
  backup(dir: string, keep: number): string {
    fs.mkdirSync(dir, { recursive: true });
    const file = path.join(dir, `planner-${DateTime.now().toFormat("yyyy-MM-dd")}.db`);
    if (fs.existsSync(file)) fs.rmSync(file);
    this.db.exec(`VACUUM INTO '${file.replace(/'/g, "''")}'`);
    const old = fs.readdirSync(dir).filter((f) => /^planner-\d{4}-\d{2}-\d{2}\.db$/.test(f)).sort().reverse().slice(keep);
    for (const f of old) fs.rmSync(path.join(dir, f));
    return file;
  }
}

function cleanItem(i: Partial<PlanItem>): PlanItem {
  const time = typeof i.time === "string" && /^\d{2}:\d{2}$/.test(i.time) ? i.time : null;
  return {
    id: i.id || randomUUID(),
    title: String(i.title ?? "").trim().slice(0, 200),
    time,
    durationMins: Math.min(Math.max(Number(i.durationMins) || 60, 5), 24 * 60),
    ownerPersonId: i.ownerPersonId || null,
    attendees: Array.isArray(i.attendees) ? i.attendees.map(String) : [],
    notes: String(i.notes ?? "").slice(0, 1000),
    recurringId: i.recurringId || null,
    claimedBy: i.claimedBy || null,
    done: !!i.done,
  };
}
