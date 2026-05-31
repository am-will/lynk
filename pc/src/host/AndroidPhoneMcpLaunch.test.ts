import assert from "node:assert/strict";
import { mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import test from "node:test";
import { resolveAndroidPhoneMcpLaunchConfig } from "./AndroidPhoneMcpLaunch.js";

test("android phone MCP launch uses packaged dist bin when present", async () => {
  const pcRoot = await mkTempPcRoot();
  await mkdir(resolve(pcRoot, "dist/bin"), { recursive: true });
  await writeFile(resolve(pcRoot, "dist/bin/lynk-bridge-mcp.js"), "");

  try {
    const config = resolveAndroidPhoneMcpLaunchConfig({ pcRoot, nodeExecPath: "/usr/bin/node" });

    assert.deepEqual(config, {
      command: "/usr/bin/node",
      args: [resolve(pcRoot, "dist/bin/lynk-bridge-mcp.js")],
      cwd: pcRoot
    });
  } finally {
    await rm(pcRoot, { recursive: true, force: true });
  }
});

test("android phone MCP launch falls back to source checkout", async () => {
  const pcRoot = await mkTempPcRoot();
  await mkdir(resolve(pcRoot, "src/mcp"), { recursive: true });
  await mkdir(resolve(pcRoot, "node_modules/.bin"), { recursive: true });
  await writeFile(resolve(pcRoot, "src/mcp/androidPhoneServer.ts"), "");
  await writeFile(resolve(pcRoot, "node_modules/.bin/tsx"), "");

  try {
    const config = resolveAndroidPhoneMcpLaunchConfig({ pcRoot, nodeExecPath: "/usr/bin/node" });

    assert.deepEqual(config, {
      command: resolve(pcRoot, "node_modules/.bin/tsx"),
      args: [resolve(pcRoot, "src/mcp/androidPhoneServer.ts")],
      cwd: pcRoot
    });
  } finally {
    await rm(pcRoot, { recursive: true, force: true });
  }
});

async function mkTempPcRoot(): Promise<string> {
  const root = join(tmpdir(), `lynk-mcp-launch-${process.pid}-${Date.now()}-${Math.random().toString(16).slice(2)}`);
  await mkdir(root, { recursive: true });
  return root;
}
