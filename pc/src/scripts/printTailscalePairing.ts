import { execFile } from "node:child_process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);
const DEFAULT_PORT = 8788;

interface TailscaleStatus {
  BackendState?: string;
  Self?: {
    DNSName?: string;
    Online?: boolean;
    TailscaleIPs?: string[];
  };
}

async function runTailscale(args: string[]): Promise<string> {
  const candidates = [
    process.env.TAILSCALE_CLI,
    "tailscale",
    "/Applications/Tailscale.app/Contents/MacOS/Tailscale"
  ].filter((candidate): candidate is string => Boolean(candidate?.trim()));

  const errors: string[] = [];
  for (const candidate of candidates) {
    try {
      const { stdout } = await execFileAsync(candidate, args, { maxBuffer: 1024 * 1024 });
      return stdout;
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      errors.push(`${candidate}: ${message}`);
    }
  }

  throw new Error([
    "Unable to run the Tailscale CLI.",
    "Install Tailscale, add the CLI to PATH, or set TAILSCALE_CLI to the full executable path.",
    ...errors.map((error) => `- ${error}`)
  ].join("\n"));
}

function normalizeDnsName(value: string | undefined): string | undefined {
  const trimmed = value?.trim().replace(/\.$/, "");
  return trimmed || undefined;
}

function isIpv4(value: string): boolean {
  return /^\d{1,3}(?:\.\d{1,3}){3}$/.test(value);
}

function formatUrlHost(host: string): string {
  return host.includes(":") && !host.startsWith("[") ? `[${host}]` : host;
}

function preferredTailnetHost(status: TailscaleStatus): string | undefined {
  const dnsName = normalizeDnsName(status.Self?.DNSName);
  if (dnsName) {
    return dnsName;
  }

  const ips = status.Self?.TailscaleIPs ?? [];
  return ips.find(isIpv4) ?? ips[0];
}

async function readTailnetHost(): Promise<{ host: string; source: string; status?: TailscaleStatus }> {
  try {
    const status = JSON.parse(await runTailscale(["status", "--json"])) as TailscaleStatus;
    const host = preferredTailnetHost(status);
    if (host) {
      return { host, source: normalizeDnsName(status.Self?.DNSName) ? "MagicDNS" : "Tailscale IP", status };
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.warn(`Could not read 'tailscale status --json': ${message}`);
  }

  const ip = (await runTailscale(["ip", "-4"])).split(/\s+/).find(Boolean);
  if (!ip) {
    throw new Error("Tailscale did not report an IPv4 address for this device.");
  }
  return { host: ip, source: "Tailscale IPv4" };
}

const port = Number.parseInt(process.env.PHONE_AGENT_PORT ?? String(DEFAULT_PORT), 10);
if (!Number.isFinite(port) || port <= 0) {
  throw new Error(`Invalid PHONE_AGENT_PORT: ${process.env.PHONE_AGENT_PORT}`);
}

const { host, source, status } = await readTailnetHost();
const wsUrl = `ws://${formatUrlHost(host)}:${port}/phone`;
const healthUrl = `http://${formatUrlHost(host)}:${port}/health`;
const token = process.env.PHONE_AGENT_TOKEN;

console.log("Tailscale Android pairing");
console.log("");
console.log(`WebSocket URL: ${wsUrl}`);
console.log(`Health URL:    ${healthUrl}`);
console.log(`Host source:   ${source}`);
if (status?.BackendState && status.BackendState !== "Running") {
  console.log(`Tailscale:     ${status.BackendState}`);
}
if (status?.Self?.Online === false) {
  console.log("Warning:       Tailscale reports this device is offline.");
}
console.log("");
console.log("Android app path:");
console.log("  OpenAgent -> Open Connection & Config -> Bridge");
console.log("");
console.log("Bridge fields:");
console.log(`- WebSocket URL: ${wsUrl}`);
console.log(`- Device ID: ${process.env.PHONE_AGENT_DEFAULT_DEVICE ?? "openclaw-agent"}`);
console.log(`- Auth token: ${token ? "<use the PHONE_AGENT_TOKEN value from this shell>" : "<generate one with: export PHONE_AGENT_TOKEN=\"$(openssl rand -hex 32)\">"}`);
console.log("");
console.log("Start the PC side with:");
console.log("  openclaw gateway start");
if (token) {
  console.log(`  PHONE_AGENT_TOKEN=<same-token-you-pasted-into-android> PHONE_AGENT_PORT=${port} npm run bridge`);
} else {
  console.log("  export PHONE_AGENT_TOKEN=\"$(openssl rand -hex 32)\"");
  console.log("  echo \"Android token: $PHONE_AGENT_TOKEN\"");
  console.log(`  PHONE_AGENT_PORT=${port} npm run bridge`);
}
