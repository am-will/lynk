import assert from "node:assert/strict";
import test from "node:test";
import { selectRealtimeApiKey } from "./OpenAiRealtimeClient.js";

test("realtime credentials prefer the host-owned API key", () => {
  assert.equal(selectRealtimeApiKey("host-key", "phone-key"), "host-key");
  assert.equal(selectRealtimeApiKey("", "phone-key"), "phone-key");
  assert.equal(selectRealtimeApiKey(undefined, "  "), undefined);
});
