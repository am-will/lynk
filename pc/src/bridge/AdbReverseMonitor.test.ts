import assert from "node:assert/strict";
import test from "node:test";
import { parseAdbDevices } from "./AdbReverseMonitor.js";

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
