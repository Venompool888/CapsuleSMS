import express from "express";
import QRCode from "qrcode";
import { fileURLToPath } from "node:url";
import {
  cleanExpired,
  consumeEnrollment,
  createDevice,
  createEnrollment,
  createSession,
  createUser,
  replaceRoutes,
} from "./db.js";
import {
  hashPassword,
  normalizeUsername,
  tokenHash,
  verifyPassword,
} from "./security.js";
import {
  adminPage,
  enrollmentPage,
  loginPage,
  portalPage,
} from "./views.js";

const COOKIE_NAME = "capsule_session";
const MAX_ENVELOPE_BYTES = 64 * 1024;
const MAX_PENDING_PER_TARGET = 10_000;
const RECEIVE_ONLY_PLATFORM = "fluidcapsule-android";

export function createApp({ db, config }) {
  const app = express();
  const messageWaiters = new Map();
  app.disable("x-powered-by");
  if (config.trustProxy) app.set("trust proxy", 1);
  app.use(securityHeaders);
  app.use(express.json({ limit: "96kb" }));
  app.use(express.urlencoded({ extended: false, limit: "32kb" }));
  app.use("/assets", express.static(fileURLToPath(new URL("../public", import.meta.url)), {
    immutable: true,
    maxAge: "1h",
  }));

  const maxPendingPerTarget = config.maxPendingPerTarget ?? MAX_PENDING_PER_TARGET;
  let lastCleanup = 0;
  app.use((_req, _res, next) => {
    if (Date.now() - lastCleanup > 60_000) {
      cleanExpired(db);
      lastCleanup = Date.now();
    }
    next();
  });

  const limiter = loginLimiter();
  app.get("/healthz", (_req, res) => res.json({ status: "ok", service: "capsule-sms-sync" }));
  app.get("/", (req, res) => res.redirect(readWebUser(req, db) ? "/portal" : "/login"));
  app.get("/login", (req, res) => res.send(loginPage(req.query.error)));
  app.post("/login", limiter, asyncHandler(async (req, res) => {
    requireSecureRequest(req, config);
    const user = await authenticateUser(db, req.body.username, req.body.password);
    if (!user) return res.status(401).send(loginPage("用户名或密码错误"));
    const session = createSession(db, user.id, config.sessionTtlMs);
    res.cookie(COOKIE_NAME, session.token, cookieOptions(req, config, session.expiresAt));
    res.redirect(user.role === "admin" ? "/admin" : "/portal");
  }));
  app.post("/logout", requireWebUser(db), requireCsrf, (req, res) => {
    const token = parseCookies(req.headers.cookie)[COOKIE_NAME];
    if (token) db.prepare("DELETE FROM sessions WHERE token_hash = ?").run(tokenHash(token));
    res.clearCookie(COOKIE_NAME, { path: "/" });
    res.redirect("/login");
  });

  app.get("/admin", requireWebUser(db, "admin"), (req, res) => {
    const users = db.prepare(`
      SELECT u.id, u.username, u.role, u.enabled, COUNT(d.id) AS device_count
      FROM users u LEFT JOIN devices d ON d.user_id = u.id
      GROUP BY u.id ORDER BY u.role, u.username
    `).all();
    res.send(adminPage(req.webUser, users, req.query.message));
  });
  app.post("/admin/users", requireWebUser(db, "admin"), requireCsrf, asyncHandler(async (req, res) => {
    await createUser(db, { username: req.body.username, password: req.body.password });
    res.redirect("/admin?message=" + encodeURIComponent("用户已创建"));
  }));
  app.post("/admin/users/:id/toggle", requireWebUser(db, "admin"), requireCsrf, (req, res) => {
    db.prepare(`
      UPDATE users SET enabled = CASE enabled WHEN 1 THEN 0 ELSE 1 END
      WHERE id = ? AND role = 'user'
    `).run(req.params.id);
    res.redirect("/admin?message=" + encodeURIComponent("用户状态已更新"));
  });
  app.post("/admin/users/:id/password", requireWebUser(db, "admin"), requireCsrf, asyncHandler(async (req, res) => {
    const passwordHash = await hashPassword(req.body.password);
    const changed = db.prepare("UPDATE users SET password_hash = ? WHERE id = ? AND role = 'user'")
      .run(passwordHash, req.params.id);
    if (!changed.changes) throw new Error("用户不存在");
    db.prepare("DELETE FROM sessions WHERE user_id = ?").run(req.params.id);
    res.redirect("/admin?message=" + encodeURIComponent("密码已重置，原网页登录会话已失效"));
  }));

  app.get("/portal", requireWebUser(db), (req, res) => {
    const devices = db.prepare(`
      SELECT id, name, platform, enabled, last_seen_at, created_at
      FROM devices WHERE user_id = ? ORDER BY created_at
    `).all(req.webUser.id);
    const routes = db.prepare(`
      SELECT r.source_device_id, r.target_device_id FROM sync_routes r
      JOIN devices d ON d.id = r.source_device_id WHERE d.user_id = ?
    `).all(req.webUser.id);
    res.send(portalPage(req.webUser, devices, routes, req.query.message));
  });
  app.post("/portal/enrollments", requireWebUser(db), requireCsrf, asyncHandler(async (req, res) => {
    const enrollment = createEnrollment(db, {
      userId: req.webUser.id,
      createdBy: req.webUser.id,
    });
    const uri = enrollmentUri(config.publicBaseUrl, enrollment.code);
    const qrSvg = await QRCode.toString(uri, { type: "svg", margin: 1, width: 280 });
    res.send(enrollmentPage(req.webUser, enrollment, qrSvg, config.publicBaseUrl));
  }));
  app.post("/portal/devices/:id/revoke", requireWebUser(db), requireCsrf, (req, res) => {
    revokeDevice(db, req.params.id, req.webUser.id);
    res.redirect("/portal?message=" + encodeURIComponent("设备已撤销"));
  });
  app.post("/portal/routes", requireWebUser(db), requireCsrf, (req, res) => {
    replaceRoutes(
      db,
      req.webUser.id,
      req.body.deviceId,
      req.body.pushTo,
      req.body.receiveFrom,
    );
    res.redirect("/portal?message=" + encodeURIComponent("同步关系已保存，并自动更新到关联设备"));
  });

  app.post("/api/v1/connect", limiter, asyncHandler(async (req, res) => {
    requireSecureRequest(req, config);
    let user;
    if (req.body.enrollmentCode) {
      const enrollment = consumeEnrollment(db, req.body.enrollmentCode);
      if (!enrollment) return jsonError(res, 401, "invalid_enrollment", "注册码无效、已使用或已过期");
      user = db.prepare("SELECT * FROM users WHERE id = ? AND enabled = 1").get(enrollment.user_id);
    } else {
      user = await authenticateUser(db, req.body.username, req.body.password);
    }
    if (!user) return jsonError(res, 401, "invalid_credentials", "用户名或密码错误");
    const device = createDevice(db, {
      userId: user.id,
      name: req.body.deviceName,
      platform: req.body.platform ?? "android",
      publicKey: req.body.publicKey,
    });
    res.status(201).json({
      deviceId: device.id,
      deviceToken: device.token,
      account: user.username,
      serverTime: Date.now(),
    });
  }));

  app.use("/api/v1", requireDevice(db));
  app.get("/api/v1/me", (req, res) => res.json(deviceState(db, req.device)));
  app.get("/api/v1/devices", (req, res) => res.json(deviceState(db, req.device)));
  app.put("/api/v1/sync-routes", (req, res) => {
    replaceRoutes(
      db,
      req.device.user_id,
      req.device.id,
      req.body.pushTo,
      req.body.receiveFrom,
    );
    res.json(deviceState(db, req.device));
  });
  // Lets a device that is disconnecting retire its own token and routes.
  app.post("/api/v1/devices/self/revoke", (req, res) => {
    revokeDevice(db, req.device.id, req.device.user_id);
    res.json({ revoked: true });
  });
  app.post("/api/v1/messages", (req, res) => {
    // FluidCapsule only displays SMS. Without this, a receive-only device could use the
    // reverse direction of a phone→FluidCapsule route to make the phone send or store SMS.
    if (req.device.platform === RECEIVE_ONLY_PLATFORM) {
      return jsonError(res, 403, "receive_only_device", "此设备只能接收短信");
    }
    const eventId = String(req.body.eventId ?? "");
    if (!/^[A-Za-z0-9._:-]{8,128}$/.test(eventId)) {
      return jsonError(res, 400, "invalid_event_id", "eventId 格式无效");
    }
    const envelopeBody = req.body.envelope;
    const envelope = JSON.stringify(envelopeBody);
    if (!envelopeBody || Buffer.byteLength(envelope) > MAX_ENVELOPE_BYTES) {
      return jsonError(res, 413, "invalid_envelope", "加密信封为空或超过 64 KiB");
    }
    const requestedTarget = String(req.body.targetDeviceId ?? "").trim();
    const routedTargets = requestedTarget
      ? db.prepare(`
          SELECT d.id FROM devices d
          WHERE d.id = ? AND d.user_id = ? AND d.enabled = 1 AND d.id != ?
            AND EXISTS (
              SELECT 1 FROM sync_routes r
              WHERE (r.source_device_id = ? AND r.target_device_id = d.id)
                 OR (r.source_device_id = d.id AND r.target_device_id = ?)
            )
        `).all(requestedTarget, req.device.user_id, req.device.id,
          req.device.id, req.device.id)
      : db.prepare(`
          SELECT d.id FROM sync_routes r JOIN devices d ON d.id = r.target_device_id
          WHERE r.source_device_id = ? AND d.enabled = 1
        `).all(req.device.id);
    // A route can be added after the sender fetched its recipients. That device
    // cannot decrypt an envelope until the sender has wrapped a key for it.
    const keyedTargets = envelopeBody.version === 1 && envelopeBody.algorithm === "ECDH-P256+A256GCM"
      ? routedTargets.filter(({ id }) => Object.hasOwn(envelopeBody.wrappedKeys ?? {}, id))
      : routedTargets;
    const now = Date.now();
    // A device that never comes back (lost phone, wiped app) would otherwise collect a week of
    // ciphertext without bound. Past the cap it is skipped and left out of acceptedTargets.
    const pendingFor = db.prepare(`
      SELECT COUNT(*) AS count FROM message_deliveries
      WHERE target_device_id = ? AND acknowledged_at IS NULL AND expires_at > ?
    `);
    const targets = keyedTargets.filter(({ id }) => pendingFor.get(id, now).count < maxPendingPerTarget);
    const expiresAt = now + config.messageTtlMs;
    const insert = db.prepare(`
      INSERT OR IGNORE INTO message_deliveries
      (event_id, source_device_id, target_device_id, envelope, created_at, expires_at)
      VALUES (?, ?, ?, ?, ?, ?)
    `);
    for (const target of targets) {
      const result = insert.run(eventId, req.device.id, target.id, envelope, now, expiresAt);
      if (result.changes) notifyMessageWaiters(messageWaiters, target.id);
    }
    res.status(202).json({ eventId, acceptedTargets: targets.map((target) => target.id), expiresAt });
  });
  app.get("/api/v1/messages/wait", (req, res) => {
    const requested = Number.parseInt(req.query.timeout ?? "25", 10) || 25;
    const timeoutSeconds = Math.min(30, Math.max(1, requested));
    const hasPending = () => Boolean(db.prepare(`
      SELECT 1 FROM message_deliveries
      WHERE target_device_id = ? AND acknowledged_at IS NULL AND expires_at > ?
      LIMIT 1
    `).get(req.device.id, Date.now()));
    if (hasPending()) return res.json({ available: true, serverTime: Date.now() });

    let finished = false;
    const deviceWaiters = messageWaiters.get(req.device.id) ?? new Set();
    messageWaiters.set(req.device.id, deviceWaiters);
    const cleanup = () => {
      deviceWaiters.delete(wake);
      if (!deviceWaiters.size) messageWaiters.delete(req.device.id);
    };
    const finish = (available) => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      cleanup();
      if (!res.headersSent && !res.destroyed) res.json({ available, serverTime: Date.now() });
    };
    const wake = () => finish(true);
    deviceWaiters.add(wake);
    const timer = setTimeout(() => finish(false), timeoutSeconds * 1000);
    req.on("close", () => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      cleanup();
    });
    // Close the small race between the first query and waiter registration.
    if (hasPending()) finish(true);
  });
  app.get("/api/v1/messages", (req, res) => {
    const after = Math.max(0, Number.parseInt(req.query.after ?? "0", 10) || 0);
    const rows = db.prepare(`
      SELECT m.id, m.event_id, m.source_device_id, d.name AS source_device_name,
             m.envelope, m.created_at, m.expires_at
      FROM message_deliveries m JOIN devices d ON d.id = m.source_device_id
      WHERE m.target_device_id = ? AND m.id > ? AND m.acknowledged_at IS NULL
        AND m.expires_at > ? ORDER BY m.id LIMIT 100
    `).all(req.device.id, after, Date.now());
    res.json({
      messages: rows.map((row) => ({
        deliveryId: row.id,
        eventId: row.event_id,
        sourceDeviceId: row.source_device_id,
        sourceDeviceName: row.source_device_name,
        envelope: JSON.parse(row.envelope),
        createdAt: row.created_at,
        expiresAt: row.expires_at,
      })),
      nextCursor: rows.length ? rows.at(-1).id : after,
    });
  });
  app.post("/api/v1/messages/:id/ack", (req, res) => {
    const result = db.prepare(`
      UPDATE message_deliveries SET acknowledged_at = ?, envelope = 'null'
      WHERE id = ? AND target_device_id = ? AND acknowledged_at IS NULL
    `).run(Date.now(), Number(req.params.id), req.device.id);
    if (!result.changes) return jsonError(res, 404, "not_found", "消息不存在或已确认");
    res.status(204).end();
  });

  app.use((error, req, res, _next) => {
    const isApi = req.path.startsWith("/api/");
    const status = error.status ?? 400;
    if (isApi) return jsonError(res, status, "request_failed", error.message || "请求失败");
    res.status(status).send(loginPage(error.message || "请求失败"));
  });

  return app;
}

function notifyMessageWaiters(waiters, deviceId) {
  for (const wake of [...(waiters.get(deviceId) ?? [])]) wake();
}

function deviceState(db, device) {
  const devices = db.prepare(`
    SELECT id, name, platform, public_key, last_seen_at, created_at
    FROM devices WHERE user_id = ? AND enabled = 1 ORDER BY created_at
  `).all(device.user_id).map((entry) => ({
    id: entry.id,
    name: entry.name,
    platform: entry.platform,
    publicKey: entry.public_key,
    lastSeenAt: entry.last_seen_at,
    createdAt: entry.created_at,
  }));
  const routes = db.prepare(`
    SELECT source_device_id, target_device_id FROM sync_routes
    WHERE source_device_id IN (SELECT id FROM devices WHERE user_id = ?)
  `).all(device.user_id);
  return {
    currentDeviceId: device.id,
    account: device.username,
    devices,
    pushTo: routes.filter((route) => route.source_device_id === device.id).map((route) => route.target_device_id),
    receiveFrom: routes.filter((route) => route.target_device_id === device.id).map((route) => route.source_device_id),
  };
}

async function authenticateUser(db, username, password) {
  let normalized;
  try {
    normalized = normalizeUsername(username);
  } catch {
    return null;
  }
  const user = db.prepare("SELECT * FROM users WHERE username = ? AND enabled = 1").get(normalized);
  if (!user || !(await verifyPassword(String(password ?? ""), user.password_hash))) return null;
  return user;
}

function requireWebUser(db, role = null) {
  return (req, res, next) => {
    const user = readWebUser(req, db);
    if (!user) return res.redirect("/login");
    if (role && user.role !== role) return res.status(403).send("Forbidden");
    req.webUser = user;
    next();
  };
}

function readWebUser(req, db) {
  const token = parseCookies(req.headers.cookie)[COOKIE_NAME];
  if (!token) return null;
  return db.prepare(`
    SELECT u.id, u.username, u.role, s.csrf_token AS csrf
    FROM sessions s JOIN users u ON u.id = s.user_id
    WHERE s.token_hash = ? AND s.expires_at > ? AND u.enabled = 1
  `).get(tokenHash(token), Date.now()) ?? null;
}

function requireCsrf(req, res, next) {
  if (!req.webUser || req.body.csrf !== req.webUser.csrf) return res.status(403).send("CSRF validation failed");
  next();
}

function requireDevice(db) {
  return (req, res, next) => {
    const match = /^Bearer\s+(.+)$/i.exec(req.headers.authorization ?? "");
    if (!match) return jsonError(res, 401, "device_auth_required", "缺少设备令牌");
    const device = db.prepare(`
      SELECT d.*, u.username FROM devices d JOIN users u ON u.id = d.user_id
      WHERE d.token_hash = ? AND d.enabled = 1 AND u.enabled = 1
    `).get(tokenHash(match[1]));
    if (!device) return jsonError(res, 401, "invalid_device_token", "设备令牌无效或已撤销");
    db.prepare("UPDATE devices SET last_seen_at = ? WHERE id = ?").run(Date.now(), device.id);
    req.device = device;
    next();
  };
}

function revokeDevice(db, deviceId, userId) {
  db.exec("BEGIN IMMEDIATE");
  try {
    const changed = db.prepare("UPDATE devices SET enabled = 0 WHERE id = ? AND user_id = ?")
      .run(deviceId, userId);
    if (changed.changes) {
      db.prepare("DELETE FROM sync_routes WHERE source_device_id = ? OR target_device_id = ?")
        .run(deviceId, deviceId);
      // Ciphertext waiting for a revoked device can never be fetched; don't keep it for the TTL.
      db.prepare("DELETE FROM message_deliveries WHERE target_device_id = ?").run(deviceId);
    }
    db.exec("COMMIT");
    return changed.changes > 0;
  } catch (error) {
    db.exec("ROLLBACK");
    throw error;
  }
}

function requireSecureRequest(req, config) {
  if (!config.allowInsecureHttp && !req.secure) {
    const error = new Error("账号密码和设备注册码只能通过 HTTPS 发送");
    error.status = 426;
    throw error;
  }
}

function securityHeaders(_req, res, next) {
  res.set({
    "Cache-Control": "no-store",
    "Content-Security-Policy": "default-src 'self'; img-src 'self' data:; style-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
    "Cross-Origin-Opener-Policy": "same-origin",
    "Referrer-Policy": "no-referrer",
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
  });
  next();
}

function cookieOptions(req, config, expiresAt) {
  return {
    httpOnly: true,
    sameSite: "strict",
    secure: req.secure || !config.allowInsecureHttp,
    path: "/",
    expires: new Date(expiresAt),
  };
}

function parseCookies(header = "") {
  return Object.fromEntries(header.split(";").map((part) => part.trim()).filter(Boolean).map((part) => {
    const index = part.indexOf("=");
    return [decodeURIComponent(part.slice(0, index)), decodeURIComponent(part.slice(index + 1))];
  }));
}

function enrollmentUri(baseUrl, code) {
  const query = new URLSearchParams({ server: baseUrl, code });
  return `capsulesms://enroll?${query}`;
}

function jsonError(res, status, code, message) {
  return res.status(status).json({ error: code, message });
}

function asyncHandler(handler) {
  return (req, res, next) => Promise.resolve(handler(req, res, next)).catch(next);
}

function loginLimiter() {
  const attempts = new Map();
  return (req, res, next) => {
    const now = Date.now();
    const key = req.ip;
    const previous = attempts.get(key) ?? [];
    const recent = previous.filter((time) => now - time < 15 * 60_000);
    if (recent.length >= 20) return jsonError(res, 429, "rate_limited", "尝试次数过多，请稍后再试");
    recent.push(now);
    attempts.set(key, recent);
    next();
  };
}
