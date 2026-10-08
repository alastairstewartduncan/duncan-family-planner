// Entry point: `npm start`. Opens the database, starts the API and the daily housekeeping.
import { createServer } from "node:http";
import path from "node:path";
import { createApi } from "./api";
import { CalendarSync } from "./calendar";
import { loadConfig } from "./config";
import { DAYS_AHEAD, Store } from "./store";

const config = loadConfig();
const store = new Store(path.join(config.dataDir, "planner.db"));

if (config.googleKeyFile) {
  const calendar = new CalendarSync(config.googleKeyFile, () => store.family());
  store.onDayChanged((date, before, after) => calendar.dayChanged(date, before, after));
  console.log("Google Calendar sync: on (key file found)");
} else {
  console.log("Google Calendar sync: off (no google-key.json)");
}

if (!store.hasPin() || !store.family().people.length) {
  console.warn("⚠  No family set up yet. Stop the server and run:  npm run setup");
}

// Once a day (and at start-up): keep the next two weeks ready and back up the database.
let lastHousekeeping = "";
function housekeeping() {
  const today = store.today();
  if (today === lastHousekeeping) return;
  lastHousekeeping = today;
  try {
    const n = store.ensureDays(today, DAYS_AHEAD);
    const file = store.backup(path.join(config.dataDir, "backups"), config.backupsToKeep);
    console.log(`[housekeeping] ${today}: ${n} day(s) prepared, backup ${path.basename(file)}`);
  } catch (e) {
    console.error("[housekeeping] failed", e);
  }
}
housekeeping();
setInterval(housekeeping, 10 * 60 * 1000).unref();

const server = createServer(createApi(store));
server.listen(config.port, "0.0.0.0", () => {
  console.log(`Duncan Family Planner server listening on port ${config.port}`);
  console.log(`Data folder: ${config.dataDir}`);
});

function shutdown() {
  server.close();
  store.db.close();
  process.exit(0);
}
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
