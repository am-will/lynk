import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { SessionNotification } from "@agentclientprotocol/sdk";
import { DevinAcpEventNormalizer } from "./DevinAcpEventNormalizer.js";
import type { HarnessStoredSession } from "../harness/InMemoryHarnessSessionStore.js";

const session: HarnessStoredSession = {
  key: "devin:s1",
  sessionId: "s1",
  label: "s1",
  messages: [],
  updatedAt: 0
};

function notice(update: SessionNotification["update"], sessionId = "s1"): SessionNotification {
  return { sessionId, update };
}

describe("DevinAcpEventNormalizer", () => {
  it("aggregates only agent text while thoughts use the reasoning stream", () => {
    const events: Array<{ event: string; payload: Record<string, unknown> }> = [];
    const normalizer = new DevinAcpEventNormalizer((event, payload) => events.push({ event, payload: payload as Record<string, unknown> }));
    const first = normalizer.handle(session, "r1", notice({
      sessionUpdate: "agent_message_chunk",
      content: { type: "text", text: "Hel" }
    }));
    const second = normalizer.handle(session, "r1", notice({
      sessionUpdate: "agent_message_chunk",
      content: { type: "text", text: "lo" }
    }));
    normalizer.handle(session, "r1", notice({
      sessionUpdate: "agent_thought_chunk",
      content: { type: "text", text: "private thought" }
    }));
    normalizer.handle(session, "r1", notice({
      sessionUpdate: "user_message_chunk",
      content: { type: "text", text: "replay" }
    }));
    assert.equal(first?.textDelta, "Hel");
    assert.equal(second?.textDelta, "lo");
    assert.deepEqual(events.map(({ event, payload }) => [event, payload.state ?? payload.type]), [
      ["chat", "delta"],
      ["chat", "delta"],
      ["agent", "reasoning.delta"]
    ]);
  });

  it("replaces the stable plan event output with each complete snapshot", () => {
    const payloads: Record<string, unknown>[] = [];
    const normalizer = new DevinAcpEventNormalizer((_event, payload) => payloads.push(payload as Record<string, unknown>));
    normalizer.handle(session, "r1", notice({
      sessionUpdate: "plan",
      entries: [{ content: "First", priority: "high", status: "pending" }]
    }));
    normalizer.handle(session, "r1", notice({
      sessionUpdate: "plan",
      entries: [{ content: "Second", priority: "low", status: "completed" }]
    }));
    assert.equal(payloads[0]?.eventId, "devin_plan_s1");
    assert.equal(payloads[1]?.eventId, "devin_plan_s1");
    assert.deepEqual(payloads[1]?.output, [{ content: "Second", priority: "low", status: "completed" }]);
  });

  it("normalizes tool transitions and strips every _meta field", () => {
    const payloads: Record<string, unknown>[] = [];
    const normalizer = new DevinAcpEventNormalizer((_event, payload) => payloads.push(payload as Record<string, unknown>));
    normalizer.handle(session, "r1", notice({
      sessionUpdate: "tool_call",
      toolCallId: "tc1",
      title: "Edit file",
      kind: "edit",
      status: "in_progress",
      rawInput: { path: "a.ts", _meta: { secret: "no" }, nested: { _meta: "no", ok: true } },
      _meta: { secret: "never-forward" }
    }));
    normalizer.handle(session, "r1", notice({
      sessionUpdate: "tool_call_update",
      toolCallId: "tc1",
      status: "completed",
      content: [
        { type: "content", content: { type: "text", text: "done", _meta: { hidden: true } } },
        { type: "diff", path: "a.ts", oldText: "a", newText: "b", _meta: { hidden: true } }
      ],
      locations: [{ path: "a.ts", line: 4, _meta: { hidden: true } }]
    }));
    assert.equal(payloads[0]?.eventId, "devin_tool_tc1");
    assert.equal(payloads[0]?.status, "running");
    assert.deepEqual(payloads[0]?.args, { path: "a.ts", nested: { ok: true } });
    assert.equal(payloads[1]?.status, "completed");
    assert.equal(JSON.stringify(payloads).includes("_meta"), false);
    assert.equal(JSON.stringify(payloads).includes("never-forward"), false);
  });

  it("normalizes context usage and USD cost", () => {
    const normalizer = new DevinAcpEventNormalizer(() => undefined);
    const result = normalizer.handle(session, "r1", notice({
      sessionUpdate: "usage_update",
      used: 100,
      size: 200,
      cost: { amount: 1.25, currency: "USD" }
    }));
    assert.deepEqual(result?.usage, {
      contextTokens: 100,
      contextWindowTokens: 200,
      estimatedCostUsd: 1.25
    });
  });

  it("does not mix notifications from another session", () => {
    const events: unknown[] = [];
    const normalizer = new DevinAcpEventNormalizer((_event, payload) => events.push(payload));
    const result = normalizer.handle(session, "r1", notice({
      sessionUpdate: "agent_message_chunk",
      content: { type: "text", text: "wrong" }
    }, "s2"));
    assert.equal(result, undefined);
    assert.deepEqual(events, []);
  });
});
