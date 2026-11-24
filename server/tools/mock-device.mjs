#!/usr/bin/env node
// A fake Capsule SMS phone that speaks the same encrypted sync protocol as the Android app
// (ECDH P-256 key wrapping + AES-256-GCM, see app/.../sync/CloudCrypto.java). Use it to push
// test SMS to a real phone, receive what the phone forwards, and inspect sync routes.
//
//   node tools/mock-device.mjs connect [--name "Mock Phone"]
//   node tools/mock-device.mjs devices
//   node tools/mock-device.mjs route [--push all|<id,id>] [--receive all|<id,id>]
//   node tools/mock-device.mjs sms --from +61400000000 --body "Your code is 482913" [--type sent]
//   node tools/mock-device.mjs remote-send --to <deviceId> --address +614... --body "hi" --sub 1
//   node tools/mock-device.mjs listen        # print (and acknowledge) incoming messages
//   node tools/mock-device.mjs bad-sms --kind tamper|wrong-sender|bad-json|unknown-kind|oversized
//       # queue one message receivers must skip (oversized: expect the server to reject it)
//
// Options: --server <url> (default from data/dev/credentials.json), --state <name> (default
// "mock") to run several mock devices side by side.
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import {
  createCipheriv, createDecipheriv, createHash, createPrivateKey, createPublicKey,
  diffieHellman, generateKeyPairSync, randomBytes, randomUUID, timingSafeEqual,
} from "node:crypto";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const dataDir = resolve(root, "data/dev");
const ALGORITHM = "ECDH-P256+A256GCM";
const SMS_TYPE = { inbox: 1, sent: 2 };
const FLUID_CAPSULE_PLATFORM = "fluidcapsule-android";
const isMain = !!process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);

const { positionals, values: opts } = parseArgs({
  args: isMain ? process.argv.slice(2) : [],
  allowPositionals: true,
  options: {
    server: { type: "string" },
    state: { type: "string", default: "mock" },
    name: { type: "string", default: "Mock Phone" },
    push: { type: "string" },
    receive: { type: "string" },
    from: { type: "string" },
    to: { type: "string" },
    address: { type: "string" },
    body: { type: "string" },
    kind: { type: "string" },
    type: { type: "string", default: "inbox" },
    sub: { type: "string", default: "-1" },
    once: { type: "boolean", default: false },
  },
});

// ---------- crypto (mirrors CloudCrypto.java) ----------

export function publicKeyBase64(privateKey) {
  return createPublicKey(privateKey).export({ type: "spki", format: "der" }).toString("base64");
}

function decodePublicKey(base64) {
  return createPublicKey({ key: Buffer.from(base64, "base64"), format: "der", type: "spki" });
}

function wrappingKey(privateKey, otherPublicBase64, eventId, targetId) {
  const shared = diffieHellman({ privateKey, publicKey: decodePublicKey(otherPublicBase64) });
  return createHash("sha256")
    .update("capsule-wrap-v1\n")
    .update(shared)
    .update(`\n${eventId}\n${targetId}`)
    .digest();
}

function gcmEncrypt(key, iv, aad, plaintext) {
  const cipher = createCipheriv("aes-256-gcm", key, iv);
  cipher.setAAD(Buffer.from(aad, "utf8"));
  // Java's AES/GCM output is ciphertext followed by the 16-byte tag.
  return Buffer.concat([cipher.update(plaintext), cipher.final(), cipher.getAuthTag()]);
}

function gcmDecrypt(key, iv, aad, data) {
  const decipher = createDecipheriv("aes-256-gcm", key, iv);
  decipher.setAAD(Buffer.from(aad, "utf8"));
  decipher.setAuthTag(data.subarray(data.length - 16));
  return Buffer.concat([decipher.update(data.subarray(0, data.length - 16)), decipher.final()]);
}

export function encryptEnvelope(privateKey, eventId, payload, targets) {
  return encryptPlaintext(privateKey, eventId, Buffer.from(JSON.stringify(payload), "utf8"), targets);
}

function encryptPlaintext(privateKey, eventId, plaintext, targets) {
  const contentKey = randomBytes(32);
  const payloadIv = randomBytes(12);
  const ciphertext = gcmEncrypt(contentKey, payloadIv, eventId, plaintext);
  const wrappedKeys = {};
  for (const target of targets) {
    if (!target.publicKey) throw new Error(`Device "${target.name}" has no public key`);
    const iv = randomBytes(12);
    wrappedKeys[target.id] = {
      iv: iv.toString("base64"),
      ciphertext: gcmEncrypt(wrappingKey(privateKey, target.publicKey, eventId, target.id),
        iv, `${eventId}\n${target.id}`, contentKey).toString("base64"),
    };
  }
  return {
    version: 1,
    algorithm: ALGORITHM,
    senderPublicKey: publicKeyBase64(privateKey),
    payloadIv: payloadIv.toString("base64"),
    ciphertext: ciphertext.toString("base64"),
    wrappedKeys,
  };
}

// Envelopes a receiver must record as an error, acknowledge and skip, without blocking later
// messages. "oversized" is over the server's 64 KiB limit, so the server should reject it.
export const BROKEN_KINDS = ["tamper", "wrong-sender", "bad-json", "unknown-kind", "oversized"];
export const MAX_ENVELOPE_BYTES = 64 * 1024;

export function brokenEnvelope(kind, privateKey, eventId, targets) {
  const sms = { kind: "sms", address: "CloudSyncBad", body: `broken envelope test: ${kind}`,
    timestamp: Date.now(), subscriptionId: -1, type: SMS_TYPE.inbox };
  switch (kind) {
    case "tamper": {
      const envelope = encryptEnvelope(privateKey, eventId, sms, targets);
      const ciphertext = Buffer.from(envelope.ciphertext, "base64");
      ciphertext[0] ^= 0x01; // breaks the GCM tag check
      return { ...envelope, ciphertext: ciphertext.toString("base64") };
    }
    case "wrong-sender": {
      // Signed by a key the server never registered for this device.
      const { privateKey: stranger } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
      return encryptEnvelope(stranger, eventId, sms, targets);
    }
    case "bad-json":
      return encryptPlaintext(privateKey, eventId, Buffer.from("not json {", "utf8"), targets);
    case "unknown-kind":
      return encryptEnvelope(privateKey, eventId, { ...sms, kind: "bogus" }, targets);
    case "oversized":
      // Just over the limit once Base64-encoded, but under express's 96 KB body limit, so
      // the server answers with its own 413 invalid_envelope instead of a parser error.
      return encryptEnvelope(privateKey, eventId, { ...sms, body: "x".repeat(49_152) }, targets);
    default:
      throw new Error(`--kind must be one of: ${BROKEN_KINDS.join(", ")}`);
  }
}

export function decryptEnvelope(privateKey, eventId, deviceId, registeredSourceKey, envelope) {
  if (envelope.version !== 1 || envelope.algorithm !== ALGORITHM) {
    throw new Error("Unsupported envelope");
  }
  const sender = Buffer.from(envelope.senderPublicKey ?? "");
  const registered = Buffer.from(registeredSourceKey ?? "");
  if (sender.length !== registered.length || !timingSafeEqual(sender, registered)) {
    throw new Error("Sender public key does not match the server's record");
  }
  const wrapped = envelope.wrappedKeys?.[deviceId];
  if (!wrapped) throw new Error("Envelope has no key for this device");
  const contentKey = gcmDecrypt(
    wrappingKey(privateKey, envelope.senderPublicKey, eventId, deviceId),
    Buffer.from(wrapped.iv, "base64"), `${eventId}\n${deviceId}`,
    Buffer.from(wrapped.ciphertext, "base64"));
  if (contentKey.length !== 32) throw new Error("Invalid content key length");
  const plaintext = gcmDecrypt(contentKey, Buffer.from(envelope.payloadIv, "base64"), eventId,
    Buffer.from(envelope.ciphertext, "base64"));
  return JSON.parse(plaintext.toString("utf8"));
}

// ---------- state + HTTP ----------

if (!/^[a-z0-9_-]+$/i.test(opts.state) || opts.state.toLowerCase() === "credentials") {
  throw new Error("Invalid --state name");
}
const statePath = resolve(dataDir, `${opts.state}.json`);

function loadState() {
  if (!existsSync(statePath)) {
    throw new Error(`No mock device yet. Run: node tools/mock-device.mjs connect --state ${opts.state}`);
  }
  const state = JSON.parse(readFileSync(statePath, "utf8"));
  state.privateKey = createPrivateKey(state.privateKeyPem);
  return state;
}

function devCredentials() {
  const path = resolve(dataDir, "credentials.json");
  return existsSync(path) ? JSON.parse(readFileSync(path, "utf8")) : null;
}

async function api(server, token, method, path, body) {
  const response = await fetch(server + path, {
    method,
    redirect: "error",
    headers: {
      accept: "application/json",
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...(body ? { "content-type": "application/json" } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await response.text();
  const json = text ? JSON.parse(text) : {};
  if (!response.ok) {
    const error = new Error(`${method} ${path} → ${response.status}: ${json.message ?? text}`);
    error.status = response.status;
    throw error;
  }
  return json;
}

const call = (state, method, path, body) => api(state.server, state.token, method, path, body);

function describeDevices(info) {
  const lines = [`Account: ${info.account}`];
  for (const device of info.devices) {
    const tags = [];
    if (device.id === info.currentDeviceId) tags.push("this mock");
    if (info.pushTo.includes(device.id)) tags.push("we push to it");
    if (info.receiveFrom.includes(device.id)) tags.push("we receive from it");
    const seen = device.lastSeenAt ? new Date(device.lastSeenAt).toLocaleString() : "never";
    lines.push(`  ${device.id}  ${device.name} (${device.platform}), last seen ${seen}`
      + (tags.length ? `  [${tags.join(", ")}]` : ""));
  }
  return lines.join("\n");
}

function pickIds(spec, eligibleIds) {
  if (spec === undefined) return undefined;
  if (spec === "all") return eligibleIds;
  if (spec === "none" || spec === "") return [];
  const selected = spec.split(",").map((value) => value.trim()).filter(Boolean);
  if (selected.some((id) => !eligibleIds.includes(id))) throw new Error("Invalid device for this route");
  return selected;
}

// ---------- commands ----------

const commands = {
  async connect() {
    const creds = devCredentials();
    const server = (opts.server ?? creds?.server ?? "").replace(/\/+$/, "");
    if (!creds) throw new Error("Start tools/dev-server.mjs first to create test credentials");
    if (!server) throw new Error("Missing server URL");
    const { privateKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
    const response = await api(server, null, "POST", "/api/v1/connect", {
      username: creds.user.username,
      password: creds.user.password,
      deviceName: opts.name,
      platform: "mock-node",
      publicKey: publicKeyBase64(privateKey),
    });
    mkdirSync(dataDir, { recursive: true });
    writeFileSync(statePath, JSON.stringify({
      server,
      deviceId: response.deviceId,
      token: response.deviceToken,
      name: opts.name,
      privateKeyPem: privateKey.export({ type: "pkcs8", format: "pem" }),
    }, null, 2) + "\n", { mode: 0o600 });
    console.log(`Connected "${opts.name}" as ${response.deviceId} (account ${response.account})`);
  },

  async devices() {
    console.log(describeDevices(await call(loadState(), "GET", "/api/v1/devices")));
  },

  async route() {
    const state = loadState();
    const info = await call(state, "GET", "/api/v1/devices");
    const peers = info.devices.filter((d) => d.id !== info.currentDeviceId);
    const sourceIds = peers.filter((d) => d.platform !== FLUID_CAPSULE_PLATFORM).map((d) => d.id);
    const updated = await call(state, "PUT", "/api/v1/sync-routes", {
      pushTo: pickIds(opts.push, peers.map((d) => d.id)) ?? info.pushTo,
      receiveFrom: pickIds(opts.receive, sourceIds) ?? info.receiveFrom.filter((id) => sourceIds.includes(id)),
    });
    console.log(describeDevices(updated));
  },

  async sms() {
    if (!opts.from || opts.body === undefined) throw new Error("sms needs --from and --body");
    if (!Object.hasOwn(SMS_TYPE, opts.type)) throw new Error("--type must be inbox or sent");
    const type = SMS_TYPE[opts.type];
    const state = loadState();
    const info = await call(state, "GET", "/api/v1/devices");
    const targets = info.devices.filter((d) => info.pushTo.includes(d.id));
    if (!targets.length) {
      throw new Error("This mock pushes to no device. Run: route --push all");
    }
    const eventId = `${state.deviceId}:${randomUUID()}`;
    const payload = {
      kind: "sms", address: opts.from, body: opts.body, timestamp: Date.now(),
      subscriptionId: Number(opts.sub), type, lineNumber: "+61400000999", simSlot: 1,
    };
    const response = await call(state, "POST", "/api/v1/messages",
      { eventId, envelope: encryptEnvelope(state.privateKey, eventId, payload, targets) });
    const names = response.acceptedTargets
      .map((id) => info.devices.find((d) => d.id === id)?.name ?? id);
    console.log(`Queued for: ${names.join(", ") || "nobody"}`);
  },

  async "bad-sms"() {
    if (!BROKEN_KINDS.includes(opts.kind)) {
      throw new Error(`bad-sms needs --kind ${BROKEN_KINDS.join("|")}`);
    }
    const state = loadState();
    const info = await call(state, "GET", "/api/v1/devices");
    const targets = info.devices.filter((d) => info.pushTo.includes(d.id));
    if (!targets.length) throw new Error("This mock pushes to no device. Run: route --push all");
    const eventId = `${state.deviceId}:${randomUUID()}`;
    const envelope = brokenEnvelope(opts.kind, state.privateKey, eventId, targets);
    try {
      const response = await call(state, "POST", "/api/v1/messages", { eventId, envelope });
      const names = response.acceptedTargets
        .map((id) => info.devices.find((d) => d.id === id)?.name ?? id);
      console.log(`Queued broken "${opts.kind}" message ${eventId} for: ${names.join(", ") || "nobody"}`);
    } catch (error) {
      if (opts.kind !== "oversized" || error.status !== 413) throw error;
      console.log(`Server rejected the oversized envelope as expected (${error.message})`);
    }
  },

  async "remote-send"() {
    const subscriptionId = Number(opts.sub);
    if (!opts.to || !opts.address?.trim() || !opts.body?.trim()
        || !opts.sub.trim() || !Number.isInteger(subscriptionId) || subscriptionId < 0) {
      throw new Error("remote-send needs --to, --address, --body and an active --sub ID");
    }
    const state = loadState();
    const info = await call(state, "GET", "/api/v1/devices");
    const target = info.devices.find((d) => d.id === opts.to);
    if (!target || target.id === state.deviceId) throw new Error(`Invalid target ${opts.to}`);
    if (target.platform === FLUID_CAPSULE_PLATFORM) throw new Error("FluidCapsule cannot send SMS");
    const eventId = `${state.deviceId}:${randomUUID()}`;
    const payload = {
      kind: "send_sms", address: opts.address, body: opts.body,
      subscriptionId, requestedAt: Date.now(),
    };
    const response = await call(state, "POST", "/api/v1/messages", {
      eventId, targetDeviceId: target.id,
      envelope: encryptEnvelope(state.privateKey, eventId, payload, [target]),
    });
    console.log(response.acceptedTargets.length
      ? `Asked ${target.name} to send the SMS` : "Rejected: no sync route between the devices");
  },

  async listen() {
    const state = loadState();
    console.log(`Listening as ${state.name} (Ctrl+C to stop)…`);
    for (;;) {
      const info = await call(state, "GET", "/api/v1/devices");
      const { messages } = await call(state, "GET", "/api/v1/messages?after=0");
      for (const message of messages) {
        const source = info.devices.find((d) => d.id === message.sourceDeviceId);
        try {
          const payload = decryptEnvelope(state.privateKey, message.eventId, state.deviceId,
            source?.publicKey, message.envelope);
          console.log(`\n[${new Date().toLocaleTimeString()}] from ${message.sourceDeviceName}:`);
          console.log(JSON.stringify(payload, null, 2));
        } catch (error) {
          console.log(`\nCould not decrypt ${message.eventId}: ${error.message}`);
        }
        try {
          await call(state, "POST", `/api/v1/messages/${message.deliveryId}/ack`, {});
        } catch (error) {
          if (error.status !== 404) throw error;
        }
      }
      if (opts.once) return;
      await call(state, "GET", "/api/v1/messages/wait?timeout=25");
    }
  },
};

if (isMain) {
  const requested = positionals[0];
  if (!Object.hasOwn(commands, requested)) {
    console.log(readFileSync(fileURLToPath(import.meta.url), "utf8").split("\n")
      .slice(1, 16).map((line) => line.replace(/^\/\/ ?/, "")).join("\n"));
    process.exitCode = requested ? 1 : 0;
  } else {
    await commands[requested]().catch((error) => {
      console.error(error.message);
      process.exitCode = 1;
    });
  }
}
