import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { resolveCommand, resolveExecutable } from "./CommandDiscovery.js";
import { discoverEndpoints } from "./EndpointDiscovery.js";
import { resolveHermesConfigPath } from "./HermesConfigPath.js";
import { loadOrCreateHostBridgeConfig, writeHostBridgeConfig } from "./HostConfigStore.js";

export interface IntegrationStatus {
  id: "openclaw" | "hermes" | "codex" | "tailscale" | "adb";
  label: string;
  installed: boolean;
  configured: boolean;
  ready: boolean;
  path?: string;
  message: string;
}

export interface RefreshResult {
  configPath: string;
  changed: boolean;
  restartRecommended: boolean;
  integrations: IntegrationStatus[];
  mcp: Array<{ integration: string; ok: boolean; message: string }>;
}

const scriptDir = dirname(fileURLToPath(import.meta.url));
const pcRoot = resolve(scriptDir, "../..");

export async function refreshHostIntegrations(options: { configureMcp?: boolean } = {}): Promise<RefreshResult> {
  const loaded = loadOrCreateHostBridgeConfig();
  const config = { ...loaded.config };
  const before = JSON.stringify(config);
  const integrations = await detectIntegrations();
  config.discoveredPaths = {
    ...config.discoveredPaths,
    ...Object.fromEntries(integrations.flatMap((integration) => integration.path ? [[integration.id, integration.path]] : []))
  };
  writeHostBridgeConfig(loaded.path, config);
  const changed = before !== JSON.stringify(config);
  const mcp = options.configureMcp === false
    ? []
    : await configureAvailableMcp(integrations);

  return {
    configPath: loaded.path,
    changed,
    restartRecommended: changed,
    integrations,
    mcp
  };
}

export async function detectIntegrations(): Promise<IntegrationStatus[]> {
  const hostConfig = loadOrCreateHostBridgeConfig().config;
  const openclaw = resolveExecutable(process.env.OPENCLAW_AGENT_COMMAND?.trim() || "openclaw");
  const codex = resolveCommand(process.env.CODEX_APP_SERVER_COMMAND ?? hostConfig.codexAppServerCommand ?? "codex app-server --listen stdio://");
  const tailscale = await discoverEndpoints({ port: hostConfig.phoneAgentPort ?? 8788, includeUsb: false });
  const adb = resolveExecutable(process.env.ADB?.trim() || "adb");
  const hermesApiKey = process.env.HERMES_API_KEY?.trim() || hostConfig.hermesApiKey?.trim();
  const hermesCli = resolveCommand(process.env.HERMES_COMMAND?.trim() || "hermes");
  const hermesConfigPath = resolveHermesConfigPath();
  const hermesConfigExists = existsSync(hermesConfigPath);

  return [
    {
      id: "openclaw",
      label: "OpenClaw",
      installed: Boolean(openclaw),
      configured: Boolean(openclaw),
      ready: Boolean(openclaw),
      path: openclaw,
      message: openclaw ? "OpenClaw CLI was found." : "OpenClaw CLI was not found on PATH."
    },
    {
      id: "hermes",
      label: "Hermes",
      installed: hermesConfigExists || Boolean(hermesApiKey) || hermesCli.available,
      configured: Boolean(hermesApiKey) || hermesCli.available,
      ready: Boolean(hermesApiKey) || hermesCli.available,
      path: hermesConfigExists ? hermesConfigPath : hermesCli.resolvedPath,
      message: hermesApiKey
        ? "Hermes API key is configured."
        : hermesCli.available
          ? "Hermes CLI was found; Lynk can use CLI fallback if no runs API is running."
          : "Hermes API key is missing and Hermes CLI was not found."
    },
    {
      id: "codex",
      label: "Codex",
      installed: codex.available,
      configured: codex.available,
      ready: codex.available,
      path: codex.resolvedPath,
      message: codex.available ? "Codex app-server command is available." : `Codex command '${codex.executable || "codex"}' was not found.`
    },
    {
      id: "tailscale",
      label: "Tailscale",
      installed: tailscale.tailscale.installed,
      configured: tailscale.tailscale.running,
      ready: tailscale.tailscale.running && tailscale.endpoints.some((endpoint) => endpoint.kind === "tailscale"),
      path: resolveExecutable(process.env.TAILSCALE_CLI?.trim() || "tailscale"),
      message: tailscale.tailscale.error ?? (tailscale.tailscale.running ? "Tailscale endpoint is available." : "Tailscale is not running.")
    },
    {
      id: "adb",
      label: "ADB",
      installed: Boolean(adb),
      configured: Boolean(adb),
      ready: Boolean(adb),
      path: adb,
      message: adb ? "ADB was found for USB reverse pairing." : "ADB was not found on PATH."
    }
  ];
}

async function configureAvailableMcp(integrations: IntegrationStatus[]): Promise<Array<{ integration: string; ok: boolean; message: string }>> {
  const results = [];
  if (integrations.find((integration) => integration.id === "openclaw")?.ready) {
    results.push(await runConfigureScript("openclaw", "configureOpenClawMcp"));
  }
  if (integrations.find((integration) => integration.id === "hermes")?.ready) {
    results.push(await runConfigureScript("hermes", "configureHermesMcp"));
  }
  if (integrations.find((integration) => integration.id === "codex")?.ready) {
    results.push(await runConfigureScript("codex", "configureCodexMcp"));
  }
  return results;
}

async function runConfigureScript(integration: string, scriptName: string): Promise<{ integration: string; ok: boolean; message: string }> {
  const loaded = loadOrCreateHostBridgeConfig();
  const script = configureScriptCommand(scriptName);
  return await new Promise((resolvePromise) => {
    const child = spawn(script.command, script.args, {
      cwd: pcRoot,
      env: {
        ...process.env,
        PHONE_AGENT_TOKEN: process.env.PHONE_AGENT_TOKEN ?? loaded.config.phoneAgentToken,
        PHONE_AGENT_BRIDGE_URL: process.env.PHONE_AGENT_BRIDGE_URL ?? loaded.config.phoneAgentBridgeUrl ?? "http://127.0.0.1:8788"
      },
      stdio: ["ignore", "pipe", "pipe"]
    });
    let output = "";
    child.stdout.on("data", (chunk) => { output += chunk.toString(); });
    child.stderr.on("data", (chunk) => { output += chunk.toString(); });
    child.on("error", (error) => resolvePromise({ integration, ok: false, message: error.message }));
    child.on("close", (code) => resolvePromise({
      integration,
      ok: code === 0,
      message: code === 0 ? "MCP configuration updated." : output.trim() || `${scriptName} exited with code ${code ?? "null"}.`
    }));
  });
}

function configureScriptCommand(scriptName: string): { command: string; args: string[] } {
  const distScript = resolve(pcRoot, "dist", "scripts", `${scriptName}.js`);
  if (existsSync(distScript)) {
    return { command: process.execPath, args: [distScript] };
  }

  const sourceScript = resolve(pcRoot, "src", "scripts", `${scriptName}.ts`);
  const tsxBin = resolve(pcRoot, "node_modules", ".bin", process.platform === "win32" ? "tsx.cmd" : "tsx");
  if (existsSync(sourceScript) && existsSync(tsxBin)) {
    return { command: tsxBin, args: [sourceScript] };
  }

  return { command: process.execPath, args: [distScript] };
}
