import assert from "node:assert/strict";
import test from "node:test";
import { tailscaleEndpointsFromStatus } from "./EndpointDiscovery.js";

test("Tailscale discovery includes MagicDNS and tailnet IP candidates", () => {
  const endpoints = tailscaleEndpointsFromStatus({
    BackendState: "Running",
    Self: {
      DNSName: "vps.tailnet.ts.net.",
      Online: true,
      TailscaleIPs: ["100.88.12.34", "fd7a:115c:a1e0::1234"]
    }
  }, 8788);

  assert.deepEqual(endpoints.map((endpoint) => ({
    label: endpoint.label,
    host: endpoint.host,
    source: endpoint.source,
    url: endpoint.url
  })), [{
    label: "Tailscale MagicDNS",
    host: "vps.tailnet.ts.net",
    source: "MagicDNS",
    url: "ws://vps.tailnet.ts.net:8788/phone"
  }, {
    label: "Tailscale IP",
    host: "100.88.12.34",
    source: "Tailscale IPv4",
    url: "ws://100.88.12.34:8788/phone"
  }, {
    label: "Tailscale IP",
    host: "fd7a:115c:a1e0::1234",
    source: "Tailscale IPv6",
    url: "ws://[fd7a:115c:a1e0::1234]:8788/phone"
  }]);
});

test("Tailscale discovery falls back to tailnet IP when MagicDNS is absent", () => {
  const endpoints = tailscaleEndpointsFromStatus({
    BackendState: "Running",
    Self: {
      Online: true,
      TailscaleIPs: ["100.88.12.34"]
    }
  }, 8788);

  assert.equal(endpoints[0]?.url, "ws://100.88.12.34:8788/phone");
});
