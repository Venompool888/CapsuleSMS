#!/usr/bin/env node
// Local sync server for testing on a USB-connected phone.
//
//   node tools/dev-server.mjs            # listens on 127.0.0.1:8787
//   adb reverse tcp:8787 tcp:8787        # the phone can then use http://127.0.0.1:8787
//
// It creates a test account on first run and writes its credentials to
// data/dev/credentials.json (data/ is git-ignored). Use those to connect the phone and the
// mock device (tools/mock-device.mjs).
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { randomBytes } from "node:crypto";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { createApp } from "../src/app.js";
import { createUser, ensureAdmin, openDatabase } from "../src/db.js";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const dataDir = resolve(root, "data/dev");
const credentialsPath = resolve(dataDir, "credentials.json");
const port = Number(process.env.PORT ?? "8787");
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("Invalid PORT");
const serverUrl = `http://127.0.0.1:${port}`;
mkdirSync(dataDir, { recursive: true });

const db = openDatabase(resolve(dataDir, "capsule-sms.sqlite"));
if (!existsSync(credentialsPath)) {
  const credentials = {
    server: serverUrl,
    admin: { username: "admin", password: randomBytes(12).toString("base64url") },
    user: { username: "tester", password: randomBytes(12).toString("base64url") },
  };
  writeFileSync(credentialsPath, JSON.stringify(credentials, null, 2) + "\n", { mode: 0o600, flag: "wx" });
}
const credentials = JSON.parse(readFileSync(credentialsPath, "utf8"));
await ensureAdmin(db, credentials.admin.username, credentials.admin.password);
if (!db.prepare("SELECT 1 FROM users WHERE username = ?").get(credentials.user.username)) {
  await createUser(db, credentials.user);
}

const app = createApp({
  db,
  config: {
    publicBaseUrl: serverUrl,
    sessionTtlMs: 12 * 60 * 60 * 1000,
    messageTtlMs: 7 * 24 * 60 * 60 * 1000,
    allowInsecureHttp: true,
    trustProxy: false,
  },
});
const server = app.listen(port, "127.0.0.1", (error) => {
  if (error) {
    console.error(`Could not listen on ${serverUrl}: ${error.message}`);
    db.close();
    process.exitCode = 1;
    return;
  }
  if (credentials.server !== serverUrl) {
    credentials.server = serverUrl;
    writeFileSync(credentialsPath, JSON.stringify(credentials, null, 2) + "\n", { mode: 0o600 });
  }
  console.info(`Dev sync server on ${serverUrl}`);
  console.info(`Test account credentials: ${credentialsPath}`);
});
const shutdown = () => {
  server.close(() => { db.close(); process.exit(0); });
  // Long-poll clients keep connections open; drop them so shutdown actually completes.
  server.closeAllConnections();
};
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
