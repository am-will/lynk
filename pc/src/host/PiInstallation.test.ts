import assert from "node:assert/strict";
import test from "node:test";
import { mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { isPiSdkInstalled, piSdkPackagePath } from "./PiInstallation.js";

test("Pi SDK installation detection is rooted in the bridge package tree", async () => {
  const root = join(tmpdir(), `lynk-pi-installation-${process.pid}-${Date.now()}`);
  try {
    assert.equal(isPiSdkInstalled(root), false);
    const packagePath = piSdkPackagePath(root);
    await mkdir(join(root, "node_modules", "@earendil-works", "pi-coding-agent"), { recursive: true });
    await writeFile(packagePath, "{}\n");
    assert.equal(isPiSdkInstalled(root), true);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
