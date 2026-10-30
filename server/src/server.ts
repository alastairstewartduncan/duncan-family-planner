// Entry point: `npm start`. Opens the database, starts the API and the daily housekeeping.
import { createServer } from "node:http";
import { createServer as createHttpsServer } from "node:https";
import path from "node:path";
import { createApi } from "./api";
import { CalendarSync } from "./calendar";
import { loadConfig } from "./config";
import { DAYS_AHEAD, Store } from "./store";
import { loadOrCreateTls } from "./tls";

// Put the time on every log line so the log file is useful after the fact.
for (const level of ["log", "warn", "error"] as const) {
  const original = console[level].bind(console);
  console[level] = (...args: unknown[]) => original(new Date().toISOString(), ...args);
}

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

const handler = createApi(store, { startedAt: Date.now(), port: config.port, https: config.https, dataDir: config.dataDir });
let server: ReturnType<typeof createServer>;
async function start() {
  if (config.https) {
    const tls = await loadOrCreateTls(path.join(config.dataDir, "tls"));
    server = createHttpsServer({ key: tls.key, cert: tls.cert }, handler);
    console.log(`HTTPS on. Security code phones should show: ${tls.securityCode}`);
  } else {
    server = createServer(handler);
    console.log("HTTPS off (plain http) - only use this on a private network.");
  }
  server.listen(config.port, "0.0.0.0", () => {
    console.log(`Duncan Family Planner server listening on port ${config.port}`);
    console.log(`Data folder: ${config.dataDir}`);
  });
}
start().catch((e) => {
  console.error("Couldn't start the server", e);
  process.exit(1);
});

// Stop taking new requests, let in-flight ones finish (max 5 s), then close the database cleanly.
let stopping = false;
function shutdown() {
  if (stopping) return;
  stopping = true;
  console.log("Shutting down...");
  const finish = () => {
    try { store.db.close(); } catch { /* already closed */ }
    process.exit(0);
  };
  setTimeout(finish, 5000).unref();
  if (server) server.close(finish); else finish();
}

// A crash is logged with its time, then the process exits so the Windows startup task restarts it.
process.on("uncaughtException", (e) => { console.error("Uncaught error, restarting", e); process.exit(1); });
process.on("unhandledRejection", (e) => { console.error("Unhandled promise rejection, restarting", e); process.exit(1); });
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
