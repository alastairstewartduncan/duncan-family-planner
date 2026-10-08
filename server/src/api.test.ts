import assert from "node:assert/strict";
import fs from "node:fs";
import { AddressInfo } from "node:net";
import { createServer, Server } from "node:http";
import os from "node:os";
import path from "node:path";
import { after, before, test } from "node:test";
import { DateTime } from "luxon";
import { createApi } from "./api";
import { Store } from "./store";

let server: Server;
let base = "";
let store: Store;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), "dfp-"));

async function call(method: string, url: string, body?: unknown, token?: string) {
  const res = await fetch(base + url, {
    method,
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return { status: res.status, json: (await res.json()) as any };
}

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
});

after(() => {
  server.close();
  store.db.close();
  fs.rmSync(dir, { recursive: true, force: true });
});

test("sign-in, plan a day, claim an item, shopping list", async () => {
  assert.equal((await call("GET", "/api/family")).status, 401);
  assert.equal((await call("POST", "/api/login", { personId: "alfie", pin: "0000" })).status, 401);
  const login = await call("POST", "/api/login", { personId: "alastair", pin: "2468" });
  assert.equal(login.status, 200);
  const token = login.json.token as string;

  // A regular event appears on every matching day from today.
  const today = DateTime.fromFormat(store.today(), "yyyy-MM-dd");
  const r = await call("POST", "/api/recurring", { title: "Ice hockey", time: "19:00", daysOfWeek: [today.weekday], ownerPersonId: "alastair" }, token);
  assert.equal(r.status, 200);
  const date = today.toFormat("yyyy-MM-dd");
  const day = (await call("GET", `/api/days/${date}`, undefined, token)).json;
  assert.deepEqual(day.items.map((i: any) => i.title), ["Ice hockey"]);

  // Save the day as a user, then claim a new item.
  const saved = await call("PUT", `/api/days/${date}`, {
    ...day,
    tea: "Chicken wings & waffle fries",
    items: [...day.items, { id: "lift", title: "Lift to Hannah's", time: null }],
  }, token);
  assert.equal(saved.status, 200);
  assert.equal(saved.json.userEdited, true);
  assert.equal(saved.json.updatedBy, "alastair");
  const claimed = await call("PATCH", `/api/days/${date}/items/lift`, { claimedBy: "alastair" }, token);
  // Same change sent the way Android does it (POST + override header).
  const viaPost = await fetch(`${base}/api/days/${date}/items/lift`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}`, "X-HTTP-Method-Override": "PATCH" },
    body: JSON.stringify({ done: true }),
  });
  assert.equal(viaPost.status, 200);
  assert.equal(claimed.json.items.find((i: any) => i.id === "lift").claimedBy, "alastair");

  // Week range creates upcoming days.
  const week = await call("GET", `/api/days?from=${date}&to=${today.plus({ days: 6 }).toFormat("yyyy-MM-dd")}`, undefined, token);
  assert.equal(week.json.length, 7);

  // Version changes after writes.
  const v1 = (await call("GET", "/api/version", undefined, token)).json.revision;
  await call("POST", "/api/shopping", { text: "Waffle fries" }, token);
  const v2 = (await call("GET", "/api/version", undefined, token)).json.revision;
  assert.ok(v2 > v1);
  const list = (await call("GET", "/api/shopping", undefined, token)).json;
  assert.equal(list[0].text, "Waffle fries");
  assert.equal(list[0].addedBy, "alastair");
});

test("backups are written and pruned", () => {
  const backups = path.join(dir, "backups");
  fs.mkdirSync(backups, { recursive: true });
  for (const d of ["2020-01-01", "2020-01-02", "2020-01-03"]) fs.writeFileSync(path.join(backups, `planner-${d}.db`), "");
  store.backup(backups, 2);
  const files = fs.readdirSync(backups).sort();
  assert.equal(files.length, 2);
  assert.ok(files.includes(`planner-${DateTime.now().toFormat("yyyy-MM-dd")}.db`));
});
