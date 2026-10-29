import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";
import { readJsonFile } from "./config";

test("JSON written by Windows PowerShell (with a byte-order mark) still loads", () => {
  const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "dfp-cfg-")), "config.json");
  fs.writeFileSync(file, "﻿" + JSON.stringify({ port: 8787 }), "utf8");
  assert.deepEqual(readJsonFile(file), { port: 8787 });
  fs.writeFileSync(file, JSON.stringify({ port: 9000 }), "utf8");
  assert.deepEqual(readJsonFile(file), { port: 9000 });
});
