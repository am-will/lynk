import assert from "node:assert/strict";
import test from "node:test";

import { serviceInstallPlan } from "./ServiceManager.js";

test("macOS service plan preserves a useful PATH for managed harness commands", { skip: process.platform !== "darwin" }, () => {
  const originalPath = process.env.PATH;
  try {
    process.env.PATH = "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin";
    const plan = serviceInstallPlan();
    const plistCommand = plan.commands.find((command) => command.includes("dev.androidagent.bridge.plist")) ?? "";

    assert.match(plistCommand, /<key>WorkingDirectory<\/key>/);
    assert.match(plistCommand, /<key>PATH<\/key><string>\/opt\/homebrew\/bin:/);
  } finally {
    if (originalPath === undefined) {
      delete process.env.PATH;
    } else {
      process.env.PATH = originalPath;
    }
  }
});
