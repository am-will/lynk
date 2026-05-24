import assert from "node:assert/strict";
import test from "node:test";
import { formatStatusReport, type ChatFormatterState } from "./OpenClawChatFormatters.js";

function statusState(overrides: Partial<ChatFormatterState> = {}): ChatFormatterState {
  return {
    harnessId: "openclaw",
    sessionKey: "agent:main:explicit:open-claw-agent-pixel",
    runId: null,
    model: "gpt-5.5",
    reasoningEffort: "medium",
    reasoningStream: null,
    fastMode: null,
    verboseLevel: "full",
    pendingRuns: new Map(),
    sessionSummaries: new Map([["one", { key: "one" }]]),
    ...overrides
  };
}

test("status report unwraps active harness health and omits noisy idle fields", () => {
  const text = formatStatusReport(statusState(), {
    harnesses: {
      openclaw: { ok: true, eventLoop: { degraded: false } }
    }
  });

  assert.match(text, /Gateway: ok/);
  assert.match(text, /Fast mode: off/);
  assert.doesNotMatch(text, /Run: idle/);
  assert.doesNotMatch(text, /Verbose:/);
  assert.doesNotMatch(text, /Known sessions:/);
  assert.doesNotMatch(text, /unknown/);
});

test("status report still includes an active run id", () => {
  const text = formatStatusReport(statusState({ runId: "run_1" }), { ok: true });

  assert.match(text, /Run: run_1/);
});
