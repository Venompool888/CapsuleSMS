import assert from "node:assert/strict";
import { test } from "node:test";
import { platformLabel, portalPage } from "../src/views.js";

const user = { username: "sync-user", role: "user", csrf: "csrf-token" };
const phone = { id: "phone", name: "Phone", platform: "android-17", enabled: 1, last_seen_at: null };
const capsule = { id: "capsule", name: "Capsule", platform: "fluidcapsule-android", enabled: 1, last_seen_at: null };
const old = { id: "old", name: "Old tablet", platform: "android-14", enabled: 0, last_seen_at: null };

function card(html, name) {
  const start = html.indexOf(`<h2>${name}</h2>`);
  assert.notEqual(start, -1, `card for ${name}`);
  return html.slice(start, html.indexOf("</section>", start));
}

test("platform names are shown in plain words", () => {
  assert.equal(platformLabel("android-17"), "Android 17");
  assert.equal(platformLabel("fluidcapsule-android"), "FluidCapsule · 仅接收");
  assert.equal(platformLabel("coloros"), "coloros");
});

test("a receive-only device gets no push choices and is not offered as a sender", () => {
  const html = portalPage(user, [phone, capsule], []);
  const capsuleCard = card(html, "Capsule");
  assert.match(capsuleCard, /只接收短信/);
  assert.doesNotMatch(capsuleCard, /name="pushTo"/);
  assert.match(capsuleCard, /name="receiveFrom" value="phone"/);
  const phoneCard = card(html, "Phone");
  assert.match(phoneCard, /name="pushTo" value="capsule"/);
  assert.doesNotMatch(phoneCard, /name="receiveFrom" value="capsule"/);
});

test("revoked devices are grouped at the end and get no route form", () => {
  const html = portalPage(user, [old, phone], []);
  assert.doesNotMatch(html, /<h2>Old tablet<\/h2>/);
  assert.match(html, /已撤销的设备（1）[\s\S]*Old tablet/);
  assert.ok(html.indexOf("已撤销的设备") > html.indexOf("<h2>Phone</h2>"));
});

test("revoking asks for a second press before the form is shown", () => {
  const phoneCard = card(portalPage(user, [phone], []), "Phone");
  assert.match(phoneCard, /<details class="confirm"><summary[^>]*>撤销<\/summary><form method="post" action="\/portal\/devices\/phone\/revoke">/);
  assert.match(phoneCard, /确认撤销/);
});

test("only revoked devices still shows the empty state", () => {
  const html = portalPage(user, [old], []);
  assert.match(html, /还没有设备/);
});
