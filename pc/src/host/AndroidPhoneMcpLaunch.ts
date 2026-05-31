import { existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export interface AndroidPhoneMcpLaunchConfig {
  command: string;
  args: string[];
  cwd: string;
}

const hostDir = dirname(fileURLToPath(import.meta.url));
const defaultPcRoot = resolve(hostDir, "../..");

export function resolveAndroidPhoneMcpLaunchConfig(options: {
  pcRoot?: string;
  nodeExecPath?: string;
} = {}): AndroidPhoneMcpLaunchConfig {
  const pcRoot = options.pcRoot ?? defaultPcRoot;
  const nodeExecPath = options.nodeExecPath ?? process.execPath;
  const distBin = resolve(pcRoot, "dist/bin/lynk-bridge-mcp.js");
  if (existsSync(distBin)) {
    return {
      command: nodeExecPath,
      args: [distBin],
      cwd: pcRoot
    };
  }

  const sourceServer = resolve(pcRoot, "src/mcp/androidPhoneServer.ts");
  const tsxBin = resolve(pcRoot, "node_modules/.bin", process.platform === "win32" ? "tsx.cmd" : "tsx");
  if (existsSync(sourceServer) && existsSync(tsxBin)) {
    return {
      command: tsxBin,
      args: [sourceServer],
      cwd: pcRoot
    };
  }

  throw new Error(
    `Unable to locate the Lynk MCP server. Expected ${distBin} in a packaged install, or ${sourceServer} with ${tsxBin} in a source checkout.`
  );
}
