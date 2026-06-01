import assert from "node:assert/strict";
import { mkdir, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import test from "node:test";

import { commandExecutable, resolveCommand } from "./CommandDiscovery.js";

test("commandExecutable handles bare, quoted, and empty commands", () => {
  assert.equal(commandExecutable("codex app-server --listen stdio://"), "codex");
  assert.equal(commandExecutable("\"/Applications/Codex CLI/codex\" app-server"), "/Applications/Codex CLI/codex");
  assert.equal(commandExecutable("'/opt/hermes bin/hermes' serve"), "/opt/hermes bin/hermes");
  assert.equal(commandExecutable("   "), "");
});

test("resolveCommand finds OpenCode's default user install outside PATH", async () => {
  const originalPath = process.env.PATH;
  const originalHome = process.env.HOME;
  const dir = join(tmpdir(), `lynk-opencode-discovery-${process.pid}-${Date.now()}`);
  try {
    const bin = join(dir, ".opencode", "bin");
    await mkdir(bin, { recursive: true });
    await writeFile(join(bin, "opencode"), "#!/bin/sh\n");
    process.env.PATH = "";
    process.env.HOME = dir;

    const resolution = resolveCommand("opencode serve --hostname 127.0.0.1 --port 4096");

    assert.equal(resolution.available, true);
    assert.equal(resolution.resolvedPath, join(bin, "opencode"));
  } finally {
    if (originalPath === undefined) {
      delete process.env.PATH;
    } else {
      process.env.PATH = originalPath;
    }
    if (originalHome === undefined) {
      delete process.env.HOME;
    } else {
      process.env.HOME = originalHome;
    }
    await rm(dir, { recursive: true, force: true });
  }
});
