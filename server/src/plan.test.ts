import assert from "node:assert/strict";
import { test } from "node:test";
import {
  buildNewDay,
  calendarEventId,
  diffCalendar,
  formatDay,
  reconcileRecurring,
} from "./plan";
import { Person, RecurringItem } from "./types";

const people: Person[] = [
  { id: "claire", name: "Claire", colour: "#D81B60" },
  { id: "alastair", name: "Alastair", colour: "#1E88E5" },
  { id: "alfie", name: "Alfie", colour: "#43A047" },
];

const recurring: Record<string, RecurringItem> = {
  hockey: { title: "Ice hockey", time: "19:00", daysOfWeek: [2], ownerPersonId: "alastair", active: true },
  beavers: { title: "Beavers", time: null, daysOfWeek: [3], active: true },
};

test("new Wednesday gets defaults and Beavers but not hockey", () => {
  const day = buildNewDay(
    "2026-10-07",
    people,
    { people: { alfie: { status: "school", extras: "", travel: "bus home" } } },
    recurring,
  );
  assert.equal(day.people.alfie.travel, "bus home");
  assert.equal(day.people.claire.status, "");
  assert.deepEqual(day.items.map((i) => i.title), ["Beavers"]);
  assert.equal(day.userEdited, false);
});

test("formatDay matches the family message shape", () => {
  const day = buildNewDay("2026-10-06", people, undefined, recurring);
  day.people.claire.status = "Blackburn";
  day.people.alfie = { status: "school", extras: "", travel: "bus home" };
  day.tea = "chicken wings & waffle fries";
  const { title, body } = formatDay(day, { people });
  assert.equal(title, "Tuesday 6 October");
  assert.equal(
    body,
    "Claire – Blackburn\nAlfie – school / bus home\n\nTea – chicken wings & waffle fries\n19:00 Ice hockey (Alastair)",
  );
});

test("reconcile respects skipped items and removes deleted recurring", () => {
  const day = buildNewDay("2026-10-07", people, undefined, recurring);
  assert.equal(reconcileRecurring(day, recurring), null);
  const skipped = { ...day, items: [], skippedRecurring: ["beavers"] };
  assert.equal(reconcileRecurring(skipped, recurring), null);
  const removed = reconcileRecurring(day, { hockey: recurring.hockey });
  assert.deepEqual(removed?.items, []);
});

test("calendar diff only covers timed items", () => {
  const before = buildNewDay("2026-10-06", people, undefined, recurring);
  const after = { ...before, items: [...before.items, { id: "x", title: "Hannah", time: "18:00" }] };
  const d = diffCalendar(before, after);
  assert.deepEqual(d.upserts.map((i) => i.id), ["x"]);
  assert.deepEqual(diffCalendar(after, before).deletes, ["x"]);
  assert.match(calendarEventId("duncan", "2026-10-06", "x"), /^[0-9a-v]+$/);
});
