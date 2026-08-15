import { resolve } from "node:path";
import { createApp } from "./app.js";
import { ensureAdmin, openDatabase } from "./db.js";

const config = {
  host: process.env.HOST || "127.0.0.1",
  port: integerEnv("PORT", 8787),
  publicBaseUrl: normalizeBaseUrl(process.env.PUBLIC_BASE_URL || "http://127.0.0.1:8787"),
  sessionTtlMs: integerEnv("SESSION_TTL_HOURS", 12) * 60 * 60 * 1000,
  messageTtlMs: integerEnv("MESSAGE_TTL_HOURS", 168) * 60 * 60 * 1000,
  allowInsecureHttp: process.env.ALLOW_INSECURE_HTTP === "1",
  trustProxy: process.env.TRUST_PROXY === "1",
};

const adminUsername = process.env.ADMIN_USERNAME;
const adminPassword = process.env.ADMIN_PASSWORD;
if (!adminUsername || !adminPassword) {
  throw new Error("首次启动必须设置 ADMIN_USERNAME 和 ADMIN_PASSWORD（至少 12 个字符）");
}
if (!config.allowInsecureHttp && !config.publicBaseUrl.startsWith("https://")) {
  throw new Error("PUBLIC_BASE_URL 必须使用 HTTPS；本机调试可显式设置 ALLOW_INSECURE_HTTP=1");
}

const dbPath = process.env.DATABASE_PATH === ":memory:"
  ? ":memory:"
  : resolve(process.env.DATABASE_PATH || "./data/capsule-sms.sqlite");
const db = openDatabase(dbPath);
const admin = await ensureAdmin(db, adminUsername, adminPassword);
if (admin.created) console.info(`Created administrator: ${adminUsername}`);

const app = createApp({ db, config });
const server = app.listen(config.port, config.host, () => {
  console.info(`Capsule SMS Sync listening on ${config.host}:${config.port}`);
  console.info(`Public URL: ${config.publicBaseUrl}`);
});

function shutdown(signal) {
  console.info(`${signal}: shutting down`);
  server.close(() => {
    db.close();
    process.exit(0);
  });
}
process.on("SIGINT", () => shutdown("SIGINT"));
process.on("SIGTERM", () => shutdown("SIGTERM"));

function integerEnv(name, fallback) {
  const value = Number.parseInt(process.env[name] ?? String(fallback), 10);
  if (!Number.isSafeInteger(value) || value <= 0) throw new Error(`${name} 必须为正整数`);
  return value;
}

function normalizeBaseUrl(value) {
  const url = new URL(value);
  url.pathname = url.pathname.replace(/\/$/, "");
  url.search = "";
  url.hash = "";
  return url.toString().replace(/\/$/, "");
}
