import assert from "node:assert/strict";
import test from "node:test";

import { buildAndroidPairingDeepLink, configuredSecureEndpoints, createHostPairingPayload } from "./PairingPayload.js";

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

test("configured pairing endpoints are explicit secure URLs", () => {
  assert.deepEqual(configuredSecureEndpoints("wss://bridge.example/phone, wss://backup.example:9443"), [{
    kind: "configured",
    label: "Configured secure endpoint",
    url: "wss://bridge.example/phone",
    host: "bridge.example",
    source: "PHONE_AGENT_PAIRING_WSS_URLS"
  }, {
    kind: "configured",
    label: "Configured secure endpoint 2",
    url: "wss://backup.example:9443/phone",
    host: "backup.example",
    source: "PHONE_AGENT_PAIRING_WSS_URLS"
  }]);
  assert.throws(() => configuredSecureEndpoints("ws://192.168.1.20:8788/phone"), /require wss/);
});

test("pairing readiness never emits a dead synthetic network URL", async (t) => {
  t.after(() => {
    delete process.env.PHONE_AGENT_PAIRING_WSS_URLS;
    delete process.env.PHONE_AGENT_PAIRING_ALLOW_INSECURE_TAILSCALE;
    delete process.env.PHONE_AGENT_PAIRING_INCLUDE_USB;
    delete process.env.PHONE_AGENT_PAIRING_INCLUDE_LOOPBACK;
    delete process.env.TAILSCALE_CLI;
  });
  process.env.TAILSCALE_CLI = "/definitely/missing/tailscale";
  const config = { defaultDeviceId: "pixel", port: 8788, token: "secret-token" };

  const unavailable = await createHostPairingPayload(config);
  assert.deepEqual(unavailable.endpoints, []);
  assert.equal(unavailable.deepLink, null);
  assert.match(unavailable.warnings[0]!, /No usable phone endpoint/);

  process.env.PHONE_AGENT_PAIRING_WSS_URLS = "wss://bridge.example/phone";
  const configured = await createHostPairingPayload(config);
  assert.equal(configured.endpoints[0]?.url, "wss://bridge.example/phone");
  assert.match(configured.deepLink ?? "", /^android-agent:\/\/pair\?/);
});
