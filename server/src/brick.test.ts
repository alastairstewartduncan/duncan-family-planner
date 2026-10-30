import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import fs from "node:fs";
import { AddressInfo } from "node:net";
import { createServer, Server } from "node:http";
import os from "node:os";
import path from "node:path";
import { after, before, test } from "node:test";
import { createApi } from "./api";
import { Store } from "./store";

let server: Server;
let base = "";
let store: Store;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), "dfp-brick-"));
const tokens: Record<string, string> = {};

async function call(who: string, method: string, url: string, body?: unknown) {
  const res = await fetch(base + url, {
    method,
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${tokens[who]}` },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return { status: res.status, json: (await res.json()) as any };
}

const hash = (uid: string, secret: string) => createHash("sha256").update(`${uid}:${secret}`).digest("hex");

before(async () => {
  store = new Store(path.join(dir, "test.db"));
  store.saveFamily({
    people: [
      { id: "claire", name: "Claire", colour: "#D81B60" },
      { id: "alastair", name: "Alastair", colour: "#1E88E5" },
      { id: "alfie", name: "Alfie", colour: "#43A047" },
    ],
  });
  store.setPin("2468");
  server = createServer(createApi(store));
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  for (const who of ["claire", "alastair", "alfie"]) {
    const res = await fetch(base + "/api/login", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ personId: who, pin: "2468" }),
    });
    tokens[who] = ((await res.json()) as any).token;
  }
});

after(() => {
  server.close();
  store.db.close();
  fs.rmSync(dir, { recursive: true, force: true });
});

test("parents are in charge once chosen", async () => {
  // Nobody chosen yet: anyone may set up, and is told they can.
  assert.equal((await call("alfie", "GET", "/api/brick")).json.isGuardian, true);
  const set = await call("alastair", "PUT", "/api/brick/settings", { guardians: ["claire", "alastair", "nobody"] });
  assert.equal(set.status, 200);
  assert.deepEqual(set.json.guardians, ["claire", "alastair"]);
  assert.equal((await call("alfie", "GET", "/api/brick")).json.isGuardian, false);
  assert.equal((await call("alfie", "PUT", "/api/brick/settings", { guardians: ["alfie"] })).status, 403);
  assert.equal((await call("alfie", "POST", "/api/brick/focus", {})).status, 403);
  assert.equal((await call("claire", "PUT", "/api/brick/settings", { guardians: [] })).status, 400);
});

test("bricks register, and only one emergency tag can ever exist", async () => {
  const kitchen = await call("alastair", "POST", "/api/brick/tags", { id: "kitchen-0001", kind: "brick", name: "Kitchen", hash: hash("04A1", "s1") });
  assert.equal(kitchen.status, 200);
  assert.equal((await call("alastair", "POST", "/api/brick/tags", { id: "kitchen-0002", kind: "brick", hash: hash("04A1", "s1") })).status, 409);
  assert.equal((await call("alfie", "POST", "/api/brick/tags", { id: "sneaky-0001", hash: hash("04FF", "x") })).status, 403);
  assert.equal((await call("alastair", "POST", "/api/brick/tags", { id: "bad", hash: "nope" })).status, 400);

  const e1 = await call("claire", "POST", "/api/brick/tags", { id: "emergency-01", kind: "emergency", hash: hash("04E1", "e1") });
  assert.equal(e1.status, 200);
  const e2 = await call("claire", "POST", "/api/brick/tags", { id: "emergency-02", kind: "emergency", hash: hash("04E2", "e2") });
  assert.equal(e2.status, 409);
  const replaced = await call("claire", "POST", "/api/brick/tags", { id: "emergency-02", kind: "emergency", hash: hash("04E2", "e2"), replace: true });
  assert.equal(replaced.status, 200);
  const tags = (await call("alfie", "GET", "/api/brick")).json.tags as any[];
  assert.equal(tags.filter((t) => t.kind === "emergency").length, 1);
  assert.equal(tags.find((t) => t.kind === "emergency").id, "emergency-02");
  assert.ok(tags.every((t) => !("secret" in t)));
});

test("an emergency tap is announced, and the tag is spent until re-armed", async () => {
  const v1 = (await call("alfie", "GET", "/api/version")).json.revision;
  const ev = await call("alfie", "POST", "/api/brick/events", { type: "emergency", tagId: "emergency-02" });
  assert.equal(ev.status, 200);
  const snap = (await call("claire", "GET", "/api/brick")).json;
  assert.ok(snap.events.some((e: any) => e.type === "emergency" && e.personId === "alfie"));
  const tag = snap.tags.find((t: any) => t.id === "emergency-02");
  assert.equal(tag.spent, true);
  assert.equal(tag.lastUsedBy, "alfie");
  assert.ok((await call("alfie", "GET", "/api/version")).json.revision > v1);
  assert.equal((await call("alfie", "POST", "/api/brick/tags/emergency-02/rearm")).status, 403);
  const rearmed = await call("alastair", "POST", "/api/brick/tags/emergency-02/rearm");
  assert.equal(rearmed.json.spent, false);
});

test("family focus, passes and schedules", async () => {
  const f = await call("claire", "POST", "/api/brick/focus", { minutes: 90 });
  assert.equal(f.status, 200);
  assert.deepEqual(f.json.personIds, ["claire", "alastair", "alfie"]);
  assert.ok((await call("alfie", "GET", "/api/brick")).json.focus.active);
  assert.equal((await call("claire", "POST", "/api/brick/focus", { minutes: 1 })).status, 400);
  await call("alastair", "DELETE", "/api/brick/focus");
  assert.equal((await call("alfie", "GET", "/api/brick")).json.focus, null);

  const pass = await call("claire", "POST", "/api/brick/pass", { personId: "alfie", minutes: 30 });
  assert.ok(pass.json.until > new Date().toISOString());
  assert.ok((await call("alfie", "GET", "/api/brick")).json.passes.alfie);
  await call("claire", "POST", "/api/brick/pass", { personId: "alfie", minutes: 0 });
  assert.equal((await call("alfie", "GET", "/api/brick")).json.passes.alfie, undefined);

  const sched = await call("alastair", "PUT", "/api/brick/schedules", [
    { label: "School nights", personIds: ["alfie"], days: [7, 1, 2, 3, 4], start: "21:30", end: "07:00" },
  ]);
  assert.equal(sched.status, 200);
  assert.deepEqual(sched.json[0].days, [1, 2, 3, 4, 7]);
  assert.equal((await call("alastair", "PUT", "/api/brick/schedules", [{ personIds: ["alfie"], days: [1], start: "25:00", end: "07:00" }])).status, 400);
});

test("phones check in; heartbeats don't churn, switching the blocker off is flagged", async () => {
  await call("alfie", "POST", "/api/brick/status", { locked: true, reason: "manual", serviceOn: true, adminOn: true });
  const v1 = (await call("alfie", "GET", "/api/version")).json.revision;
  await call("alfie", "POST", "/api/brick/status", { locked: true, reason: "manual", serviceOn: true, adminOn: true });
  assert.equal((await call("alfie", "GET", "/api/version")).json.revision, v1);
  await call("alfie", "POST", "/api/brick/status", { locked: false, reason: "", serviceOn: false, adminOn: true });
  const snap = (await call("claire", "GET", "/api/brick")).json;
  assert.equal(snap.statuses.alfie.serviceOn, false);
  assert.ok(snap.events.some((e: any) => e.type === "tamper" && e.personId === "alfie"));
});

test("phone-free minutes add up into today, the week and a streak", async () => {
  const now = new Date();
  const yesterday = new Date(now.getTime() - 24 * 3600_000);
  await call("alastair", "POST", "/api/brick/events", { type: "session", minutes: 75, at: now.toISOString() });
  await call("alastair", "POST", "/api/brick/events", { type: "session", minutes: 90, at: yesterday.toISOString() });
  assert.equal((await call("alastair", "POST", "/api/brick/events", { type: "hack" })).status, 400);
  const stats = (await call("claire", "GET", "/api/brick")).json.stats;
  assert.equal(stats.alastair.today, 75);
  assert.ok(stats.alastair.streakDays >= 2);
  assert.equal(Object.keys(stats.alastair.byDay).length, 7);
  assert.equal(stats.alfie.today, 0);

  // Each person sees their own sessions, newest first.
  const mine = (await call("alastair", "GET", "/api/brick")).json.mySessions;
  assert.deepEqual(mine.map((x: any) => x.minutes), [75, 90]);
  assert.equal((await call("alfie", "GET", "/api/brick")).json.mySessions.length, 0);
});

test("a separate parent PIN guards a child's essential apps", async () => {
  // Parents are already chosen by the first test; nothing is set yet.
  assert.equal((await call("claire", "GET", "/api/brick")).json.hasParentPin, false);
  assert.equal((await call("alfie", "POST", "/api/brick/parent-pin/check", { pin: "135790" })).status, 409);

  // Only a parent can set it, it must be 6-8 digits, and not the family PIN everyone knows.
  assert.equal((await call("alfie", "PUT", "/api/brick/parent-pin", { pin: "135790" })).status, 403);
  assert.equal((await call("claire", "PUT", "/api/brick/parent-pin", { pin: "1234" })).status, 400);
  assert.equal((await call("claire", "PUT", "/api/brick/parent-pin", { pin: "12ab56" })).status, 400);
  store.setPin("246810"); // a 6-digit family PIN, so we can check the clash (this signs everyone out)
  for (const who of ["claire", "alastair", "alfie"]) {
    const res = await fetch(base + "/api/login", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ personId: who, pin: "246810" }),
    });
    tokens[who] = ((await res.json()) as any).token;
  }
  assert.equal((await call("claire", "PUT", "/api/brick/parent-pin", { pin: "246810" })).status, 400);
  assert.equal((await call("claire", "PUT", "/api/brick/parent-pin", { pin: "135790" })).status, 200);
  assert.equal((await call("alfie", "GET", "/api/brick")).json.hasParentPin, true);

  // The family PIN does not work as a parent PIN, so a child who knows it still can't get in.
  const wrong = await call("alfie", "POST", "/api/brick/parent-pin/check", { pin: "246810" });
  assert.equal(wrong.status, 401);
  const ok = await call("alfie", "POST", "/api/brick/parent-pin/check", { pin: "135790" });
  assert.equal(ok.status, 200);

  // The server's snapshot never carries the PIN or its hash.
  const snap = JSON.stringify((await call("alfie", "GET", "/api/brick")).json);
  assert.ok(!snap.includes("135790"));

  // Parents see the wrong guess and the unlock in Recent.
  const types = ((await call("claire", "GET", "/api/brick")).json.events as any[]).map((e) => e.type);
  assert.ok(types.includes("pin-wrong"));
  assert.ok(types.includes("apps-unlocked"));
  assert.ok(types.includes("parent-pin-set"));

  // Five wrong guesses make that person wait, even with the right PIN, but not anyone else.
  for (let i = 0; i < 5; i++) assert.equal((await call("alfie", "POST", "/api/brick/parent-pin/check", { pin: "000000" })).status, 401);
  assert.equal((await call("alfie", "POST", "/api/brick/parent-pin/check", { pin: "135790" })).status, 429);
  assert.equal((await call("alastair", "POST", "/api/brick/parent-pin/check", { pin: "135790" })).status, 200);
});

test("the status for the tray app is for this PC only and carries no secrets", async () => {
  const { isLocalRequest, buildStatus } = await import("./admin");
  assert.equal(isLocalRequest("127.0.0.1", undefined), true);
  assert.equal(isLocalRequest("::1", undefined), true);
  assert.equal(isLocalRequest("::ffff:127.0.0.1", undefined), true);
  assert.equal(isLocalRequest("192.168.1.20", undefined), false);
  assert.equal(isLocalRequest("203.0.113.9", undefined), false);
  assert.equal(isLocalRequest("127.0.0.1", "203.0.113.9"), false); // came through a proxy

  // Over the real socket (this test connects from 127.0.0.1) it answers, with no sign-in needed.
  const res = await fetch(base + "/api/admin/status");
  assert.equal(res.status, 200);
  const status = (await res.json()) as any;
  assert.equal(status.ok, true);
  assert.deepEqual(status.people.map((p: any) => p.id).sort(), ["alastair", "alfie", "claire"]);
  assert.equal(status.parentPinSet, true);
  assert.ok(status.uptimeSeconds >= 0);
  const text = JSON.stringify(status);
  assert.ok(!text.includes(tokens.alfie) && !text.includes("135790") && !text.includes("scrypt"));

  // A request that claims to come through a proxy is refused.
  const proxied = await fetch(base + "/api/admin/status", { headers: { "X-Forwarded-For": "203.0.113.9" } });
  assert.equal(proxied.status, 404);

  // Same builder directly: Alfie's phone-free time shows up per person.
  const direct = buildStatus(store, new (await import("./brick")).BrickStore(store), { startedAt: Date.now() - 5000, port: 1, https: false, dataDir: dir }, { pausedUntil: 0, recentFailures: 0 });
  assert.ok(direct.uptimeSeconds >= 5);
  assert.ok(direct.people.every((p: any) => typeof p.phoneFreeWeekMinutes === "number"));
});

const admin = (method: string, url: string, body?: unknown, headers: Record<string, string> = {}) =>
  fetch(base + url, {
    method,
    headers: { "Content-Type": "application/json", "X-Dfp-Admin": "tray", ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
  }).then(async (r) => ({ status: r.status, json: (await r.json()) as any }));

test("essential apps can be chosen from the server PC", async () => {
  // Alfie's phone says which apps it has. Junk entries and repeats are dropped.
  const rep = await call("alfie", "POST", "/api/brick/apps", {
    apps: [
      { pkg: "com.google.android.apps.maps", label: "Maps" },
      { pkg: "com.zhiliaoapp.musically", label: "TikTok" },
      { pkg: "com.zhiliaoapp.musically", label: "TikTok again" },
      { pkg: "not a package", label: "Bad" },
    ],
  });
  assert.equal(rep.status, 200);
  const listed = await admin("GET", "/api/admin/apps");
  const alfie = listed.json.people.find((p: any) => p.id === "alfie");
  assert.deepEqual(alfie.installed.map((a: any) => a.pkg), ["com.google.android.apps.maps", "com.zhiliaoapp.musically"]);
  assert.equal(alfie.managed, false);
  assert.equal((await call("alfie", "GET", "/api/brick")).json.managedApps, null);

  // Choosing from the PC puts that list on his phone's snapshot, and nobody else's.
  const set = await admin("PUT", "/api/admin/apps/alfie", { allowed: ["com.google.android.apps.maps", "bad package"] });
  assert.equal(set.status, 200);
  assert.deepEqual(set.json.allowed, ["com.google.android.apps.maps"]);
  assert.deepEqual((await call("alfie", "GET", "/api/brick")).json.managedApps, ["com.google.android.apps.maps"]);
  assert.equal((await call("claire", "GET", "/api/brick")).json.managedApps, null);
  assert.equal((await admin("PUT", "/api/admin/apps/nobody", { allowed: [] })).status, 404);

  // An empty list is a real choice (nothing extra allowed); null hands the choice back to the phone.
  await admin("PUT", "/api/admin/apps/alfie", { allowed: [] });
  assert.deepEqual((await call("alfie", "GET", "/api/brick")).json.managedApps, []);
  await admin("PUT", "/api/admin/apps/alfie", { allowed: null });
  assert.equal((await call("alfie", "GET", "/api/brick")).json.managedApps, null);

  // Parents can see it happened.
  const types = ((await call("claire", "GET", "/api/brick")).json.events as any[]).map((e) => e.type);
  assert.ok(types.includes("apps-managed"));
});

test("Family Focus can be started and ended from the server PC", async () => {
  const start = await admin("POST", "/api/admin/focus", { minutes: 60, personIds: ["alfie"] });
  assert.equal(start.status, 200);
  const snap = (await call("alfie", "GET", "/api/brick")).json;
  assert.equal(snap.focus.active, true);
  assert.deepEqual(snap.focus.personIds, ["alfie"]);
  assert.equal((await admin("POST", "/api/admin/focus", { minutes: 1 })).status, 400);
  const status = (await admin("GET", "/api/admin/status")).json;
  assert.equal(status.focus.active, true);
  assert.ok(status.focus.untilMs > Date.now());
  assert.equal(status.people.find((p: any) => p.id === "claire").parent, true);
  assert.equal((await admin("DELETE", "/api/admin/focus")).status, 200);
  assert.equal((await call("alfie", "GET", "/api/brick")).json.focus, null);
  assert.equal(((await admin("GET", "/api/admin/status")).json).focus.active, false);
});

test("controls from the server PC refuse web pages, proxies and requests without the tray header", async () => {
  assert.equal((await admin("POST", "/api/admin/focus", { minutes: 30 }, { "X-Dfp-Admin": "" })).status, 404);
  assert.equal((await admin("POST", "/api/admin/focus", { minutes: 30 }, { "X-Forwarded-For": "203.0.113.9" })).status, 404);
  assert.equal((await admin("POST", "/api/admin/focus", { minutes: 30 }, { Origin: "https://evil.example" })).status, 404);
  assert.equal((await admin("PUT", "/api/admin/apps/alfie", { allowed: [] }, { Origin: "https://evil.example" })).status, 404);
  assert.equal((await call("alfie", "GET", "/api/brick")).json.focus, null); // nothing slipped through
  const { isLocalRequest } = await import("./admin");
  assert.equal(isLocalRequest("127.0.0.1", undefined, "localhost:8787", undefined), true);
  assert.equal(isLocalRequest("127.0.0.1", undefined, "evil.example:8787", undefined), false); // DNS rebinding
});
