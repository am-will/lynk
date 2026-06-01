import assert from "node:assert/strict";
import test from "node:test";
import { parseAdbDevices, startAdbReverseMonitor } from "./AdbReverseMonitor.js";

test("parseAdbDevices returns only connected devices", () => {
  assert.deepEqual(parseAdbDevices([
    "List of devices attached",
    "192.168.1.192:5555\tdevice",
    "emulator-5554\toffline",
    "ABC123\tunauthorized",
    "USB456 device",
    ""
  ].join("\n")), ["192.168.1.192:5555", "USB456"]);
});

test("ADB reverse monitor is opt-in", () => {
  const previous = process.env.PHONE_AGENT_ADB_REVERSE;
  try {
    delete process.env.PHONE_AGENT_ADB_REVERSE;
    const monitor = startAdbReverseMonitor({ port: 8788, adbPath: "missing-adb-for-test", intervalMs: 1 });
    monitor.stop();
  } finally {
    if (previous === undefined) {
      delete process.env.PHONE_AGENT_ADB_REVERSE;
    } else {
      process.env.PHONE_AGENT_ADB_REVERSE = previous;
    }
  }
});
