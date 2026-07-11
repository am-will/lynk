import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { SessionNotification } from "@agentclientprotocol/sdk";

import { DevinAcpClient } from "./DevinAcpClient.js";
import {
  createConfigurableDevinProcess,
  devinCapabilities,
  LONG_TIMEOUT_MS,
  type FakeControls
} from "./DevinAcpFixtures.js";
import { DevinSessionUpdateCollector } from "./DevinHistoryReplay.js";

interface RemoteSession {
  readonly sessionId: string;
  readonly cwd: string;
  readonly marker: string;
  readonly history: SessionNotification[];
}

function textUpdate(
  sessionId: string,
  role: "user_message_chunk" | "agent_message_chunk",
  text: string,
  messageId: string
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

describe("Devin ACP restart acceptance", () => {
  it("lists, loads, replays, and retains context through a fresh ACP process", async () => {
    const workspace = "/tmp/lynk-devin-stateful-fake";
    const marker = "LYNK_FAKE_RESTART_MARKER_7d3e";
    const remote = new Map<string, RemoteSession>();
    const processes: FakeControls[] = [];
    let generation = 0;
    let listCalls = 0;
    let loadCalls = 0;

    const processFactory = {
      create: async () => {
        generation += 1;
        let controls: FakeControls;
        controls = createConfigurableDevinProcess({
          capabilities: devinCapabilities(),
          handlers: {
            sessionNew: ({ cwd }) => {
              const sessionId = "stateful-restart-session";
              remote.set(sessionId, { sessionId, cwd, marker, history: [] });
              return { sessionId };
            },
            sessionList: () => {
              listCalls += 1;
              return {
                sessions: [...remote.values()].map((session) => ({
                  sessionId: session.sessionId,
                  cwd: session.cwd,
                  title: "Stateful restart acceptance"
                }))
              };
            },
            sessionLoad: ({ sessionId, cwd }) => {
              loadCalls += 1;
              const session = remote.get(sessionId);
              assert.ok(session, `unknown remote session ${sessionId}`);
              assert.equal(cwd, session.cwd);
              controls.pushReplay?.(session.history);
              return {};
            },
            sessionPrompt: ({ sessionId, prompt }) => {
              const session = remote.get(sessionId);
              assert.ok(session, `unknown remote session ${sessionId}`);
              const text = prompt
                .filter((part): part is Extract<(typeof prompt)[number], { type: "text" }> => part.type === "text")
                .map((part) => part.text)
                .join("");
              const turn = session.history.length;
              const user = textUpdate(sessionId, "user_message_chunk", text, `user-${turn}`);
              const answer = text.includes("What marker") ? session.marker : "Marker stored.";
              const agent = textUpdate(sessionId, "agent_message_chunk", answer, `agent-${turn}`);
              session.history.push(user, agent);
              controls.pushReplay?.([user, agent]);
              return { stopReason: "end_turn" };
            }
          }
        });
        processes.push(controls);
        return controls.process;
      }
    };

    const first = new DevinAcpClient({
      command: "devin acp",
      cwd: workspace,
      processFactory,
      startupTimeoutMs: LONG_TIMEOUT_MS,
      requestTimeoutMs: LONG_TIMEOUT_MS
    });
    const created = await first.sessionNew({ cwd: workspace, mcpServers: [] });
    await first.sessionPrompt({
      sessionId: created.sessionId,
      prompt: [{ type: "text", text: `Remember this exact marker: ${marker}` }]
    });
    await first.close();

    const second = new DevinAcpClient({
      command: "devin acp",
      cwd: workspace,
      processFactory,
      startupTimeoutMs: LONG_TIMEOUT_MS,
      requestTimeoutMs: LONG_TIMEOUT_MS
    });
    const listed = await second.sessionList({});
    assert.ok(listed.sessions.some((session) => session.sessionId === created.sessionId));

    const replay = new DevinSessionUpdateCollector(second);
    await second.sessionLoad({ sessionId: created.sessionId, cwd: workspace, mcpServers: [] });
    const loadedHistory = replay.snapshot(created.sessionId).messages;
    assert.ok(loadedHistory.some((message) => message.role === "user" && message.text.includes(marker)));

    await second.sessionPrompt({
      sessionId: created.sessionId,
      prompt: [{ type: "text", text: "What marker did I ask you to remember? Reply with it exactly." }]
    });
    const afterRecovery = replay.snapshot(created.sessionId).messages;
    assert.equal(afterRecovery.at(-1)?.text, marker);

    replay.detach();
    await second.close();
    assert.equal(generation, 2);
    assert.equal(listCalls, 1);
    assert.equal(loadCalls, 1);
    assert.equal(processes.length, 2);
  });
});
