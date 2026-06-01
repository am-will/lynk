import type { BridgeConfig } from "../bridge/config.js";
import { discoverEndpoints, type EndpointCandidate } from "./EndpointDiscovery.js";

export interface HostPairingPayload {
  version: 1;
  product: "android-agent-bridge";
  deviceId: string;
  token: string;
  endpoints: EndpointCandidate[];
  deepLink: string;
}

export async function createHostPairingPayload(config: Pick<BridgeConfig, "defaultDeviceId" | "port" | "token">): Promise<HostPairingPayload> {
  const discovery = await discoverEndpoints({
    port: config.port,
    includeUsb: process.env.PHONE_AGENT_PAIRING_INCLUDE_USB === "1",
    includeLoopback: process.env.PHONE_AGENT_PAIRING_INCLUDE_LOOPBACK === "1"
  });
  const endpoints = discovery.endpoints;
  return {
    version: 1,
    product: "android-agent-bridge",
    deviceId: config.defaultDeviceId,
    token: config.token,
    endpoints,
    deepLink: buildAndroidPairingDeepLink({
      deviceId: config.defaultDeviceId,
      token: config.token,
      urls: endpoints.map((endpoint) => endpoint.url)
    })
  };
}

export function buildAndroidPairingDeepLink(options: { deviceId: string; token: string; urls: string[] }): string {
  const params = new URLSearchParams();
  const urls = uniqueStrings(options.urls);
  if (urls[0]) {
    params.set("url", urls[0]);
  }
  if (urls.length > 1) {
    params.set("urls", urls.join(","));
  }
  params.set("deviceId", options.deviceId);
  params.set("token", options.token);
  return `android-agent://pair?${params.toString()}`;
}

function uniqueStrings(values: string[]): string[] {
  const seen = new Set<string>();
  const result: string[] = [];
  for (const value of values) {
    const trimmed = value.trim();
    if (!trimmed || seen.has(trimmed)) {
      continue;
    }
    seen.add(trimmed);
    result.push(trimmed);
  }
  return result;
}
