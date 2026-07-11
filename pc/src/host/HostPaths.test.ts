import assert from "node:assert/strict";
import test from "node:test";
import { join, resolve } from "node:path";
import { createHostPaths, hostPathsForConfigPath, ownedPath } from "./HostPaths.js";

test("platform defaults separate immutable package assets from mutable private data", () => {
  const installRoot = resolve("/opt/lynk/package");
  const mac = createHostPaths({ platform: "darwin", homeDir: "/Users/test", env: {}, installRoot });
  const linux = createHostPaths({ platform: "linux", homeDir: "/home/test", env: {}, installRoot });
  const windows = createHostPaths({ platform: "win32", homeDir: "C:\\Users\\test", env: { ProgramData: "C:\\ProgramData" }, installRoot });

  assert.equal(mac.dataRoot, resolve("/Users/test/Library/Application Support/Android Agent Bridge"));
  assert.equal(linux.dataRoot, resolve("/home/test/.config/android-agent-bridge"));
  assert.match(windows.dataRoot, /ProgramData.*AndroidAgentBridge/u);
  for (const paths of [mac, linux, windows]) {
    assert.notEqual(paths.installRoot, paths.dataRoot);
    assert.equal(paths.blobRoot, join(paths.dataRoot, "blobs"));
    assert.equal(paths.sessionsRoot, join(paths.dataRoot, "sessions"));
  }
});

test("explicit config paths own sibling mutable roots without using cwd or install root", () => {
  const paths = hostPathsForConfigPath("/private/data/config.json", "/immutable/package");
  assert.equal(paths.dataRoot, resolve("/private/data"));
  assert.equal(paths.auditRoot, resolve("/private/data/audit"));
  assert.notEqual(paths.dataRoot, resolve(process.cwd()));
  assert.notEqual(paths.dataRoot, paths.installRoot);
});

test("owned paths reject traversal, absolute paths, and sibling-prefix confusion", () => {
  const root = resolve("/tmp/lynk/data");
  assert.equal(ownedPath(root, "sessions", "codex.json"), join(root, "sessions", "codex.json"));
  assert.throws(() => ownedPath(root, "../database/secret"), /escapes/u);
  assert.throws(() => ownedPath(root, "/tmp/lynk/database"), /relative/u);
  assert.throws(() => ownedPath(root, ""), /non-empty/u);
});
