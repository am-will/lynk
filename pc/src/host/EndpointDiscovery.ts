import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { resolveExecutable } from "./CommandDiscovery.js";

const execFileAsync = promisify(execFile);

export type EndpointKind = "usb" | "tailscale" | "loopback" | "configured";

export interface EndpointCandidate {
  kind: EndpointKind;
  label: string;
  url: string;
  host: string;
  source: string;
}

export interface TailscaleStatus {
  BackendState?: string;
  Self?: {
    DNSName?: string;
    Online?: boolean;
    TailscaleIPs?: string[];
  };
}

export interface DiscoverySnapshot {
  endpoints: EndpointCandidate[];
  tailscale: {
    installed: boolean;
    running: boolean;
    online: boolean | null;
    source?: string;
    error?: string;
  };
}

export async function discoverEndpoints(
  options: { port: number; includeUsb?: boolean; includeLoopback?: boolean; allowInsecureTrustedOverlay?: boolean } = { port: 8788 }
): Promise<DiscoverySnapshot> {
  const endpoints: EndpointCandidate[] = [];

  const tailscale = await discoverTailscale(options.port, options.allowInsecureTrustedOverlay === true);
  endpoints.push(...tailscale.endpoints);

  if (options.includeUsb === true) {
    endpoints.push(endpoint("usb", "USB reverse", "127.0.0.1", options.port, "adb reverse"));
  }

  if (options.includeLoopback !== false) {
    endpoints.push(endpoint("loopback", "This computer", "127.0.0.1", options.port, "loopback"));
  }
  return {
    endpoints: dedupeEndpoints(endpoints),
    tailscale: tailscale.status
  };
}

export async function discoverTailscaleEndpoint(port: number): Promise<EndpointCandidate | undefined> {
  return (await discoverTailscaleEndpoints(port))[0];
}

export async function discoverTailscaleEndpoints(port: number): Promise<EndpointCandidate[]> {
  const snapshot = await discoverTailscale(port, process.env.PHONE_AGENT_PAIRING_ALLOW_INSECURE_TAILSCALE === "1");
  return snapshot.endpoints;
}

async function discoverTailscale(port: number, allowInsecureTrustedOverlay: boolean): Promise<{ endpoints: EndpointCandidate[]; status: DiscoverySnapshot["tailscale"] }> {
  const cli = process.env.TAILSCALE_CLI?.trim() || resolveExecutable("tailscale") || macTailscaleCli();
  if (!cli) {
    return { endpoints: [], status: { installed: false, running: false, online: null } };
  }

  try {
    const { stdout } = await execFileAsync(cli, ["status", "--json"], { timeout: 5_000, maxBuffer: 1024 * 1024 });
    const status = JSON.parse(stdout) as TailscaleStatus;
    const endpoints = tailscaleEndpointsFromStatus(status, port, { allowInsecureTrustedOverlay });
    const running = status.BackendState === undefined || status.BackendState === "Running";
    const online = status.Self?.Online ?? null;
    return {
      endpoints,
      status: {
        installed: true,
        running,
        online,
        source: tailscaleSource(endpoints),
        ...(running ? {} : { error: status.BackendState ?? "Tailscale is not running" })
      }
    };
  } catch (error) {
    try {
      const { stdout } = await execFileAsync(cli, ["ip", "-4"], { timeout: 5_000, maxBuffer: 1024 * 1024 });
      const host = stdout.split(/\s+/).find(Boolean);
      return {
        endpoints: host && allowInsecureTrustedOverlay
          ? [endpoint("tailscale", "Tailscale", host, port, "Tailscale IPv4")]
          : [],
        status: {
          installed: true,
          running: Boolean(host),
          online: null,
          source: host ? "Tailscale IPv4" : undefined,
          ...(host ? {} : { error: "Tailscale did not report an IPv4 address." })
        }
      };
    } catch (fallbackError) {
      return {
        endpoints: [],
        status: {
          installed: true,
          running: false,
          online: null,
          error: fallbackError instanceof Error ? fallbackError.message : String(fallbackError)
        }
      };
    }
  }
}

export function tailscaleEndpointsFromStatus(
  status: TailscaleStatus,
  port: number,
  options: { allowInsecureTrustedOverlay?: boolean } = {}
): EndpointCandidate[] {
  if (options.allowInsecureTrustedOverlay !== true) {
    return [];
  }
  const endpoints: EndpointCandidate[] = [];
  const dnsName = normalizeDnsName(status.Self?.DNSName);
  if (dnsName) {
    endpoints.push(endpoint("tailscale", "Tailscale MagicDNS", dnsName, port, "MagicDNS"));
  }
  for (const ip of tailnetIps(status)) {
    endpoints.push(endpoint("tailscale", "Tailscale IP", ip, port, ip.includes(":") ? "Tailscale IPv6" : "Tailscale IPv4"));
  }
  return dedupeEndpoints(endpoints);
}

function endpoint(kind: EndpointKind, label: string, host: string, port: number, source: string): EndpointCandidate {
  return {
    kind,
    label,
    host,
    source,
    url: `ws://${formatUrlHost(host)}:${port}/phone`
  };
}

function dedupeEndpoints(endpoints: EndpointCandidate[]): EndpointCandidate[] {
  const seen = new Set<string>();
  const result: EndpointCandidate[] = [];
  for (const endpointCandidate of endpoints) {
    if (seen.has(endpointCandidate.url)) {
      continue;
    }
    seen.add(endpointCandidate.url);
    result.push(endpointCandidate);
  }
  return result;
}

function tailnetIps(status: TailscaleStatus): string[] {
  const ips = status.Self?.TailscaleIPs ?? [];
  const ipv4 = ips.filter((ip) => /^\d{1,3}(?:\.\d{1,3}){3}$/.test(ip));
  const other = ips.filter((ip) => !ipv4.includes(ip));
  return [...ipv4, ...other];
}

function tailscaleSource(endpoints: EndpointCandidate[]): string | undefined {
  const sources = [...new Set(endpoints.map((endpoint) => endpoint.source))];
  if (sources.length === 0) {
    return undefined;
  }
  return sources.join(" + ");
}

function normalizeDnsName(value: string | undefined): string | undefined {
  return value?.trim().replace(/\.$/, "") || undefined;
}

function formatUrlHost(host: string): string {
  return host.includes(":") && !host.startsWith("[") ? `[${host}]` : host;
}

function macTailscaleCli(): string | undefined {
  const candidate = "/Applications/Tailscale.app/Contents/MacOS/Tailscale";
  return process.platform === "darwin" && resolveExecutable(candidate) ? candidate : undefined;
}
