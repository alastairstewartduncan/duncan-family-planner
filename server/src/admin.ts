// Status for the Windows tray app. Served only to programs running on the server PC itself.
import fs from "node:fs";
import path from "node:path";
import { BrickStore } from "./brick";
import { Store } from "./store";

export interface ServerInfo {
  startedAt: number; // ms since epoch
  port: number;
  https: boolean;
  dataDir: string;
}

/**
 * True only for a connection made from this PC to itself: not through a proxy, not from a
 * web page (browsers add an Origin header to cross-site requests), and addressed to
 * localhost (which blocks DNS-rebinding tricks).
 */
export function isLocalRequest(
  remoteAddress: string | undefined,
  forwarded: string | string[] | undefined,
  host?: string,
  origin?: string | string[],
): boolean {
  if (forwarded || origin) return false;
  if (host !== undefined && !/^(localhost|127\.0\.0\.1|\[::1\])(:\d+)?$/i.test(host)) return false;
  const a = remoteAddress ?? "";
  return a === "127.0.0.1" || a === "::1" || a === "::ffff:127.0.0.1";
}

export interface LoginState {
  pausedUntil: number; // ms since epoch, 0 = not paused
  recentFailures: number; // wrong PINs in the last 15 minutes
}

/** Times go out as epoch milliseconds: PowerShell would turn ISO date strings into DateTime objects of uncertain kind. */
const ms = (iso: string | null | undefined): number => (iso ? Date.parse(iso) || 0 : 0);

export function buildStatus(store: Store, brick: BrickStore, info: ServerInfo, login: LoginState) {
  const family = store.family();
  const stats = brick.stats();
  const statuses = brick.statuses();
  const seen = store.db.prepare("SELECT person_id, COUNT(*) AS n, MAX(last_used) AS last FROM tokens GROUP BY person_id").all() as
    { person_id: string; n: number; last: string | null }[];
  const seenBy = new Map(seen.map((r) => [r.person_id, r]));

  const people = family.people.map((p) => {
    const st = statuses[p.id];
    const s = stats[p.id];
    return {
      id: p.id,
      name: p.name,
      parent: brick.settings().guardians.includes(p.id),
      signedInPhones: seenBy.get(p.id)?.n ?? 0,
      lastSeenMs: ms(seenBy.get(p.id)?.last),
      phone: st
        ? { locked: st.locked, reason: st.reason, serviceOn: st.serviceOn, adminOn: st.adminOn, appVersion: st.appVersion, lastCheckInMs: ms(st.updatedAt) }
        : null,
      phoneFreeTodayMinutes: s?.today ?? 0,
      phoneFreeWeekMinutes: s?.week ?? 0,
      streakDays: s?.streakDays ?? 0,
    };
  });

  const names = new Map(family.people.map((p) => [p.id, p.name]));
  const events = brick.events(12).map((e) => ({ atMs: ms(e.at), person: names.get(e.personId) ?? e.personId, type: e.type, detail: e.detail }));

  let databaseBytes = 0;
  let latestBackup: { file: string; atMs: number } | null = null;
  let backupCount = 0;
  try {
    databaseBytes = fs.statSync(path.join(info.dataDir, "planner.db")).size;
  } catch { /* not there yet */ }
  try {
    const dir = path.join(info.dataDir, "backups");
    const files = fs.readdirSync(dir).map((f) => ({ file: f, t: fs.statSync(path.join(dir, f)).mtimeMs })).sort((a, b) => b.t - a.t);
    backupCount = files.length;
    if (files[0]) latestBackup = { file: files[0].file, atMs: Math.round(files[0].t) };
  } catch { /* no backups yet */ }

  return {
    ok: true,
    startedMs: info.startedAt,
    uptimeSeconds: Math.round((Date.now() - info.startedAt) / 1000),
    port: info.port,
    https: info.https,
    node: process.version,
    nowMs: Date.now(),
    familyName: family.name ?? "",
    people,
    events,
    focus: (() => {
      const f = brick.focus();
      return f ? { active: true, untilMs: ms(f.until), personIds: f.personIds } : { active: false, untilMs: 0, personIds: [] as string[] };
    })(),
    signIn: {
      pausedUntilMs: login.pausedUntil > Date.now() ? login.pausedUntil : 0,
      recentFailures: login.recentFailures,
    },
    database: { bytes: databaseBytes, backups: backupCount, latestBackup },
    parentPinSet: brick.hasParentPin(),
  };
}
