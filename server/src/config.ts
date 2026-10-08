import fs from "node:fs";
import path from "node:path";

/** Server settings. Read from config.json next to package.json, then environment variables. */
export interface Config {
  port: number;
  dataDir: string; // database + backups live here
  googleKeyFile: string; // service account key for Google Calendar sync ("" = off)
  backupsToKeep: number;
}

export const ROOT = path.resolve(__dirname, "..");

export function loadConfig(): Config {
  const file = path.join(ROOT, "config.json");
  const fromFile = fs.existsSync(file) ? (JSON.parse(fs.readFileSync(file, "utf8")) as Partial<Config>) : {};
  const cfg: Config = {
    port: Number(process.env.PORT ?? fromFile.port ?? 8787),
    dataDir: process.env.DATA_DIR ?? fromFile.dataDir ?? "data",
    googleKeyFile: process.env.GOOGLE_KEY_FILE ?? fromFile.googleKeyFile ?? "google-key.json",
    backupsToKeep: Number(fromFile.backupsToKeep ?? 14),
  };
  cfg.dataDir = path.resolve(ROOT, cfg.dataDir);
  if (cfg.googleKeyFile) {
    cfg.googleKeyFile = path.resolve(ROOT, cfg.googleKeyFile);
    if (!fs.existsSync(cfg.googleKeyFile)) cfg.googleKeyFile = "";
  }
  fs.mkdirSync(cfg.dataDir, { recursive: true });
  return cfg;
}
