import assert from "node:assert/strict";
import test from "node:test";
import { normalizeBridgeEndpoint } from "./BridgeEndpointPolicy.js";

test("bridge endpoint policy requires TLS for ordinary network hosts", () => {
  assert.equal(normalizeBridgeEndpoint("WSS://Bridge.Example:8788").url, "wss://bridge.example:8788/phone");
  assert.throws(() => normalizeBridgeEndpoint("ws://192.168.1.20:8788/phone"), /require wss/);
  assert.throws(() => normalizeBridgeEndpoint("ws://bridge.example:8788/phone"), /require wss/);
});

test("bridge endpoint policy keeps bounded development exceptions", () => {
  assert.equal(normalizeBridgeEndpoint("ws://127.0.0.1:8788").security, "local-development");
  assert.equal(normalizeBridgeEndpoint("ws://[::1]:8788/phone").security, "local-development");
  assert.throws(() => normalizeBridgeEndpoint("ws://100.88.12.34:8788/phone"), /explicitly trusted/);
  assert.equal(normalizeBridgeEndpoint("ws://100.88.12.34:8788/phone", {
    allowInsecureTrustedOverlay: true
  }).security, "trusted-overlay-development");
  assert.equal(normalizeBridgeEndpoint("ws://host.tailnet.ts.net:8788/phone", {
    allowInsecureTrustedOverlay: true
  }).security, "trusted-overlay-development");
  assert.throws(() => normalizeBridgeEndpoint("ws://192.168.1.20:8788/phone", {
    allowInsecureTrustedOverlay: true
  }), /require wss/);
});
