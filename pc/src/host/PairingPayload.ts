import type { BridgeConfig } from "../bridge/config.js";
import { discoverEndpoints, type EndpointCandidate } from "./EndpointDiscovery.js";
import { randomBytes } from "node:crypto";
import { normalizeBridgeEndpoint } from "./BridgeEndpointPolicy.js";

export interface HostPairingPayload {
  version: 1;
  product: "android-agent-bridge";
  deviceId: string;
  token: string;
  endpoints: EndpointCandidate[];
  deepLink: string;
}

export async function createHostPairingPayload(config: Pick<BridgeConfig, "defaultDeviceId" | "port" | "token">): Promise<HostPairingPayload> {
  const allowInsecureTrustedOverlay = process.env.PHONE_AGENT_PAIRING_ALLOW_INSECURE_TAILSCALE === "1";
  const discovery = await discoverEndpoints({
    port: config.port,
    includeUsb: process.env.PHONE_AGENT_PAIRING_INCLUDE_USB === "1",
    includeLoopback: process.env.PHONE_AGENT_PAIRING_INCLUDE_LOOPBACK === "1",
    allowInsecureTrustedOverlay
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
      urls: endpoints.map((endpoint) => endpoint.url),
      allowInsecureTrustedOverlay,
      expiresAt: Math.floor(Date.now() / 1_000) + 5 * 60,
      nonce: randomBytes(18).toString("base64url")
    })
  };
}

export function buildAndroidPairingDeepLink(options: {
  deviceId: string;
  token: string;
  urls: string[];
  expiresAt?: number;
  nonce?: string;
  allowInsecureTrustedOverlay?: boolean;
}): string {
  const params = new URLSearchParams();
  const urls = uniqueStrings(options.urls).map((url) => normalizeBridgeEndpoint(url, {
    allowInsecureTrustedOverlay: options.allowInsecureTrustedOverlay
  }).url);
  if (urls[0]) {
    params.set("url", urls[0]);
  }
  if (urls.length > 1) {
    params.set("urls", urls.join(","));
  }
  params.set("deviceId", options.deviceId);
  params.set("token", options.token);
  if (options.allowInsecureTrustedOverlay === true) {
    params.set("allowInsecureTrustedOverlay", "1");
  }
  if ((options.expiresAt === undefined) !== (options.nonce === undefined)) {
    throw new Error("Pairing links must provide both expiresAt and nonce");
  }
  if (options.expiresAt !== undefined && options.nonce !== undefined) {
    params.set("expiresAt", String(options.expiresAt));
    params.set("nonce", options.nonce);
  }
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
