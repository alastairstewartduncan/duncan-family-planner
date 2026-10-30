import fs from "node:fs";
import path from "node:path";

/** Server settings. Read from config.json next to package.json, then environment variables. */
export interface Config {
  port: number;
  dataDir: string; // database + backups live here
  googleKeyFile: string; // service account key for Google Calendar sync ("" = off)
  backupsToKeep: number;
  https: boolean; // serve over HTTPS with the server's own pinned certificate (default on)
}

export const ROOT = path.resolve(__dirname, "..");

export function loadConfig(): Config {
  const file = path.join(ROOT, "config.json");
  const fromFile = fs.existsSync(file) ? (readJsonFile(file) as Partial<Config>) : {};
  const cfg: Config = {
    port: Number(process.env.PORT ?? fromFile.port ?? 8787),
    dataDir: process.env.DATA_DIR ?? fromFile.dataDir ?? "data",
    googleKeyFile: process.env.GOOGLE_KEY_FILE ?? fromFile.googleKeyFile ?? "google-key.json",
    backupsToKeep: Number(fromFile.backupsToKeep ?? 14),
    https: (process.env.HTTPS ?? String(fromFile.https ?? true)) !== "false",
  };
  cfg.dataDir = path.resolve(ROOT, cfg.dataDir);
  if (cfg.googleKeyFile) {
    cfg.googleKeyFile = path.resolve(ROOT, cfg.googleKeyFile);
    if (!fs.existsSync(cfg.googleKeyFile)) cfg.googleKeyFile = "";
  }
  fs.mkdirSync(cfg.dataDir, { recursive: true });
  return cfg;
}

/**
 * Reads a JSON file, ignoring a leading byte-order mark. Windows PowerShell 5.1
 * (the one built into Windows) adds one when it writes UTF-8, which JSON.parse rejects.
 */
export function readJsonFile(file: string): unknown {
  return JSON.parse(fs.readFileSync(file, "utf8").replace(/^\uFEFF/, ""));
}
