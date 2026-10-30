import assert from "node:assert/strict";
import fs from "node:fs";
import { AddressInfo } from "node:net";
import { createServer } from "node:https";
import os from "node:os";
import path from "node:path";
import tls from "node:tls";
import { test } from "node:test";
import { createApi, resetLoginLimits } from "./api";
import { Store } from "./store";
import { loadOrCreateTls, securityCode } from "./tls";

test("the server makes its certificate once and keeps it", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "dfp-tls-"));
  const a = await loadOrCreateTls(path.join(dir, "tls"));
  const b = await loadOrCreateTls(path.join(dir, "tls"));
  assert.equal(a.fingerprint, b.fingerprint);
  assert.match(a.securityCode, /^[0-9A-F]{4} [0-9A-F]{4} [0-9A-F]{4} [0-9A-F]{4}$/);
  assert.equal(fs.readFileSync(path.join(dir, "tls", "security-code.txt"), "utf8").trim(), a.securityCode);
  assert.equal(securityCode("ab:cd:ef:01:23:45:67:89:00"), "ABCD EF01 2345 6789");

  // A phone connecting over HTTPS sees exactly that certificate.
  const store = new Store(path.join(dir, "t.db"));
  const server = createServer({ key: a.key, cert: a.cert }, createApi(store));
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
  const port = (server.address() as AddressInfo).port;
  const seen = await new Promise<string>((resolve, reject) => {
    const sock = tls.connect({ host: "127.0.0.1", port, rejectUnauthorized: false }, () => {
      resolve(sock.getPeerX509Certificate()!.fingerprint256);
      sock.end();
    });
    sock.on("error", reject);
  });
  assert.equal(seen, a.fingerprint);
  server.close();
  store.db.close();
});

test("lots of wrong PINs from different places pause every sign-in", async () => {
  resetLoginLimits();
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "dfp-lock-"));
  const store = new Store(path.join(dir, "t.db"));
  store.saveFamily({ people: [{ id: "alfie", name: "Alfie", colour: "#43A047" }] });
  store.setPin("135790");
  const handle = createApi(store);
  // Call the handler directly so each guess can claim a different address.
  const login = (ip: string, pin: string) => new Promise<number>((resolve) => {
    const { Readable } = require("node:stream");
    const req = Readable.from([Buffer.from(JSON.stringify({ personId: "alfie", pin }))]);
    Object.assign(req, { method: "POST", url: "/api/login", headers: {}, socket: { remoteAddress: ip } });
    const res: any = { headersSent: false, statusCode: 0, writeHead(s: number) { this.statusCode = s; }, end() { resolve(this.statusCode); } };
    handle(req, res);
  });
  for (let i = 0; i < 9; i++) assert.equal(await login(`10.0.0.${i}`, "000000"), 401);
  assert.equal(await login("10.0.1.1", "135790"), 200); // still fine before the limit
  assert.equal(await login("10.0.0.99", "000000"), 401); // tenth wrong PIN
  assert.equal(await login("10.0.1.2", "135790"), 429); // now everyone waits, even with the right PIN
  resetLoginLimits();
  assert.equal(await login("10.0.1.2", "135790"), 200);
  store.db.close();
});
