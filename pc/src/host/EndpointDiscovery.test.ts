import assert from "node:assert/strict";
import test from "node:test";
import { discoverEndpoints, tailscaleEndpointsFromStatus } from "./EndpointDiscovery.js";

test("Tailscale discovery does not synthesize TLS endpoints", () => {
  const endpoints = tailscaleEndpointsFromStatus({
    BackendState: "Running",
    Self: {
      DNSName: "vps.tailnet.ts.net.",
      Online: true,
      TailscaleIPs: ["100.88.12.34", "fd7a:115c:a1e0::1234"]
    }
  }, 8788);

  assert.deepEqual(endpoints, []);
});

test("Tailscale discovery falls back to tailnet IP when MagicDNS is absent", () => {
  const endpoints = tailscaleEndpointsFromStatus({
    BackendState: "Running",
    Self: {
      Online: true,
      TailscaleIPs: ["100.88.12.34"]
    }
  }, 8788);

  assert.equal(endpoints.length, 0);
});

test("Tailscale cleartext endpoints require an explicit trusted-overlay opt-in", () => {
  const endpoints = tailscaleEndpointsFromStatus({
    Self: { TailscaleIPs: ["100.88.12.34"] }
  }, 8788, { allowInsecureTrustedOverlay: true });

  assert.equal(endpoints[0]?.url, "ws://100.88.12.34:8788/phone");
});

test("endpoint discovery does not include USB reverse unless explicitly requested", async () => {
  const withoutUsb = await discoverEndpoints({ port: 8788, includeUsb: false });
  assert.equal(withoutUsb.endpoints.some((endpoint) => endpoint.kind === "usb"), false);

  const withUsb = await discoverEndpoints({ port: 8788, includeUsb: true });
  assert.equal(withUsb.endpoints.some((endpoint) => endpoint.kind === "usb"), true);
});

test("endpoint discovery can omit loopback from phone pairing candidates", async () => {
  const endpoints = await discoverEndpoints({ port: 8788, includeLoopback: false });
  assert.equal(endpoints.endpoints.some((endpoint) => endpoint.kind === "loopback"), false);
});
