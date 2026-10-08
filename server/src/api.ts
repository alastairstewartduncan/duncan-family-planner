// HTTP API. Plain node:http with a tiny router, so there's nothing extra to install.
import { IncomingMessage, ServerResponse } from "node:http";
import { DateTime } from "luxon";
import { DATE_FMT } from "./plan";
import { HttpError, Store } from "./store";
import { Person, PersonDay, RecurringItem } from "./types";

type Ctx = { req: IncomingMessage; params: string[]; query: URLSearchParams; body: any; personId: string };
type Handler = (ctx: Ctx) => unknown;
interface Route { method: string; pattern: RegExp; auth: boolean; handler: Handler }

const DATE = "(\\d{4}-\\d{2}-\\d{2})";
const ID = "([A-Za-z0-9_-]{1,64})";
const HEX = /^#[0-9A-Fa-f]{6}$/;
const HHMM = /^([01]\d|2[0-3]):[0-5]\d$/;

/** Failed PIN attempts per address, to slow down guessing. */
const failures = new Map<string, { count: number; until: number }>();

export function createApi(store: Store) {
  const routes: Route[] = [];
  const add = (method: string, path: string, handler: Handler, auth = true) =>
    routes.push({ method, pattern: new RegExp(`^${path}$`), auth, handler });

  // ---- Public ------------------------------------------------------------------
  add("GET", "/api/health", () => ({ ok: true, revision: store.revision(), hasPin: store.hasPin() }), false);

  // Names and colours only, for the "who are you?" picker on the sign-in screen.
  add("GET", "/api/people", () => store.family().people, false);

  add("POST", "/api/login", ({ req, body }) => {
    const ip = req.socket.remoteAddress ?? "?";
    const f = failures.get(ip);
    if (f && f.until > Date.now()) throw new HttpError(429, "Too many wrong PINs. Try again in a minute.");
    const personId = String(body?.personId ?? "");
    if (!store.family().people.some((p) => p.id === personId)) throw new HttpError(400, "Pick who you are.");
    if (!store.checkPin(String(body?.pin ?? ""))) {
      const count = (f?.count ?? 0) + 1;
      failures.set(ip, { count, until: count >= 5 ? Date.now() + 60_000 : 0 });
      throw new HttpError(401, "That PIN isn't right.");
    }
    failures.delete(ip);
    return { token: store.createToken(personId), personId };
  }, false);

  // ---- Signed in -----------------------------------------------------------------
  add("POST", "/api/logout", ({ req }) => {
    store.revokeToken(bearer(req) ?? "");
    return { ok: true };
  });

  add("GET", "/api/version", () => ({ revision: store.revision(), today: store.today() }));

  add("GET", "/api/family", () => store.family());

  add("PUT", "/api/family/settings", ({ body }) => {
    const update: Record<string, unknown> = {};
    if (body.reminderTime !== undefined) {
      if (!HHMM.test(body.reminderTime)) throw new HttpError(400, "reminderTime must be HH:mm");
      update.reminderTime = body.reminderTime;
    }
    if (body.reminderDays !== undefined) {
      update.reminderDays = [...new Set((body.reminderDays as unknown[]).map(Number).filter((d) => d >= 1 && d <= 7))].sort();
    }
    if (body.calendarId !== undefined) update.calendarId = String(body.calendarId).trim();
    if (body.name !== undefined) update.name = String(body.name).trim() || "Family";
    return store.saveFamily(update);
  });

  add("PUT", "/api/family/people", ({ body }) => {
    const people: Person[] = (Array.isArray(body) ? body : []).map((p: any) => ({
      id: String(p.id ?? "").trim(),
      name: String(p.name ?? "").trim(),
      colour: HEX.test(p.colour) ? p.colour : "#607D8B",
    }));
    if (!people.length || people.some((p) => !/^[a-z0-9-]{1,40}$/.test(p.id) || !p.name)) {
      throw new HttpError(400, "Each person needs a name and an id.");
    }
    if (new Set(people.map((p) => p.id)).size !== people.length) throw new HttpError(400, "Duplicate person.");
    return store.saveFamily({ people });
  });

  add("GET", "/api/days", ({ query }) => {
    const from = validDate(query.get("from"));
    const to = validDate(query.get("to"));
    const span = DateTime.fromFormat(to, DATE_FMT).diff(DateTime.fromFormat(from, DATE_FMT), "days").days + 1;
    if (span < 1 || span > 62) throw new HttpError(400, "Range must be 1–62 days.");
    store.ensureDays(from, span);
    return store.daysBetween(from, to);
  });

  add("GET", `/api/days/${DATE}`, ({ params }) => {
    store.ensureDays(params[0], 1);
    return store.getDay(params[0]) ?? null;
  });

  add("PUT", `/api/days/${DATE}`, ({ params, body, personId }) => store.saveDay(params[0], body, personId));

  add("PATCH", `/api/days/${DATE}/items/${ID}`, ({ params, body, personId }) => {
    const patch: { done?: boolean; claimedBy?: string | null } = {};
    if (body.done !== undefined) patch.done = !!body.done;
    if (body.claimedBy !== undefined) patch.claimedBy = body.claimedBy ? String(body.claimedBy) : null;
    return store.patchItem(params[0], params[1], patch, personId);
  });

  add("POST", `/api/days/${DATE}/copy-people`, ({ params, body, personId }) =>
    store.copyPeople(validDate(body?.from), params[0], personId));

  add("GET", "/api/recurring", () =>
    Object.entries(store.recurring()).map(([id, r]) => ({ id, ...r })));

  add("POST", "/api/recurring", ({ body }) => ({ id: store.saveRecurring(undefined, cleanRecurring(body)) }));
  add("PUT", `/api/recurring/${ID}`, ({ params, body }) => ({ id: store.saveRecurring(params[0], cleanRecurring(body)) }));
  add("DELETE", `/api/recurring/${ID}`, ({ params }) => {
    store.deleteRecurring(params[0]);
    return { ok: true };
  });

  add("GET", "/api/defaults", () => store.defaults());
  add("PUT", "/api/defaults/([1-7])", ({ params, body }) => {
    const people: Record<string, PersonDay> = {};
    for (const [id, pd] of Object.entries((body?.people ?? {}) as Record<string, any>)) {
      people[id] = { status: String(pd?.status ?? ""), extras: String(pd?.extras ?? ""), travel: String(pd?.travel ?? "") };
    }
    store.saveDefaults(Number(params[0]), people);
    return { ok: true };
  });

  add("GET", "/api/shopping", () => store.shopping());
  add("POST", "/api/shopping", ({ body, personId }) => {
    store.addShopping(String(body?.text ?? ""), personId);
    return { ok: true };
  });
  add("PATCH", `/api/shopping/${ID}`, ({ params, body }) => {
    store.setShoppingDone(params[0], !!body?.done);
    return { ok: true };
  });
  add("DELETE", `/api/shopping/${ID}`, ({ params }) => {
    store.deleteShopping(params[0]);
    return { ok: true };
  });
  add("POST", "/api/shopping/clear-done", () => {
    store.clearDoneShopping();
    return { ok: true };
  });

  // ---- Dispatcher ------------------------------------------------------------------
  return async function handle(req: IncomingMessage, res: ServerResponse) {
    const started = Date.now();
    const url = new URL(req.url ?? "/", "http://localhost");
    try {
      const candidates = routes.filter((r) => r.pattern.test(url.pathname));
      if (!candidates.length) throw new HttpError(404, "Not found");
      // Android's HttpURLConnection can't send PATCH, so it sends POST + this header.
      const override = String(req.headers["x-http-method-override"] ?? "").toUpperCase();
      const method = req.method === "POST" && override === "PATCH" ? "PATCH" : req.method;
      const route = candidates.find((r) => r.method === method);
      if (!route) throw new HttpError(405, "Method not allowed");

      let personId = "";
      if (route.auth) {
        const pid = store.personForToken(bearer(req) ?? "");
        if (!pid) throw new HttpError(401, "Please sign in again.");
        personId = pid;
      }
      const body = ["POST", "PUT", "PATCH"].includes(method ?? "") ? await readJson(req) : undefined;
      const params = route.pattern.exec(url.pathname)!.slice(1);
      const result = await route.handler({ req, params, query: url.searchParams, body: body ?? {}, personId });
      send(res, 200, result ?? null);
    } catch (e) {
      const status = e instanceof HttpError ? e.status : 500;
      if (status === 500) console.error(`[api] ${req.method} ${url.pathname}`, e);
      send(res, status, { error: e instanceof HttpError ? e.message : "Something went wrong on the server." });
    } finally {
      if (process.env.LOG_REQUESTS) console.log(`${req.method} ${url.pathname} ${res.statusCode} ${Date.now() - started}ms`);
    }
  };
}

function bearer(req: IncomingMessage): string | undefined {
  const h = req.headers.authorization ?? "";
  return h.startsWith("Bearer ") ? h.slice(7) : undefined;
}

function validDate(v: unknown): string {
  const s = String(v ?? "");
  if (!DateTime.fromFormat(s, DATE_FMT).isValid) throw new HttpError(400, "Dates must be yyyy-MM-dd");
  return s;
}

function cleanRecurring(b: any): RecurringItem {
  const title = String(b?.title ?? "").trim();
  const daysOfWeek = [...new Set(((b?.daysOfWeek ?? []) as unknown[]).map(Number).filter((d) => d >= 1 && d <= 7))].sort();
  if (!title || !daysOfWeek.length) throw new HttpError(400, "A regular event needs a name and at least one day.");
  return {
    title: title.slice(0, 200),
    time: typeof b.time === "string" && HHMM.test(b.time) ? b.time : null,
    durationMins: Math.min(Math.max(Number(b.durationMins) || 60, 5), 24 * 60),
    daysOfWeek,
    ownerPersonId: b.ownerPersonId || null,
    attendees: Array.isArray(b.attendees) ? b.attendees.map(String) : [],
    notes: String(b.notes ?? "").slice(0, 1000),
    active: b.active !== false,
  };
}

function readJson(req: IncomingMessage): Promise<any> {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks: Buffer[] = [];
    req.on("data", (c: Buffer) => {
      size += c.length;
      if (size > 1_000_000) {
        reject(new HttpError(413, "Request too large"));
        req.destroy();
      } else chunks.push(c);
    });
    req.on("end", () => {
      if (!chunks.length) return resolve({});
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString("utf8")));
      } catch {
        reject(new HttpError(400, "Body must be JSON"));
      }
    });
    req.on("error", reject);
  });
}

function send(res: ServerResponse, status: number, data: unknown) {
  if (res.headersSent) return;
  const body = JSON.stringify(data);
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" });
  res.end(body);
}
