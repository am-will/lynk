import { execFile } from "node:child_process";
import { networkInterfaces } from "node:os";
import { promisify } from "node:util";
import { resolveExecutable } from "./CommandDiscovery.js";

const execFileAsync = promisify(execFile);

export type EndpointKind = "usb" | "tailscale" | "lan" | "loopback";

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

export async function discoverEndpoints(options: { port: number; includeUsb?: boolean } = { port: 8788 }): Promise<DiscoverySnapshot> {
  const endpoints: EndpointCandidate[] = [];
  if (options.includeUsb !== false) {
    endpoints.push(endpoint("usb", "USB reverse", "127.0.0.1", options.port, "adb reverse"));
  }

  const tailscale = await discoverTailscale(options.port);
  endpoints.push(...tailscale.endpoints);

  for (const address of lanAddresses()) {
    endpoints.push(endpoint("lan", `Local network (${address.interfaceName})`, address.address, options.port, address.family));
  }

  endpoints.push(endpoint("loopback", "This computer", "127.0.0.1", options.port, "loopback"));
  return {
    endpoints: dedupeEndpoints(endpoints),
    tailscale: tailscale.status
  };
}

export async function discoverTailscaleEndpoint(port: number): Promise<EndpointCandidate | undefined> {
  return (await discoverTailscaleEndpoints(port))[0];
}

export async function discoverTailscaleEndpoints(port: number): Promise<EndpointCandidate[]> {
  const snapshot = await discoverTailscale(port);
  return snapshot.endpoints;
}

async function discoverTailscale(port: number): Promise<{ endpoints: EndpointCandidate[]; status: DiscoverySnapshot["tailscale"] }> {
  const cli = process.env.TAILSCALE_CLI?.trim() || resolveExecutable("tailscale") || macTailscaleCli();
  if (!cli) {
    return { endpoints: [], status: { installed: false, running: false, online: null } };
  }

  try {
    const { stdout } = await execFileAsync(cli, ["status", "--json"], { timeout: 5_000, maxBuffer: 1024 * 1024 });
    const status = JSON.parse(stdout) as TailscaleStatus;
    const endpoints = tailscaleEndpointsFromStatus(status, port);
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
        endpoints: host ? [endpoint("tailscale", "Tailscale", host, port, "Tailscale IPv4")] : [],
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

export function tailscaleEndpointsFromStatus(status: TailscaleStatus, port: number): EndpointCandidate[] {
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

function lanAddresses(): Array<{ interfaceName: string; address: string; family: string }> {
  const result: Array<{ interfaceName: string; address: string; family: string }> = [];
  for (const [interfaceName, addresses] of Object.entries(networkInterfaces())) {
    for (const address of addresses ?? []) {
      if (address.internal || address.family !== "IPv4") {
        continue;
      }
      if (isIgnoredLanAddress(address.address, interfaceName)) {
        continue;
      }
      result.push({ interfaceName, address: address.address, family: address.family });
    }
  }
  return result;
}

function isIgnoredLanAddress(address: string, interfaceName: string): boolean {
  const lowerName = interfaceName.toLowerCase();
  return address.startsWith("169.254.")
    || address.startsWith("172.17.")
    || lowerName.includes("docker")
    || lowerName.includes("bridge")
    || lowerName.includes("vmnet")
    || lowerName.includes("vbox")
    || lowerName.includes("utun");
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
