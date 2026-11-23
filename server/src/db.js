import { mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { randomBytes, randomUUID } from "node:crypto";
import { DatabaseSync } from "node:sqlite";
import { hashPassword, normalizeUsername, randomToken, tokenHash } from "./security.js";

export function openDatabase(path) {
  if (path !== ":memory:") mkdirSync(dirname(path), { recursive: true });
  const db = new DatabaseSync(path, { timeout: 5000 });
  db.exec(`
    PRAGMA foreign_keys = ON;
    PRAGMA journal_mode = WAL;
    CREATE TABLE IF NOT EXISTS users (
      id TEXT PRIMARY KEY,
      username TEXT NOT NULL UNIQUE,
      password_hash TEXT NOT NULL,
      role TEXT NOT NULL CHECK (role IN ('admin', 'user')),
      enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
      created_at INTEGER NOT NULL
    ) STRICT;
    CREATE TABLE IF NOT EXISTS sessions (
      token_hash TEXT PRIMARY KEY,
      user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      csrf_token TEXT NOT NULL,
      expires_at INTEGER NOT NULL,
      created_at INTEGER NOT NULL
    ) STRICT;
    CREATE TABLE IF NOT EXISTS devices (
      id TEXT PRIMARY KEY,
      user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      name TEXT NOT NULL,
      platform TEXT NOT NULL,
      token_hash TEXT NOT NULL UNIQUE,
      public_key TEXT,
      enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
      last_seen_at INTEGER,
      created_at INTEGER NOT NULL
    ) STRICT;
    CREATE TABLE IF NOT EXISTS enrollments (
      id TEXT PRIMARY KEY,
      user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      code_hash TEXT NOT NULL UNIQUE,
      created_by TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      expires_at INTEGER NOT NULL,
      used_at INTEGER,
      created_at INTEGER NOT NULL
    ) STRICT;
    CREATE TABLE IF NOT EXISTS sync_routes (
      source_device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
      target_device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
      created_at INTEGER NOT NULL,
      PRIMARY KEY (source_device_id, target_device_id),
      CHECK (source_device_id <> target_device_id)
    ) STRICT;
    CREATE TABLE IF NOT EXISTS message_deliveries (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      event_id TEXT NOT NULL,
      source_device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
      target_device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
      envelope TEXT NOT NULL,
      created_at INTEGER NOT NULL,
      expires_at INTEGER NOT NULL,
      acknowledged_at INTEGER,
      UNIQUE (event_id, target_device_id)
    ) STRICT;
    CREATE INDEX IF NOT EXISTS message_target_cursor
      ON message_deliveries(target_device_id, id);
  `);
  return db;
}

export async function ensureAdmin(db, username, password) {
  const normalized = normalizeUsername(username);
  const existing = db.prepare("SELECT id, role FROM users WHERE username = ?").get(normalized);
  if (existing) {
    if (existing.role !== "admin") throw new Error("ADMIN_USERNAME 已被普通用户占用");
    return { created: false, id: existing.id };
  }
  const id = randomUUID();
  db.prepare(`
    INSERT INTO users (id, username, password_hash, role, enabled, created_at)
    VALUES (?, ?, ?, 'admin', 1, ?)
  `).run(id, normalized, await hashPassword(password), Date.now());
  return { created: true, id };
}

export async function createUser(db, { username, password, role = "user" }) {
  const id = randomUUID();
  db.prepare(`
    INSERT INTO users (id, username, password_hash, role, enabled, created_at)
    VALUES (?, ?, ?, ?, 1, ?)
  `).run(id, normalizeUsername(username), await hashPassword(password), role, Date.now());
  return id;
}

export function createSession(db, userId, ttlMs) {
  const token = randomToken();
  const csrf = randomToken(24);
  const now = Date.now();
  db.prepare(`
    INSERT INTO sessions (token_hash, user_id, csrf_token, expires_at, created_at)
    VALUES (?, ?, ?, ?, ?)
  `).run(tokenHash(token), userId, csrf, now + ttlMs, now);
  return { token, csrf, expiresAt: now + ttlMs };
}

export function createDevice(db, { userId, name, platform, publicKey = null }) {
  const id = randomUUID();
  const token = randomToken();
  const now = Date.now();
  db.prepare(`
    INSERT INTO devices (id, user_id, name, platform, token_hash, public_key, created_at)
    VALUES (?, ?, ?, ?, ?, ?, ?)
  `).run(id, userId, cleanDeviceName(name), cleanPlatform(platform), tokenHash(token), cleanPublicKey(publicKey), now);
  return { id, token, createdAt: now };
}

export function createEnrollment(db, { userId, createdBy, ttlMs = 10 * 60 * 1000 }) {
  const id = randomUUID();
  const code = `${friendlyCode(4)}-${friendlyCode(4)}`;
  const now = Date.now();
  db.prepare(`
    INSERT INTO enrollments (id, user_id, code_hash, created_by, expires_at, created_at)
    VALUES (?, ?, ?, ?, ?, ?)
  `).run(id, userId, tokenHash(code), createdBy, now + ttlMs, now);
  return { id, code, expiresAt: now + ttlMs };
}

export function consumeEnrollment(db, code) {
  const now = Date.now();
  db.exec("BEGIN IMMEDIATE");
  try {
    const enrollment = db.prepare(`
      SELECT * FROM enrollments
      WHERE code_hash = ? AND used_at IS NULL AND expires_at > ?
    `).get(tokenHash(String(code ?? "").trim().toUpperCase()), now);
    if (!enrollment) {
      db.exec("ROLLBACK");
      return null;
    }
    db.prepare("UPDATE enrollments SET used_at = ? WHERE id = ?").run(now, enrollment.id);
    db.exec("COMMIT");
    return enrollment;
  } catch (error) {
    db.exec("ROLLBACK");
    throw error;
  }
}

export function replaceRoutes(db, userId, deviceId, pushTo, receiveFrom) {
  const devices = db.prepare(
    "SELECT id, platform FROM devices WHERE user_id = ? AND enabled = 1",
  ).all(userId);
  const allowed = new Set(devices.map(({ id }) => id));
  const sources = new Set(devices.filter(({ platform }) => platform !== "fluidcapsule-android")
    .map(({ id }) => id));
  if (!allowed.has(deviceId)) throw new Error("设备不存在或已停用");
  const outgoing = sources.has(deviceId) ? uniqueDeviceIds(pushTo, allowed, deviceId) : [];
  const incoming = uniqueDeviceIds(receiveFrom, sources, deviceId);
  const now = Date.now();
  db.exec("BEGIN IMMEDIATE");
  try {
    db.prepare("DELETE FROM sync_routes WHERE source_device_id = ?").run(deviceId);
    db.prepare("DELETE FROM sync_routes WHERE target_device_id = ?").run(deviceId);
    const insert = db.prepare(`
      INSERT OR IGNORE INTO sync_routes (source_device_id, target_device_id, created_at)
      VALUES (?, ?, ?)
    `);
    for (const target of outgoing) insert.run(deviceId, target, now);
    for (const source of incoming) insert.run(source, deviceId, now);
    db.exec("COMMIT");
  } catch (error) {
    db.exec("ROLLBACK");
    throw error;
  }
}

export function cleanExpired(db) {
  const now = Date.now();
  db.prepare("DELETE FROM sessions WHERE expires_at <= ?").run(now);
  db.prepare("DELETE FROM enrollments WHERE expires_at <= ? OR used_at IS NOT NULL").run(now);
  db.prepare("DELETE FROM message_deliveries WHERE expires_at <= ? OR acknowledged_at IS NOT NULL").run(now);
}

function uniqueDeviceIds(values, allowed, self) {
  const list = Array.isArray(values) ? values : values == null ? [] : [values];
  return [...new Set(list.map(String))].filter((id) => id !== self && allowed.has(id));
}

function cleanDeviceName(value) {
  const name = String(value ?? "").trim();
  if (name.length < 1 || name.length > 64) throw new Error("设备名称长度必须为 1–64 个字符");
  return name;
}

function cleanPlatform(value) {
  const platform = String(value ?? "android").trim().toLowerCase();
  if (!/^[a-z0-9._-]{1,32}$/.test(platform)) throw new Error("设备平台格式无效");
  return platform;
}

function cleanPublicKey(value) {
  if (value == null || value === "") return null;
  const key = String(value);
  if (key.length > 4096) throw new Error("设备公钥过长");
  return key;
}

function friendlyCode(length) {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  return [...randomBytes(length)].map((byte) => alphabet[byte % alphabet.length]).join("");
}
