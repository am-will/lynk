export type BridgeEndpointSecurity = "secure" | "local-development" | "trusted-overlay-development";

export interface NormalizedBridgeEndpoint {
  url: string;
  security: BridgeEndpointSecurity;
}

export function normalizeBridgeEndpoint(
  raw: string,
  options: { allowInsecureTrustedOverlay?: boolean } = {}
): NormalizedBridgeEndpoint {
  let parsed: URL;
  try {
    parsed = new URL(raw.trim());
  } catch {
    throw new Error("Bridge endpoint must be a valid WebSocket URL.");
  }
  if (!new Set(["ws:", "wss:"]).has(parsed.protocol)
    || !parsed.hostname
    || parsed.username
    || parsed.password
    || parsed.search
    || parsed.hash
    || (parsed.pathname !== "/" && parsed.pathname !== "/phone")) {
    throw new Error("Bridge endpoint must be a credential-free ws or wss URL ending in /phone.");
  }
  parsed.pathname = "/phone";
  if (parsed.protocol === "wss:") {
    return { url: parsed.toString(), security: "secure" };
  }
  if (isLoopbackHost(parsed.hostname)) {
    return { url: parsed.toString(), security: "local-development" };
  }
  if (options.allowInsecureTrustedOverlay === true && isTrustedOverlayHost(parsed.hostname)) {
    return { url: parsed.toString(), security: "trusted-overlay-development" };
  }
  throw new Error("Network bridge endpoints require wss; ws is limited to loopback/ADB or an explicitly trusted Tailscale overlay.");
}

export function isLoopbackHost(host: string): boolean {
  const normalized = stripIpv6Brackets(host).toLowerCase();
  return normalized === "localhost" || normalized === "::1" || /^127(?:\.\d{1,3}){3}$/.test(normalized);
}

export function isTrustedOverlayHost(host: string): boolean {
  const normalized = stripIpv6Brackets(host).toLowerCase().replace(/\.$/, "");
  if (normalized.endsWith(".ts.net") || normalized.startsWith("fd7a:115c:a1e0:")) {
    return true;
  }
  const octets = normalized.split(".").map(Number);
  return octets.length === 4
    && octets.every((value) => Number.isInteger(value) && value >= 0 && value <= 255)
    && octets[0] === 100
    && octets[1]! >= 64
    && octets[1]! <= 127;
}

function stripIpv6Brackets(host: string): string {
  return host.startsWith("[") && host.endsWith("]") ? host.slice(1, -1) : host;
}
