import assert from "node:assert/strict";
import test from "node:test";

import { macLaunchAgentIsRunning, serviceInstallPlan } from "./ServiceManager.js";

test("macOS service plan preserves a useful PATH for managed harness commands", { skip: process.platform !== "darwin" }, () => {
  const originalPath = process.env.PATH;
  try {
    process.env.PATH = "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin";
    const plan = serviceInstallPlan();
    const plistCommand = plan.commands.find((command) => command.includes("dev.androidagent.bridge.plist")) ?? "";

    assert.match(plistCommand, /<key>WorkingDirectory<\/key>/);
    assert.match(plistCommand, /<key>PATH<\/key><string>\/opt\/homebrew\/bin:/);
    assert.equal(plan.commands.some((command) => command.includes("dev.openclaw.agent.bridge.plist")), true);
  } finally {
    if (originalPath === undefined) {
      delete process.env.PATH;
    } else {
      process.env.PATH = originalPath;
    }
  }
});

test("macOS service status distinguishes a running job from a registered crash loop", () => {
  assert.equal(macLaunchAgentIsRunning("state = spawn scheduled\nlast exit code = 1"), false);
  assert.equal(macLaunchAgentIsRunning("\tstate = running\n\tpid = 123"), true);
});
