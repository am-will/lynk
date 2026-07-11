import assert from "node:assert/strict";
import test from "node:test";

import { buildAndroidPairingDeepLink } from "./PairingPayload.js";

test("buildAndroidPairingDeepLink preserves ordered endpoint candidates", () => {
  const deepLink = buildAndroidPairingDeepLink({
    deviceId: "pixel",
    token: "secret-token",
    urls: [
      "wss://tailnet-host:8788/phone",
      "wss://192.168.1.20:8788/phone",
      "wss://192.168.1.20:8788/phone"
    ],
    expiresAt: 2_000_000_300,
    nonce: "abcdefghijklmnop"
  });
  const parsed = new URL(deepLink);
  assert.equal(parsed.protocol, "android-agent:");
  assert.equal(parsed.host, "pair");
  assert.equal(parsed.searchParams.get("url"), "wss://tailnet-host:8788/phone");
  assert.equal(parsed.searchParams.get("urls"), "wss://tailnet-host:8788/phone,wss://192.168.1.20:8788/phone");
  assert.equal(parsed.searchParams.get("deviceId"), "pixel");
  assert.equal(parsed.searchParams.get("token"), "secret-token");
  assert.equal(parsed.searchParams.get("expiresAt"), "2000000300");
  assert.equal(parsed.searchParams.get("nonce"), "abcdefghijklmnop");
});

test("buildAndroidPairingDeepLink marks explicit insecure Tailscale development endpoints", () => {
  const parsed = new URL(buildAndroidPairingDeepLink({
    deviceId: "pixel",
    token: "secret-token",
    urls: ["ws://100.88.12.34:8788/phone"],
    allowInsecureTrustedOverlay: true
  }));

  assert.equal(parsed.searchParams.get("allowInsecureTrustedOverlay"), "1");
});

test("buildAndroidPairingDeepLink requires complete freshness fields", () => {
  assert.throws(() => buildAndroidPairingDeepLink({
    deviceId: "pixel",
    token: "secret-token",
    urls: ["wss://bridge.example/phone"],
    expiresAt: 2_000_000_300
  }), /both expiresAt and nonce/);
});
