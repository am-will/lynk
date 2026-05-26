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
    ]
  });
  const parsed = new URL(deepLink);
  assert.equal(parsed.protocol, "android-agent:");
  assert.equal(parsed.host, "pair");
  assert.equal(parsed.searchParams.get("url"), "ws://tailnet-host:8788/phone");
  assert.equal(parsed.searchParams.get("urls"), "ws://tailnet-host:8788/phone,ws://192.168.1.20:8788/phone");
  assert.equal(parsed.searchParams.get("deviceId"), "pixel");
  assert.equal(parsed.searchParams.get("token"), "secret-token");
});
