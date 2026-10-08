// One-off setup: `npm run setup` (or `npm run setup -- family.json`).
// Loads the family, usual week and regular events from family.json and sets the
// family PIN. Safe to re-run: it updates what's in the file and keeps existing
// days and the shopping list.
import fs from "node:fs";
import path from "node:path";
import { ROOT, loadConfig } from "./config";
import { DAYS_AHEAD, Store } from "./store";
import { Family, PersonDay, RecurringItem } from "./types";

interface SeedFile {
  pin?: string;
  family: Omit<Family, "calendarId"> & { calendarId?: string };
  defaults?: Record<string, { people: Record<string, PersonDay> }>;
  recurring?: Record<string, RecurringItem>;
}

const file = path.resolve(process.argv[2] ?? path.join(ROOT, "family.json"));
if (!fs.existsSync(file)) {
  console.error(`Can't find ${file}.\nCopy family.example.json to family.json, edit it, then run npm run setup again.`);
  process.exit(1);
}
const seed = JSON.parse(fs.readFileSync(file, "utf8")) as SeedFile;
const config = loadConfig();
const store = new Store(path.join(config.dataDir, "planner.db"));

const existing = store.family();
store.saveFamily({
  ...seed.family,
  calendarId: seed.family.calendarId ?? existing.calendarId ?? "",
  people: seed.family.people.map((p) => ({ id: p.id, name: p.name, colour: p.colour })),
});
for (const [weekday, value] of Object.entries(seed.defaults ?? {})) store.saveDefaults(Number(weekday), value.people);
for (const [id, value] of Object.entries(seed.recurring ?? {})) store.saveRecurring(id, value);

const pin = String(seed.pin ?? "").trim();
if (pin) {
  if (!/^\d{4,8}$/.test(pin)) {
    console.error("The PIN must be 4–8 digits.");
    process.exit(1);
  }
  store.setPin(pin);
  console.log("✔ Family PIN set (everyone will need to sign in again). You can now remove it from family.json.");
} else if (!store.hasPin()) {
  console.error('No PIN set yet. Add "pin": "1234" (4–8 digits) to family.json and run setup again.');
  process.exit(1);
}

store.ensureDays(store.today(), DAYS_AHEAD);
console.log(`✔ Set up "${seed.family.name}" with ${seed.family.people.length} people, ` +
  `${Object.keys(seed.defaults ?? {}).length} usual days and ${Object.keys(seed.recurring ?? {}).length} regular events.`);
store.db.close();
