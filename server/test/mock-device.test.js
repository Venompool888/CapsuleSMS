import assert from "node:assert/strict";
import { generateKeyPairSync } from "node:crypto";
import { test } from "node:test";
import {
  BROKEN_KINDS, MAX_ENVELOPE_BYTES, brokenEnvelope, decryptEnvelope, encryptEnvelope,
  publicKeyBase64,
} from "../tools/mock-device.mjs";

const newKey = () => generateKeyPairSync("ec", { namedCurve: "prime256v1" }).privateKey;
const sender = newKey();
const receiver = newKey();
const target = { id: "receiver-1", name: "Receiver", publicKey: publicKeyBase64(receiver) };
const eventId = "sender-1:test-event";
const open = (envelope) =>
  decryptEnvelope(receiver, eventId, target.id, publicKeyBase64(sender), envelope);

test("a normal envelope round-trips", () => {
  const payload = { kind: "sms", address: "CloudSyncOk", body: "ok", type: 1 };
  assert.deepEqual(open(encryptEnvelope(sender, eventId, payload, [target])), payload);
});

test("tampered, wrong-sender and bad-json envelopes cannot be opened", () => {
  for (const kind of ["tamper", "wrong-sender", "bad-json"]) {
    assert.throws(() => open(brokenEnvelope(kind, sender, eventId, [target])), kind);
  }
});

test("unknown-kind decrypts but is not an SMS", () => {
  assert.equal(open(brokenEnvelope("unknown-kind", sender, eventId, [target])).kind, "bogus");
});

test("oversized is over the envelope limit but under the request body limit", () => {
  const bytes = Buffer.byteLength(
    JSON.stringify(brokenEnvelope("oversized", sender, eventId, [target])));
  assert.ok(bytes > MAX_ENVELOPE_BYTES, `${bytes} bytes`);
  assert.ok(bytes < 96 * 1024, `${bytes} bytes`);
});

test("unknown broken kinds are refused", () => {
  assert.ok(BROKEN_KINDS.length === 5);
  assert.throws(() => brokenEnvelope("nope", sender, eventId, [target]), /--kind/);
});
