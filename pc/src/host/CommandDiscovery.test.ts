import assert from "node:assert/strict";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";
import { tmpdir } from "node:os";
import test from "node:test";

import { commandExecutable, resolvedCommandWithArgs, resolveCommand } from "./CommandDiscovery.js";

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

test("resolveCommand finds Devin's default user install outside PATH", async () => {
  const originalPath = process.env.PATH;
  const originalHome = process.env.HOME;
  const dir = join(tmpdir(), `lynk-devin-discovery-${process.pid}-${Date.now()}`);
  try {
    const bin = join(dir, ".local", "bin");
    await mkdir(bin, { recursive: true });
    await writeFile(join(bin, "devin"), "#!/bin/sh\n");
    process.env.PATH = "";
    process.env.HOME = dir;

    const resolution = resolveCommand("devin acp");

    assert.equal(resolution.available, true);
    assert.equal(resolution.resolvedPath, join(bin, "devin"));
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

test("resolveCommand preserves absolute executable and args with spaces", async () => {
  const originalPath = process.env.PATH;
  const dir = await mkdtemp(join(tmpdir(), "lynk-devin-abs-"));
  const executable = join(dir, "Devin CLI", "devin");
  try {
    await mkdir(dirname(executable), { recursive: true });
    await writeFile(executable, "#!/bin/sh\n");
    process.env.PATH = "";

    const resolution = resolveCommand(`"${executable}" acp --verbose`);

    assert.equal(resolution.available, true);
    assert.equal(resolution.resolvedPath, executable);
  } finally {
    if (originalPath === undefined) {
      delete process.env.PATH;
    } else {
      process.env.PATH = originalPath;
    }
    await rm(dir, { recursive: true, force: true });
  }
});

test("resolvedCommandWithArgs quotes executables with whitespace and preserves args", () => {
  assert.equal(resolvedCommandWithArgs("devin acp", "/opt/devin/bin/devin"), "/opt/devin/bin/devin acp");
  assert.equal(resolvedCommandWithArgs("devin acp", "/Applications/Devin CLI/devin"), `"/Applications/Devin CLI/devin" acp`);
  assert.equal(resolvedCommandWithArgs("devin acp --verbose", "/Applications/Devin CLI/devin"), `"/Applications/Devin CLI/devin" acp --verbose`);
  assert.equal(resolvedCommandWithArgs("devin", "/Applications/Devin CLI/devin"), `"/Applications/Devin CLI/devin"`);
});

test("resolvedCommandWithArgs round-trips through resolveCommand for whitespace paths", async () => {
  const originalPath = process.env.PATH;
  const dir = await mkdtemp(join(tmpdir(), "lynk-cmd-roundtrip-"));
  const executable = join(dir, "Devin CLI", "devin");
  try {
    await mkdir(dirname(executable), { recursive: true });
    await writeFile(executable, "#!/bin/sh\n");
    process.env.PATH = "";

    const original = `"${executable}" acp`;
    const resolution = resolveCommand(original);
    assert.equal(resolution.available, true);
    const recomposed = resolvedCommandWithArgs(original, resolution.resolvedPath ?? "");
    assert.equal(recomposed, `"${executable}" acp`);
    const roundTrip = resolveCommand(recomposed);
    assert.equal(roundTrip.available, true);
    assert.equal(roundTrip.resolvedPath, executable);
  } finally {
    if (originalPath === undefined) {
      delete process.env.PATH;
    } else {
      process.env.PATH = originalPath;
    }
    await rm(dir, { recursive: true, force: true });
  }
});
