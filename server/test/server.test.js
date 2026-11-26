import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { createApp } from "../src/app.js";
import {
  createEnrollment,
  createUser,
  openDatabase,
} from "../src/db.js";

const db = openDatabase(":memory:");
const config = {
  publicBaseUrl: "http://127.0.0.1",
  sessionTtlMs: 60 * 60 * 1000,
  messageTtlMs: 24 * 60 * 60 * 1000,
  allowInsecureHttp: true,
  trustProxy: false,
};
let server;
let baseUrl;
let userId;

before(async () => {
  userId = await createUser(db, {
    username: "sync-user",
    password: "correct-horse-battery-staple",
  });
  server = createApp({ db, config }).listen(0, "127.0.0.1");
  await new Promise((resolve) => server.once("listening", resolve));
  baseUrl = `http://127.0.0.1:${server.address().port}`;
});

after(async () => {
  await new Promise((resolve) => server.close(resolve));
  db.close();
});

test("health endpoint is available", async () => {
  const response = await fetch(`${baseUrl}/healthz`);
  assert.equal(response.status, 200);
  assert.equal((await response.json()).status, "ok");
});

test("management UI stylesheet is served from a unicode workspace path", async () => {
  const response = await fetch(`${baseUrl}/assets/style.css`);
  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type"), /text\/css/);
  assert.match(await response.text(), /--blue:#2868f0/);
});

test("credential connection is rejected over HTTP unless explicitly enabled", async () => {
  const secureOnly = createApp({ db, config: { ...config, allowInsecureHttp: false } })
    .listen(0, "127.0.0.1");
  await new Promise((resolve) => secureOnly.once("listening", resolve));
  const secureBase = `http://127.0.0.1:${secureOnly.address().port}`;
  try {
    const response = await fetch(`${secureBase}/api/v1/connect`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        username: "sync-user",
        password: "correct-horse-battery-staple",
        deviceName: "Unsafe HTTP",
      }),
    });
    assert.equal(response.status, 426);
    assert.equal((await response.json()).error, "request_failed");
  } finally {
    await new Promise((resolve) => secureOnly.close(resolve));
  }
});

test("password login registers devices and one route appears on both ends", async () => {
  const pixel = await connect({
    username: "sync-user",
    password: "correct-horse-battery-staple",
    deviceName: "Pixel 10",
    platform: "android-17",
    publicKey: "pixel-public-key",
  });
  const oppo = await connect({
    username: "sync-user",
    password: "correct-horse-battery-staple",
    deviceName: "OPPO",
    platform: "coloros",
    publicKey: "oppo-public-key",
  });

  const changed = await api(pixel.deviceToken, "/api/v1/sync-routes", {
    method: "PUT",
    body: { pushTo: [oppo.deviceId], receiveFrom: [] },
  });
  assert.deepEqual(changed.pushTo, [oppo.deviceId]);

  const oppoState = await api(oppo.deviceToken, "/api/v1/devices");
  assert.deepEqual(oppoState.receiveFrom, [pixel.deviceId]);
  assert.equal(oppoState.devices.find((device) => device.id === pixel.deviceId).publicKey, "pixel-public-key");

  const reverseReply = await api(oppo.deviceToken, "/api/v1/messages", {
    method: "POST",
    body: {
      eventId: "oppo-reply-command-0001",
      targetDeviceId: pixel.deviceId,
      envelope: { algorithm: "test-only", ciphertext: "encrypted-reply-command" },
    },
    expectedStatus: 202,
  });
  assert.deepEqual(reverseReply.acceptedTargets, [pixel.deviceId]);
  const pixelInbox = await api(pixel.deviceToken, "/api/v1/messages");
  assert.equal(pixelInbox.messages[0].sourceDeviceId, oppo.deviceId);
  await api(pixel.deviceToken, `/api/v1/messages/${pixelInbox.messages[0].deliveryId}/ack`, {
    method: "POST",
    expectedStatus: 204,
  });

  const accepted = await api(pixel.deviceToken, "/api/v1/messages", {
    method: "POST",
    body: {
      eventId: "pixel-event-0001",
      envelope: { algorithm: "test-only", ciphertext: "opaque-ciphertext" },
    },
    expectedStatus: 202,
  });
  assert.deepEqual(accepted.acceptedTargets, [oppo.deviceId]);

  const inbox = await api(oppo.deviceToken, "/api/v1/messages");
  assert.equal(inbox.messages.length, 1);
  assert.equal(inbox.messages[0].sourceDeviceName, "Pixel 10");
  assert.equal(inbox.messages[0].envelope.ciphertext, "opaque-ciphertext");

  await api(oppo.deviceToken, `/api/v1/messages/${inbox.messages[0].deliveryId}/ack`, {
    method: "POST",
    expectedStatus: 204,
  });
  const afterAck = await api(oppo.deviceToken, "/api/v1/messages");
  assert.equal(afterAck.messages.length, 0);

  const waitStarted = Date.now();
  const waitForMessage = api(oppo.deviceToken, "/api/v1/messages/wait?timeout=5");
  await new Promise((resolve) => setTimeout(resolve, 50));
  await api(pixel.deviceToken, "/api/v1/messages", {
    method: "POST",
    body: {
      eventId: "pixel-event-realtime-0002",
      envelope: { algorithm: "test-only", ciphertext: "another-opaque-envelope" },
    },
    expectedStatus: 202,
  });
  const wake = await waitForMessage;
  assert.equal(wake.available, true);
  assert.ok(Date.now() - waitStarted < 2_000);
  const realtimeInbox = await api(oppo.deviceToken, "/api/v1/messages");
  await api(oppo.deviceToken, `/api/v1/messages/${realtimeInbox.messages[0].deliveryId}/ack`, {
    method: "POST",
    expectedStatus: 204,
  });
});

test("one-time enrollment code can only register one device", async () => {
  const enrollment = createEnrollment(db, { userId, createdBy: userId });
  const first = await connect({
    enrollmentCode: enrollment.code,
    deviceName: "Tablet",
    platform: "android",
  });
  assert.ok(first.deviceToken);

  const response = await fetch(`${baseUrl}/api/v1/connect`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      enrollmentCode: enrollment.code,
      deviceName: "Replay",
      platform: "android",
    }),
  });
  assert.equal(response.status, 401);
  assert.equal((await response.json()).error, "invalid_enrollment");
});

test("routes cannot target a device from another account", async () => {
  await createUser(db, { username: "other-user", password: "another-long-test-password" });
  const own = await connect({
    username: "sync-user",
    password: "correct-horse-battery-staple",
    deviceName: "Own device",
  });
  const other = await connect({
    username: "other-user",
    password: "another-long-test-password",
    deviceName: "Other device",
  });
  const state = await api(own.deviceToken, "/api/v1/sync-routes", {
    method: "PUT",
    body: { pushTo: [other.deviceId], receiveFrom: [] },
  });
  assert.deepEqual(state.pushTo, []);
});

test("a receive-only FluidCapsule device cannot post messages, even back along a route", async () => {
  const credentials = { username: "sync-user", password: "correct-horse-battery-staple" };
  const phone = await connect({ ...credentials, deviceName: "Phone", platform: "android-17",
    publicKey: "phone-public-key" });
  const capsule = await connect({ ...credentials, deviceName: "FluidCapsule",
    platform: "fluidcapsule-android", publicKey: "capsule-public-key" });
  await api(phone.deviceToken, "/api/v1/sync-routes", {
    method: "PUT",
    body: { pushTo: [capsule.deviceId], receiveFrom: [] },
  });
  for (const body of [
    { eventId: `${capsule.deviceId}:send-0001`, targetDeviceId: phone.deviceId,
      envelope: { algorithm: "test-only", ciphertext: "send-command" } },
    { eventId: `${capsule.deviceId}:push-0001`,
      envelope: { algorithm: "test-only", ciphertext: "fake-sms" } },
  ]) {
    const rejected = await api(capsule.deviceToken, "/api/v1/messages", {
      method: "POST", body, expectedStatus: 403,
    });
    assert.equal(rejected.error, "receive_only_device");
  }
  const phoneInbox = await api(phone.deviceToken, "/api/v1/messages");
  assert.equal(phoneInbox.messages.length, 0);
});

test("a disconnecting device can revoke itself, which drops its token and routes", async () => {
  const credentials = { username: "sync-user", password: "correct-horse-battery-staple" };
  const leaving = await connect({ ...credentials, deviceName: "Leaving phone" });
  const staying = await connect({ ...credentials, deviceName: "Staying tablet" });
  await api(leaving.deviceToken, "/api/v1/sync-routes", {
    method: "PUT",
    body: { pushTo: [staying.deviceId], receiveFrom: [staying.deviceId] },
  });
  assert.ok((await api(staying.deviceToken, "/api/v1/devices")).receiveFrom.includes(leaving.deviceId));

  assert.deepEqual(
    await api(leaving.deviceToken, "/api/v1/devices/self/revoke", { method: "POST", body: {} }),
    { revoked: true },
  );

  await api(leaving.deviceToken, "/api/v1/devices", { expectedStatus: 401 });
  const after = await api(staying.deviceToken, "/api/v1/devices");
  assert.equal(after.receiveFrom.includes(leaving.deviceId), false);
  assert.equal(after.pushTo.includes(leaving.deviceId), false);
  assert.equal(after.devices.some((device) => device.id === leaving.deviceId), false);
});

test("revoking a device drops the deliveries still waiting for it", async () => {
  const credentials = { username: "sync-user", password: "correct-horse-battery-staple" };
  const sender = await connect({ ...credentials, deviceName: "Sender" });
  const lost = await connect({ ...credentials, deviceName: "Lost phone" });
  await api(sender.deviceToken, "/api/v1/sync-routes", {
    method: "PUT", body: { pushTo: [lost.deviceId], receiveFrom: [] },
  });
  await api(sender.deviceToken, "/api/v1/messages", {
    method: "POST", expectedStatus: 202,
    body: { eventId: `${sender.deviceId}:lost-0001`,
      envelope: { algorithm: "test-only", ciphertext: "x" } },
  });
  const pending = () => db.prepare(
    "SELECT COUNT(*) AS count FROM message_deliveries WHERE target_device_id = ?",
  ).get(lost.deviceId).count;
  assert.equal(pending(), 1);
  await api(lost.deviceToken, "/api/v1/devices/self/revoke", { method: "POST", body: {} });
  assert.equal(pending(), 0);
});

test("a target that never collects stops accepting new deliveries past the cap", async () => {
  const capped = createApp({ db, config: { ...config, maxPendingPerTarget: 2 } }).listen(0, "127.0.0.1");
  await new Promise((resolve) => capped.once("listening", resolve));
  const cappedBase = `http://127.0.0.1:${capped.address().port}`;
  try {
    const credentials = { username: "sync-user", password: "correct-horse-battery-staple" };
    const sender = await connect({ ...credentials, deviceName: "Busy sender" });
    const idle = await connect({ ...credentials, deviceName: "Idle receiver" });
    await api(sender.deviceToken, "/api/v1/sync-routes", {
      method: "PUT", body: { pushTo: [idle.deviceId], receiveFrom: [] },
    });
    const accepted = [];
    for (let i = 1; i <= 3; i++) {
      const response = await fetch(`${cappedBase}/api/v1/messages`, {
        method: "POST",
        headers: { authorization: `Bearer ${sender.deviceToken}`, "content-type": "application/json" },
        body: JSON.stringify({ eventId: `${sender.deviceId}:cap-000${i}`,
          envelope: { algorithm: "test-only", ciphertext: `m${i}` } }),
      });
      assert.equal(response.status, 202);
      accepted.push((await response.json()).acceptedTargets);
    }
    assert.deepEqual(accepted, [[idle.deviceId], [idle.deviceId], []]);
    assert.equal((await api(idle.deviceToken, "/api/v1/messages")).messages.length, 2);
  } finally {
    await new Promise((resolve) => capped.close(resolve));
  }
});

test("a page of deliveries can be acknowledged in one request", async () => {
  const credentials = { username: "sync-user", password: "correct-horse-battery-staple" };
  const sender = await connect({ ...credentials, deviceName: "Batch sender" });
  const receiver = await connect({ ...credentials, deviceName: "Batch receiver" });
  const bystander = await connect({ ...credentials, deviceName: "Bystander" });
  await api(sender.deviceToken, "/api/v1/sync-routes", {
    method: "PUT", body: { pushTo: [receiver.deviceId], receiveFrom: [] },
  });
  for (let i = 1; i <= 3; i++) {
    await api(sender.deviceToken, "/api/v1/messages", {
      method: "POST", expectedStatus: 202,
      body: { eventId: `${sender.deviceId}:batch-000${i}`,
        envelope: { algorithm: "test-only", ciphertext: `m${i}` } },
    });
  }
  const ids = (await api(receiver.deviceToken, "/api/v1/messages")).messages.map((m) => m.deliveryId);
  assert.equal(ids.length, 3);

  const foreign = await api(bystander.deviceToken, "/api/v1/messages/ack", {
    method: "POST", body: { deliveryIds: ids },
  });
  assert.equal(foreign.acknowledged, 0);

  const first = await api(receiver.deviceToken, "/api/v1/messages/ack", {
    method: "POST", body: { deliveryIds: [ids[0], ids[1], ids[1], 987654321] },
  });
  assert.equal(first.acknowledged, 2);
  const again = await api(receiver.deviceToken, "/api/v1/messages/ack", {
    method: "POST", body: { deliveryIds: [ids[0]] },
  });
  assert.equal(again.acknowledged, 0);
  assert.deepEqual(
    (await api(receiver.deviceToken, "/api/v1/messages")).messages.map((m) => m.deliveryId),
    [ids[2]],
  );

  for (const deliveryIds of [[], ["1"], [0], new Array(501).fill(1), undefined]) {
    const rejected = await api(receiver.deviceToken, "/api/v1/messages/ack", {
      method: "POST", body: { deliveryIds }, expectedStatus: 400,
    });
    assert.equal(rejected.error, "invalid_ack");
  }
});

test("web pages show notices by code only, never text from the link", async () => {
  const session = await webLogin();
  const crafted = await session.get("/portal?message=" + encodeURIComponent("请访问 evil.example") + "&notice=nope");
  assert.doesNotMatch(crafted, /evil\.example/);
  assert.doesNotMatch(crafted, /alert success/);
  assert.match(await session.get("/portal?notice=device-revoked"), /设备已撤销/);
  const login = await (await fetch(`${baseUrl}/login?error=${encodeURIComponent("请访问 evil.example")}`)).text();
  assert.doesNotMatch(login, /evil\.example/);
});

test("a failed web action keeps you signed in and shows what went wrong", async () => {
  const session = await webLogin();
  const response = await session.post("/portal/routes", { csrf: session.csrf, deviceId: "no-such-device" });
  assert.equal(response.status, 400);
  const page = await response.text();
  assert.match(page, /设备不存在或已停用/);
  assert.match(page, /退出/);
  assert.doesNotMatch(page, /name="password"/);

  const stale = await session.post("/portal/routes", { csrf: "stale", deviceId: "x" });
  assert.equal(stale.status, 403);
  assert.match(await stale.text(), /页面已过期/);

  const forbidden = await fetch(`${baseUrl}/admin`, { headers: { cookie: session.cookie } });
  assert.equal(forbidden.status, 403);
  assert.match(await forbidden.text(), /只有管理员/);
});

test("a duplicate username gets a plain message instead of a database error", async () => {
  await assert.rejects(
    createUser(db, { username: "sync-user", password: "another-long-password" }),
    (error) => error.expose === true && error.message === "这个用户名已被使用",
  );
});

async function webLogin() {
  const response = await fetch(`${baseUrl}/login`, {
    method: "POST",
    redirect: "manual",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ username: "sync-user", password: "correct-horse-battery-staple" }),
  });
  assert.equal(response.status, 302);
  const cookie = response.headers.get("set-cookie").split(";")[0];
  const get = async (path) => (await fetch(`${baseUrl}${path}`, { headers: { cookie } })).text();
  const csrf = /name="csrf" value="([^"]+)"/.exec(await get("/portal"))[1];
  const post = (path, form) => fetch(`${baseUrl}${path}`, {
    method: "POST",
    redirect: "manual",
    headers: { cookie, "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams(form),
  });
  return { cookie, csrf, get, post };
}

async function connect(body) {
  const response = await fetch(`${baseUrl}/api/v1/connect`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  if (response.status !== 201) {
    assert.fail(`Expected 201, received ${response.status}: ${await response.text()}`);
  }
  return response.json();
}

async function api(token, path, options = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    method: options.method ?? "GET",
    headers: {
      authorization: `Bearer ${token}`,
      ...(options.body ? { "content-type": "application/json" } : {}),
    },
    body: options.body ? JSON.stringify(options.body) : undefined,
  });
  const expectedStatus = options.expectedStatus ?? 200;
  if (response.status !== expectedStatus) {
    assert.fail(`Expected ${expectedStatus}, received ${response.status}: ${await response.text()}`);
  }
  return response.status === 204 ? null : response.json();
}
