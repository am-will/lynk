import assert from "node:assert/strict";
import test from "node:test";
import { realtimeVoiceInstructions, realtimeVoiceTools, selectRealtimeApiKey } from "./OpenAiRealtimeClient.js";
import { REALTIME_TOOL_NAMES } from "../protocol/messages.js";

test("realtime credentials prefer the host-owned API key", () => {
  assert.equal(selectRealtimeApiKey("host-key", "phone-key"), "host-key");
  assert.equal(selectRealtimeApiKey("", "phone-key"), "phone-key");
  assert.equal(selectRealtimeApiKey(undefined, "  "), undefined);
});

test("iOS realtime configuration excludes Android prompts and phone tools", () => {
  const prompt = realtimeVoiceInstructions({
    systemPrompt: "You are the native Lynk iOS assistant.",
    supportsPhoneControl: false
  });
  assert.doesNotMatch(prompt, /Android|Phone Control MCP|phone-control|phone screen|phone task/i);

  const names = realtimeVoiceTools(false).map((tool) => (tool as { name: string }).name);
  assert.equal(names.includes(REALTIME_TOOL_NAMES.runPhoneTask), false);
  assert.equal(names.includes(REALTIME_TOOL_NAMES.steerPhoneTask), false);
  assert.equal(names.includes(REALTIME_TOOL_NAMES.stopPhoneTask), false);
});

test("Android realtime configuration preserves phone-control tools", () => {
  const prompt = realtimeVoiceInstructions({ supportsPhoneControl: true });
  assert.match(prompt, /Android client supports phone control/);
  const names = realtimeVoiceTools(true).map((tool) => (tool as { name: string }).name);
  assert.equal(names.includes(REALTIME_TOOL_NAMES.runPhoneTask), true);
  assert.equal(names.includes(REALTIME_TOOL_NAMES.steerPhoneTask), true);
  assert.equal(names.includes(REALTIME_TOOL_NAMES.stopPhoneTask), true);
});
