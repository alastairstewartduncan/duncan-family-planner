// HTTPS for the home server without a domain name: the server makes its own
// certificate once and keeps it. Phones "pin" it on first connect — the person
// compares a short security code on the phone with the one shown on the PC —
// and from then on refuse any other certificate.
import { X509Certificate } from "node:crypto";
import fs from "node:fs";
import path from "node:path";

export interface Tls {
  key: string;
  cert: string;
  fingerprint: string; // SHA-256 of the certificate, "AB:CD:…"
  securityCode: string; // first 16 hex digits in groups of four, for people to compare
}

export function securityCode(fingerprint: string): string {
  const hex = fingerprint.replace(/:/g, "").toUpperCase().slice(0, 16);
  return hex.match(/.{4}/g)!.join(" ");
}

function describe(key: string, cert: string): Tls {
  const fingerprint = new X509Certificate(cert).fingerprint256;
  return { key, cert, fingerprint, securityCode: securityCode(fingerprint) };
}

/** Loads the server's certificate, creating one (valid 20 years) the first time. */
export async function loadOrCreateTls(dir: string): Promise<Tls> {
  const keyFile = path.join(dir, "key.pem");
  const certFile = path.join(dir, "cert.pem");
  if (fs.existsSync(keyFile) && fs.existsSync(certFile)) {
    return describe(fs.readFileSync(keyFile, "utf8"), fs.readFileSync(certFile, "utf8"));
  }
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const selfsigned = require("selfsigned") as { generate: (attrs: unknown, opts: unknown) => Promise<{ private: string; cert: string }> };
  const notBeforeDate = new Date();
  const notAfterDate = new Date(notBeforeDate);
  notAfterDate.setFullYear(notAfterDate.getFullYear() + 20);
  const pems = await selfsigned.generate([{ name: "commonName", value: "Duncan Family Planner" }], {
    keyType: "ec", curve: "P-256", algorithm: "sha256", notBeforeDate, notAfterDate,
  });
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(keyFile, pems.private, { mode: 0o600 });
  fs.writeFileSync(certFile, pems.cert);
  const tls = describe(pems.private, pems.cert);
  fs.writeFileSync(path.join(dir, "security-code.txt"), tls.securityCode + "\n");
  return tls;
}
