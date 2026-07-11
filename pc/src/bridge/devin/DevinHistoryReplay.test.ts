import { describe, it } from "node:test";
import assert from "node:assert/strict";
import type { SessionNotification } from "@agentclientprotocol/sdk";
import { assembleReplayHistory } from "./DevinHistoryReplay.js";

function textChunk(
  sessionId: string,
  role: "user_message_chunk" | "agent_message_chunk",
  text: string,
  messageId?: string
): SessionNotification {
  return {
    sessionId,
    update: {
      sessionUpdate: role,
      messageId,
      content: { type: "text", text }
    }
  } as SessionNotification;
}

function thoughtChunk(sessionId: string, text: string): SessionNotification {
  return {
    sessionId,
    update: {
      sessionUpdate: "agent_thought_chunk",
      content: { type: "text", text }
    }
  } as SessionNotification;
}

function toolCallChunk(sessionId: string): SessionNotification {
  return {
    sessionId,
    update: {
      sessionUpdate: "tool_call",
      toolCallId: "tc-1",
      title: "Run command",
      content: [{ type: "text", text: "ignored" }]
    }
  } as unknown as SessionNotification;
}

describe("DevinHistoryReplay", () => {
  it("assembles user and agent chunks by messageId", () => {
    const notifications = [
      textChunk("s1", "user_message_chunk", "Hello ", "m1"),
      textChunk("s1", "user_message_chunk", "world", "m1"),
      textChunk("s1", "agent_message_chunk", "Hi ", "m2"),
      textChunk("s1", "agent_message_chunk", "there", "m2")
    ];
    const history = assembleReplayHistory(notifications);
    assert.equal(history.length, 2);
    assert.equal(history[0]?.role, "user");
    assert.equal(history[0]?.text, "Hello world");
    assert.equal(history[1]?.role, "assistant");
    assert.equal(history[1]?.text, "Hi there");
  });

  it("groups consecutive chunks by role when messageId is absent", () => {
    const notifications = [
      textChunk("s1", "user_message_chunk", "Q1 "),
      textChunk("s1", "user_message_chunk", "Q2"),
      textChunk("s1", "agent_message_chunk", "A1 "),
      textChunk("s1", "agent_message_chunk", "A2")
    ];
    const history = assembleReplayHistory(notifications);
    assert.equal(history.length, 2);
    assert.equal(history[0]?.text, "Q1 Q2");
    assert.equal(history[1]?.text, "A1 A2");
  });

  it("ignores thought and tool chunks", () => {
    const notifications = [
      textChunk("s1", "user_message_chunk", "Hello"),
      thoughtChunk("s1", "thinking..."),
      toolCallChunk("s1"),
      textChunk("s1", "agent_message_chunk", "World")
    ];
    const history = assembleReplayHistory(notifications);
    assert.equal(history.length, 2);
    assert.equal(history[0]?.text, "Hello");
    assert.equal(history[1]?.text, "World");
  });

  it("returns empty for unrelated update types", () => {
    const history = assembleReplayHistory([thoughtChunk("s1", "only thought")]);
    assert.equal(history.length, 0);
  });
});
