import assert from "node:assert/strict";
import test from "node:test";

import { buildAndroidPairingDeepLink } from "./PairingPayload.js";

test("buildAndroidPairingDeepLink preserves ordered endpoint candidates", () => {
  const deepLink = buildAndroidPairingDeepLink({
    deviceId: "pixel",
    token: "secret-token",
    urls: [
      "ws://tailnet-host:8788/phone",
      "ws://192.168.1.20:8788/phone",
      "ws://192.168.1.20:8788/phone"
    ],
    expiresAt: 2_000_000_300,
    nonce: "abcdefghijklmnop"
  });
  const parsed = new URL(deepLink);
  assert.equal(parsed.protocol, "android-agent:");
  assert.equal(parsed.host, "pair");
  assert.equal(parsed.searchParams.get("url"), "ws://tailnet-host:8788/phone");
  assert.equal(parsed.searchParams.get("urls"), "ws://tailnet-host:8788/phone,ws://192.168.1.20:8788/phone");
  assert.equal(parsed.searchParams.get("deviceId"), "pixel");
  assert.equal(parsed.searchParams.get("token"), "secret-token");
  assert.equal(parsed.searchParams.get("expiresAt"), "2000000300");
  assert.equal(parsed.searchParams.get("nonce"), "abcdefghijklmnop");
});

test("buildAndroidPairingDeepLink requires complete freshness fields", () => {
  assert.throws(() => buildAndroidPairingDeepLink({
    deviceId: "pixel",
    token: "secret-token",
    urls: ["wss://bridge.example/phone"],
    expiresAt: 2_000_000_300
  }), /both expiresAt and nonce/);
});
