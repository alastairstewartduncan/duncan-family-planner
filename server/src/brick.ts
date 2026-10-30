// Brick: phone-free time for the family. Phones lock everything except essential
// apps until someone taps their phone on a registered NFC "brick" tag. The
// phones do the locking themselves (so it works offline); the server keeps the
// shared rules, the tag list and what everyone's phone is doing.
import { pbkdf2Sync, randomBytes, randomUUID, scryptSync, timingSafeEqual } from "node:crypto";
import { DateTime } from "luxon";
import { HttpError, Store } from "./store";

export type TagKind = "brick" | "emergency";

export interface BrickTag {
  id: string; // also written on the tag itself
  kind: TagKind;
  name: string; // "Kitchen", "Hall cupboard"
  hash: string; // sha256(uidHex + ":" + secret) — the secret never leaves the tag and the phone that wrote it
  createdAt: string;
  createdBy: string;
  lastUsedAt: string | null;
  lastUsedBy: string | null;
  spent: boolean; // emergency only: used, and waiting for a guardian to re-arm it
}

export interface BrickSettings {
  guardians: string[]; // person ids who can change the rules ([] = anyone, until someone is chosen)
  emergencyMinutes: number; // how long an emergency tap unlocks a phone for
  rearmAfterEmergency: boolean; // an emergency tag stops working once used, until a guardian re-arms it
  maxBrickMinutes: number; // a phone someone bricked themselves unlocks by itself after this long (0 = no limit)
}

export interface BrickSchedule {
  id: string;
  label: string; // "School nights"
  personIds: string[];
  days: number[]; // ISO weekday the lock STARTS on (1 Mon .. 7 Sun)
  start: string; // "HH:mm"
  end: string; // "HH:mm" — earlier than start means it runs past midnight
  active: boolean;
}

export interface FamilyFocus {
  active: boolean;
  personIds: string[];
  startedBy: string;
  startedAt: string;
  until: string | null; // ISO; null = until a guardian ends it
}

export interface BrickPass {
  until: string; // ISO
  grantedBy: string;
}

export interface PhoneStatus {
  locked: boolean;
  reason: string; // "manual" | "focus" | "schedule" | ""
  until: string | null;
  since: string | null;
  serviceOn: boolean; // the app blocker (accessibility service) is switched on
  adminOn: boolean; // uninstall protection is on
  nfcOn: boolean;
  appVersion: string;
  updatedAt: string; // server time of the last check-in
}

export interface BrickEvent {
  id: number;
  at: string;
  personId: string;
  type: string; // brick | unbrick | emergency | denied | session | tamper | pass | focus
  detail: string;
  minutes: number; // for "session": how long the phone was bricked
}

const DEFAULT_SETTINGS: BrickSettings = { guardians: [], emergencyMinutes: 60, rearmAfterEmergency: true, maxBrickMinutes: 0 };
const HHMM = /^([01]\d|2[0-3]):[0-5]\d$/;
const HASH = /^[0-9a-f]{64}$/;
const EVENT_TYPES = new Set(["brick", "unbrick", "emergency", "denied", "session", "tamper", "recovery"]);
// A recovery code is checked on the phone itself (it must work offline), so a copy of its hash sits on every phone.
// That is why it has to be long and mixed: 8 to 32 letters and digits with at least one of each.
const RECOVERY_CODE = /^(?=.*[A-Za-z])(?=.*\d)[A-Za-z0-9]{8,32}$/;
const RECOVERY_ITERATIONS = 200_000;
export interface RecoveryHash { salt: string; iterations: number; hash: string }
const PARENT_PIN = /^\d{6,8}$/;
const PACKAGE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/;
export interface InstalledApp { pkg: string; label: string }
const PARENT_PIN_KEY = "brickParentPin";
/** Wrong parent PINs from one person before they have to wait. */
const PARENT_PIN_TRIES = 5;
const PARENT_PIN_WAIT_MS = 5 * 60_000;

export class BrickStore {
  constructor(private store: Store) {
    store.db.exec(`
      CREATE TABLE IF NOT EXISTS brick_tags (id TEXT PRIMARY KEY, json TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS brick_status (person_id TEXT PRIMARY KEY, json TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS brick_events (
        id INTEGER PRIMARY KEY AUTOINCREMENT, at TEXT NOT NULL, person_id TEXT NOT NULL,
        type TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '', minutes INTEGER NOT NULL DEFAULT 0);
      CREATE INDEX IF NOT EXISTS brick_events_at ON brick_events (at);
    `);
  }

  private kv<T>(key: string, fallback: T): T {
    const row = this.store.db.prepare("SELECT value FROM kv WHERE key = ?").get(key) as { value: string } | undefined;
    return row ? { ...fallback, ...(JSON.parse(row.value) as T) } : fallback;
  }

  private setKv(key: string, value: unknown) {
    this.store.db.prepare("INSERT INTO kv (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")
      .run(key, JSON.stringify(value));
    this.store.touch();
  }

  // ---- Who's in charge -------------------------------------------------------------

  settings(): BrickSettings {
    return this.kv("brickSettings", DEFAULT_SETTINGS);
  }

  isGuardian(personId: string): boolean {
    const g = this.settings().guardians;
    return g.length === 0 || g.includes(personId);
  }

  requireGuardian(personId: string) {
    if (!this.isGuardian(personId)) throw new HttpError(403, "Only a parent can change that.");
  }

  saveSettings(input: Partial<BrickSettings>): BrickSettings {
    const people = this.store.family().people.map((p) => p.id);
    const next = { ...this.settings() };
    if (input.guardians !== undefined) {
      const g = [...new Set((input.guardians as unknown[]).map(String))].filter((id) => people.includes(id));
      if (!g.length) throw new HttpError(400, "Choose at least one parent.");
      next.guardians = g;
    }
    if (input.emergencyMinutes !== undefined) {
      next.emergencyMinutes = Math.min(Math.max(Math.round(Number(input.emergencyMinutes) || 60), 5), 24 * 60);
    }
    if (input.rearmAfterEmergency !== undefined) next.rearmAfterEmergency = !!input.rearmAfterEmergency;
    if (input.maxBrickMinutes !== undefined) {
      const m = Math.round(Number(input.maxBrickMinutes) || 0);
      if (m !== 0 && (m < 15 || m > 24 * 60)) throw new HttpError(400, "A brick can be limited to between 15 minutes and a day, or not limited at all.");
      next.maxBrickMinutes = m;
    }
    this.setKv("brickSettings", next);
    return next;
  }

  // ---- Recovery code ---------------------------------------------------------------
  // One code, set by a parent, that unlocks any phone even with no signal. The server keeps only a
  // salted hash and sends that to the phones, which check what is typed on their own.

  recovery(): RecoveryHash | null {
    const row = this.store.db.prepare("SELECT value FROM kv WHERE key = ?").get("brickRecovery") as { value: string } | undefined;
    if (!row) return null;
    const r = JSON.parse(row.value) as RecoveryHash;
    return { salt: r.salt, iterations: r.iterations, hash: r.hash };
  }

  hasRecovery(): boolean {
    return this.recovery() !== null;
  }

  setRecoveryCode(personId: string, code: string) {
    if (!RECOVERY_CODE.test(code)) {
      throw new HttpError(400, "The recovery code must be 8 to 32 letters and digits, with at least one of each.");
    }
    const salt = randomBytes(16);
    const hash = pbkdf2Sync(code, salt, RECOVERY_ITERATIONS, 32, "sha256");
    this.setKv("brickRecovery", { salt: salt.toString("hex"), iterations: RECOVERY_ITERATIONS, hash: hash.toString("hex") });
    this.log(personId, "recovery-set", "");
  }

  // ---- Parent PIN ------------------------------------------------------------------
  // A second PIN, separate from the family sign-in PIN that every child also knows.
  // It guards changes a child's phone could otherwise make for itself, such as
  // which apps stay open while bricked.

  private parentPinFails = new Map<string, { count: number; until: number }>();

  hasParentPin(): boolean {
    return !!this.store.db.prepare("SELECT 1 FROM kv WHERE key = ?").get(PARENT_PIN_KEY);
  }

  setParentPin(personId: string, pin: string) {
    if (!PARENT_PIN.test(pin)) throw new HttpError(400, "The parent PIN must be 6 to 8 digits.");
    if (this.store.checkPin(pin)) throw new HttpError(400, "Choose a parent PIN that is different from the family PIN.");
    const salt = randomBytes(16);
    const hash = scryptSync(pin, salt, 32);
    this.store.db.prepare("INSERT INTO kv (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")
      .run(PARENT_PIN_KEY, `${salt.toString("hex")}:${hash.toString("hex")}`);
    this.parentPinFails.clear();
    this.log(personId, "parent-pin-set", "");
    this.store.touch();
  }

  /** True when the PIN is right. Five wrong guesses make that person wait five minutes. */
  checkParentPin(personId: string, pin: string): boolean {
    const stored = this.store.db.prepare("SELECT value FROM kv WHERE key = ?").get(PARENT_PIN_KEY) as { value: string } | undefined;
    if (!stored) throw new HttpError(409, "No parent PIN has been set yet. Ask a parent to set one.");
    const now = Date.now();
    const f = this.parentPinFails.get(personId);
    if (f && f.until > now) throw new HttpError(429, "Too many wrong parent PINs. Try again in a few minutes.");
    const [salt, hash] = stored.value.split(":").map((h) => Buffer.from(h, "hex"));
    const candidate = scryptSync(PARENT_PIN.test(pin) ? pin : "", salt, 32);
    if (PARENT_PIN.test(pin) && candidate.length === hash.length && timingSafeEqual(candidate, hash)) {
      this.parentPinFails.delete(personId);
      return true;
    }
    const count = (f?.count ?? 0) + 1;
    const locked = count >= PARENT_PIN_TRIES;
    this.parentPinFails.set(personId, { count: locked ? 0 : count, until: locked ? now + PARENT_PIN_WAIT_MS : 0 });
    this.log(personId, "pin-wrong", locked ? "locked out for 5 minutes" : "");
    return false;
  }

  /** A child opened the essential-apps picker with the parent PIN. */
  noteAppsUnlocked(personId: string) {
    this.log(personId, "apps-unlocked", "");
  }

  // ---- Essential apps, chosen from the server PC ------------------------------------
  // Each phone reports which apps it has. A parent can then pick, from the PC, which of
  // them stay open while that person is bricked. A person with no list here simply keeps
  // choosing on their own phone.

  managedApps(): Record<string, string[]> {
    return this.kv<Record<string, string[]>>("brickManagedApps", {});
  }

  installedApps(): Record<string, InstalledApp[]> {
    return this.kv<Record<string, InstalledApp[]>>("brickInstalledApps", {});
  }

  /** The phone lists the apps it has. Unchanged lists are ignored so phones don't refetch for nothing. */
  reportInstalledApps(personId: string, body: any): { ok: true } {
    const raw = Array.isArray(body?.apps) ? (body.apps as any[]) : [];
    const seen = new Set<string>();
    const apps: InstalledApp[] = [];
    for (const a of raw) {
      const pkg = String(a?.pkg ?? "");
      if (!PACKAGE.test(pkg) || pkg.length > 200 || seen.has(pkg)) continue;
      seen.add(pkg);
      apps.push({ pkg, label: String(a?.label ?? pkg).slice(0, 80) });
      if (apps.length >= 600) break;
    }
    apps.sort((x, y) => x.label.toLowerCase().localeCompare(y.label.toLowerCase()));
    const all = this.installedApps();
    if (JSON.stringify(all[personId]) === JSON.stringify(apps)) return { ok: true };
    all[personId] = apps;
    this.store.db.prepare("INSERT INTO kv (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")
      .run("brickInstalledApps", JSON.stringify(all));
    return { ok: true };
  }

  /** allowed = null hands the choice back to the person's own phone. */
  setManagedApps(personId: string, allowed: unknown): { personId: string; allowed: string[] | null } {
    if (!this.store.family().people.some((p) => p.id === personId)) throw new HttpError(404, "Unknown person.");
    const all = this.managedApps();
    if (allowed === null || allowed === undefined) {
      delete all[personId];
      this.setKv("brickManagedApps", all);
      this.log(personId, "apps-managed", "back on their own phone");
      return { personId, allowed: null };
    }
    if (!Array.isArray(allowed)) throw new HttpError(400, "allowed must be a list of apps.");
    const list = [...new Set((allowed as unknown[]).map(String))].filter((pkg) => PACKAGE.test(pkg) && pkg.length <= 200).slice(0, 600);
    all[personId] = list;
    this.setKv("brickManagedApps", all);
    this.log(personId, "apps-managed", `${list.length} app${list.length === 1 ? "" : "s"} set by a parent`);
    return { personId, allowed: list };
  }

  /** What the tray app shows: every person, the apps their phone reported, and what is allowed. */
  adminApps() {
    const managed = this.managedApps();
    const installed = this.installedApps();
    return {
      people: this.store.family().people.map((p) => ({
        id: p.id,
        name: p.name,
        managed: managed[p.id] !== undefined,
        allowed: managed[p.id] ?? [],
        installed: installed[p.id] ?? [],
      })),
    };
  }

  /** Who a change made from the server PC is recorded against: the first parent. */
  adminPerson(): string {
    return this.settings().guardians[0] ?? this.store.family().people[0]?.id ?? "server";
  }

  // ---- Tags ------------------------------------------------------------------------

  tags(): BrickTag[] {
    const rows = this.store.db.prepare("SELECT json FROM brick_tags").all() as { json: string }[];
    return rows.map((r) => JSON.parse(r.json) as BrickTag).sort((a, b) => a.kind.localeCompare(b.kind) || a.createdAt.localeCompare(b.createdAt));
  }

  private tag(id: string): BrickTag {
    const row = this.store.db.prepare("SELECT json FROM brick_tags WHERE id = ?").get(id) as { json: string } | undefined;
    if (!row) throw new HttpError(404, "That tag isn't registered.");
    return JSON.parse(row.json) as BrickTag;
  }

  private writeTag(t: BrickTag) {
    this.store.db.prepare("INSERT INTO brick_tags (id, json) VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET json = excluded.json")
      .run(t.id, JSON.stringify(t));
  }

  /**
   * Registers a tag. There can only ever be one emergency tag: adding another
   * is refused unless `replace` is set, which retires the old one for good.
   */
  addTag(body: any, personId: string): BrickTag {
    const kind: TagKind = body?.kind === "emergency" ? "emergency" : "brick";
    const id = String(body?.id ?? "");
    const hash = String(body?.hash ?? "").toLowerCase();
    if (!/^[A-Za-z0-9_-]{8,64}$/.test(id) || !HASH.test(hash)) throw new HttpError(400, "That tag couldn't be read properly. Try again.");
    const all = this.tags();
    if (all.some((t) => t.id === id || t.hash === hash)) throw new HttpError(409, "That tag is already registered.");
    const tag: BrickTag = {
      id, kind, hash,
      name: String(body?.name ?? "").trim().slice(0, 40) || (kind === "emergency" ? "Emergency" : "Brick"),
      createdAt: new Date().toISOString(), createdBy: personId,
      lastUsedAt: null, lastUsedBy: null, spent: false,
    };
    this.store.db.exec("BEGIN IMMEDIATE");
    try {
      if (kind === "emergency") {
        const existing = all.filter((t) => t.kind === "emergency");
        if (existing.length && !body?.replace) {
          throw new HttpError(409, "There's already an emergency tag. Replace it to retire the old one.");
        }
        for (const t of existing) this.store.db.prepare("DELETE FROM brick_tags WHERE id = ?").run(t.id);
      }
      this.writeTag(tag);
      this.store.db.exec("COMMIT");
    } catch (e) {
      this.store.db.exec("ROLLBACK");
      throw e;
    }
    this.log(personId, kind === "emergency" ? "emergency-sealed" : "tag-added", tag.name);
    this.store.touch();
    return tag;
  }

  renameTag(id: string, name: string): BrickTag {
    const t = { ...this.tag(id), name: name.trim().slice(0, 40) || this.tag(id).name };
    this.writeTag(t);
    this.store.touch();
    return t;
  }

  deleteTag(id: string) {
    this.tag(id);
    this.store.db.prepare("DELETE FROM brick_tags WHERE id = ?").run(id);
    this.store.touch();
  }

  rearm(id: string, personId: string): BrickTag {
    const t = { ...this.tag(id), spent: false };
    this.writeTag(t);
    this.log(personId, "rearmed", t.name);
    this.store.touch();
    return t;
  }

  // ---- Schedules, focus and passes ---------------------------------------------------

  schedules(): BrickSchedule[] {
    return this.kv<{ list: BrickSchedule[] }>("brickSchedules", { list: [] }).list;
  }

  saveSchedules(input: unknown): BrickSchedule[] {
    const people = this.store.family().people.map((p) => p.id);
    const list = (Array.isArray(input) ? input : []).map((s: any): BrickSchedule => {
      const days = [...new Set(((s?.days ?? []) as unknown[]).map(Number).filter((d) => d >= 1 && d <= 7))].sort();
      const personIds = [...new Set(((s?.personIds ?? []) as unknown[]).map(String))].filter((id) => people.includes(id));
      if (!HHMM.test(s?.start) || !HHMM.test(s?.end) || s.start === s.end) throw new HttpError(400, "Each schedule needs a start and end time.");
      if (!days.length || !personIds.length) throw new HttpError(400, "Each schedule needs at least one day and one person.");
      return {
        id: /^[A-Za-z0-9_-]{1,64}$/.test(s?.id ?? "") ? s.id : randomUUID(),
        label: String(s?.label ?? "").trim().slice(0, 40) || "Phone-free time",
        personIds, days, start: s.start, end: s.end, active: s?.active !== false,
      };
    });
    this.setKv("brickSchedules", { list });
    return list;
  }

  focus(): FamilyFocus | null {
    const f = this.kv<FamilyFocus | { active: false }>("brickFocus", { active: false });
    if (!f.active) return null;
    const ff = f as FamilyFocus;
    if (ff.until && ff.until <= new Date().toISOString()) return null;
    return ff;
  }

  startFocus(body: any, personId: string): FamilyFocus {
    const people = this.store.family().people.map((p) => p.id);
    const chosen = Array.isArray(body?.personIds) ? (body.personIds as unknown[]).map(String).filter((id) => people.includes(id)) : people;
    if (!chosen.length) throw new HttpError(400, "Choose who's taking part.");
    let until: string | null = null;
    if (body?.minutes !== undefined && body?.minutes !== null) {
      const mins = Math.round(Number(body.minutes));
      if (!(mins >= 5 && mins <= 24 * 60)) throw new HttpError(400, "Family Focus can last between 5 minutes and a day.");
      until = new Date(Date.now() + mins * 60_000).toISOString();
    }
    const f: FamilyFocus = { active: true, personIds: chosen, startedBy: personId, startedAt: new Date().toISOString(), until };
    this.setKv("brickFocus", f);
    this.log(personId, "focus", until ? `until ${until}` : "until ended");
    return f;
  }

  endFocus(personId: string) {
    this.setKv("brickFocus", { active: false });
    this.log(personId, "focus-ended", "");
  }

  passes(): Record<string, BrickPass> {
    const all = this.kv<Record<string, BrickPass>>("brickPasses", {});
    const now = new Date().toISOString();
    return Object.fromEntries(Object.entries(all).filter(([, p]) => p.until > now));
  }

  grantPass(body: any, grantedBy: string): BrickPass | null {
    const target = String(body?.personId ?? "");
    if (!this.store.family().people.some((p) => p.id === target)) throw new HttpError(400, "Who is the pass for?");
    const passes = this.passes();
    const mins = Math.round(Number(body?.minutes ?? 0));
    if (mins <= 0) {
      delete passes[target];
      this.setKv("brickPasses", passes);
      this.log(grantedBy, "pass-cancelled", target);
      return null;
    }
    if (mins > 24 * 60) throw new HttpError(400, "A pass can last up to a day.");
    const pass = { until: new Date(Date.now() + mins * 60_000).toISOString(), grantedBy };
    passes[target] = pass;
    this.setKv("brickPasses", passes);
    this.log(grantedBy, "pass", `${target} for ${mins} min`);
    return pass;
  }

  // ---- Phones checking in --------------------------------------------------------------

  statuses(): Record<string, PhoneStatus> {
    const rows = this.store.db.prepare("SELECT person_id, json FROM brick_status").all() as { person_id: string; json: string }[];
    return Object.fromEntries(rows.map((r) => [r.person_id, JSON.parse(r.json) as PhoneStatus]));
  }

  /** A phone reports its state. Only real changes bump the revision, so heartbeats don't make every phone refetch. */
  reportStatus(personId: string, body: any): PhoneStatus {
    const before = this.statuses()[personId];
    const s: PhoneStatus = {
      locked: !!body?.locked,
      reason: ["manual", "focus", "schedule"].includes(body?.reason) ? body.reason : "",
      until: typeof body?.until === "string" ? body.until : null,
      since: typeof body?.since === "string" ? body.since : null,
      serviceOn: !!body?.serviceOn,
      adminOn: !!body?.adminOn,
      nfcOn: body?.nfcOn !== false,
      appVersion: String(body?.appVersion ?? "").slice(0, 32),
      updatedAt: new Date().toISOString(),
    };
    this.store.db.prepare("INSERT INTO brick_status (person_id, json) VALUES (?, ?) ON CONFLICT(person_id) DO UPDATE SET json = excluded.json")
      .run(personId, JSON.stringify(s));
    const changed = !before || before.locked !== s.locked || before.reason !== s.reason ||
      before.serviceOn !== s.serviceOn || before.adminOn !== s.adminOn;
    if (before && before.serviceOn && !s.serviceOn) this.log(personId, "tamper", "App blocker was switched off");
    if (changed) this.store.touch();
    return s;
  }

  /** Something happened on a phone: it was bricked, unbricked, an emergency tag was used… */
  reportEvent(personId: string, body: any): BrickEvent {
    const type = String(body?.type ?? "");
    if (!EVENT_TYPES.has(type)) throw new HttpError(400, "Unknown event.");
    let detail = String(body?.detail ?? "").slice(0, 200);
    if (body?.tagId) {
      const tag = this.tags().find((t) => t.id === String(body.tagId));
      if (tag) {
        const used: BrickTag = { ...tag, lastUsedAt: new Date().toISOString(), lastUsedBy: personId };
        if (type === "emergency" && tag.kind === "emergency" && this.settings().rearmAfterEmergency) used.spent = true;
        this.writeTag(used);
        detail = detail || tag.name;
      }
    }
    const minutes = type === "session" ? Math.min(Math.max(Math.round(Number(body?.minutes) || 0), 0), 24 * 60) : 0;
    const at = typeof body?.at === "string" && DateTime.fromISO(body.at).isValid ? DateTime.fromISO(body.at).toUTC().toISO()! : new Date().toISOString();
    const ev = this.log(personId, type, detail, minutes, at);
    if (type !== "session") this.store.touch();
    return ev;
  }

  private log(personId: string, type: string, detail: string, minutes = 0, at = new Date().toISOString()): BrickEvent {
    const r = this.store.db.prepare("INSERT INTO brick_events (at, person_id, type, detail, minutes) VALUES (?, ?, ?, ?, ?)")
      .run(at, personId, type, detail, minutes);
    // Keep a year of history.
    this.store.db.prepare("DELETE FROM brick_events WHERE at < ?").run(DateTime.now().minus({ days: 366 }).toUTC().toISO());
    return { id: Number(r.lastInsertRowid), at, personId, type, detail, minutes };
  }

  events(limit = 40): BrickEvent[] {
    const rows = this.store.db.prepare(
      "SELECT id, at, person_id, type, detail, minutes FROM brick_events WHERE type != 'session' ORDER BY id DESC LIMIT ?",
    ).all(limit) as { id: number; at: string; person_id: string; type: string; detail: string; minutes: number }[];
    return rows.map((r) => ({ id: r.id, at: r.at, personId: r.person_id, type: r.type, detail: r.detail, minutes: r.minutes }));
  }

  /** One person's recent phone-free sessions (newest first): when each ended and how long it lasted. */
  sessions(personId: string, days = 14, limit = 50): { at: string; minutes: number; detail: string }[] {
    const since = DateTime.now().minus({ days }).toUTC().toISO();
    const rows = this.store.db.prepare(
      "SELECT at, minutes, detail FROM brick_events WHERE type = 'session' AND person_id = ? AND at >= ? ORDER BY at DESC LIMIT ?",
    ).all(personId, since, limit) as { at: string; minutes: number; detail: string }[];
    return rows.map((r) => ({ at: r.at, minutes: r.minutes, detail: r.detail }));
  }

  /** Phone-free minutes per person: today, this week (Mon–Sun) and a day-by-day streak. */
  stats() {
    const zone = this.store.family().timezone;
    const now = DateTime.now().setZone(zone);
    const today = now.startOf("day");
    const weekStart = now.startOf("week");
    const from = today.minus({ days: 60 });
    const rows = this.store.db.prepare(
      "SELECT person_id, at, minutes FROM brick_events WHERE type = 'session' AND at >= ?",
    ).all(from.toUTC().toISO()) as { person_id: string; at: string; minutes: number }[];
    const out: Record<string, { today: number; week: number; streakDays: number; byDay: Record<string, number> }> = {};
    for (const p of this.store.family().people) out[p.id] = { today: 0, week: 0, streakDays: 0, byDay: {} };
    for (const r of rows) {
      const s = out[r.person_id];
      if (!s) continue;
      const at = DateTime.fromISO(r.at).setZone(zone);
      const key = at.toFormat("yyyy-MM-dd");
      s.byDay[key] = (s.byDay[key] ?? 0) + r.minutes;
      if (at >= today) s.today += r.minutes;
      if (at >= weekStart) s.week += r.minutes;
    }
    // A streak counts days with at least an hour phone-free, ending today or yesterday.
    for (const s of Object.values(out)) {
      let d = (s.byDay[today.toFormat("yyyy-MM-dd")] ?? 0) >= 60 ? today : today.minus({ days: 1 });
      while ((s.byDay[d.toFormat("yyyy-MM-dd")] ?? 0) >= 60) {
        s.streakDays++;
        d = d.minus({ days: 1 });
      }
      // Only the last 7 days go to the phones.
      s.byDay = Object.fromEntries(Array.from({ length: 7 }, (_, i) => today.minus({ days: 6 - i }).toFormat("yyyy-MM-dd")).map((k) => [k, s.byDay[k] ?? 0]));
    }
    return out;
  }

  /** Everything a phone needs, in one call. Tags carry hashes only, never secrets. */
  snapshot(personId: string) {
    return {
      settings: this.settings(),
      isGuardian: this.isGuardian(personId),
      hasParentPin: this.hasParentPin(),
      recovery: this.recovery(),
      managedApps: this.managedApps()[personId] ?? null,
      tags: this.tags(),
      schedules: this.schedules(),
      focus: this.focus(),
      passes: this.passes(),
      statuses: this.statuses(),
      events: this.events(),
      stats: this.stats(),
      mySessions: this.sessions(personId),
      serverTime: new Date().toISOString(),
    };
  }
}
