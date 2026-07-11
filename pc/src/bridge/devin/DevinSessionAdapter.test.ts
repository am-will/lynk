import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { homedir, tmpdir } from "node:os";
import { join } from "node:path";
import type { SessionConfigOption, SessionConfigSelectOptions, SessionNotification } from "@agentclientprotocol/sdk";
import { ChatClientError } from "../chat/ChatErrors.js";
import { InMemoryHarnessSessionStore } from "../harness/InMemoryHarnessSessionStore.js";
import {
  createConfigurableDevinProcess,
  devinCapabilities,
  LONG_TIMEOUT_MS,
  type FakeControls
} from "./DevinAcpFixtures.js";
import { DevinSessionAdapter } from "./DevinSessionAdapter.js";

function tmpStorage(): { storagePath: string; cleanup: () => void } {
  const dir = mkdtempSync(join(tmpdir(), "devin-session-"));
  return {
    storagePath: join(dir, "devin-sessions.json"),
    cleanup: () => rmSync(dir, { recursive: true, force: true })
  };
}

function defaultModelOptions(): SessionConfigOption[] {
  return [
    {
      id: "model",
      name: "Model",
      type: "select",
      category: "model",
      currentValue: "default",
      options: [
        { value: "default", name: "Devin default" },
        { value: "claude-sonnet", name: "Claude Sonnet" }
      ]
    },
    {
      id: "thought",
      name: "Thought level",
      type: "select",
      category: "thought_level",
      currentValue: "normal",
      options: [
        { value: "minimal", name: "Minimal" },
        { value: "normal", name: "Normal" },
        { value: "deep", name: "Deep" }
      ]
    }
  ];
}

function buildAdapter(controls: FakeControls, storagePath: string, cwd = "/test"): DevinSessionAdapter {
  return new DevinSessionAdapter({
    command: "devin acp",
    cwd,
    storagePath,
    startupTimeoutMs: LONG_TIMEOUT_MS,
    requestTimeoutMs: LONG_TIMEOUT_MS,
    processFactory: { create: async () => controls.process }
  });
}

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

function availableCommandsUpdate(sessionId: string): SessionNotification {
  return {
    sessionId,
    update: {
      sessionUpdate: "available_commands_update",
      availableCommands: [
        {
          name: "review",
          description: "Review the workspace",
          input: { hint: "Optional focus" }
        }
      ]
    }
  } as SessionNotification;
}

describe("DevinSessionAdapter", () => {
  describe("workspace", () => {
    it("creates a session in an existing workspace", async () => {
      const { storagePath, cleanup } = tmpStorage();
      const dir = mkdtempSync(join(tmpdir(), "devin-ws-"));
      const controls = createConfigurableDevinProcess({
        handlers: {
          sessionNew: (params) => {
            assert.equal(params.cwd, dir);
            return { sessionId: "session-1", configOptions: defaultModelOptions() };
          }
        }
      });
      const adapter = buildAdapter(controls, storagePath, dir);
      const created = await adapter.createSession({ workspacePath: dir });
      assert.equal(created.key, "devin:session-1");
      assert.equal(created.workspacePath, dir);
      cleanup();
      rmSync(dir, { recursive: true, force: true });
    });

    it("expands ~ to the real home directory", async () => {
      const { storagePath, cleanup } = tmpStorage();
      const controls = createConfigurableDevinProcess({
        handlers: {
          sessionNew: (params) => {
            assert.ok(params.cwd.startsWith("/"));
            assert.ok(!params.cwd.includes("~"));
            return { sessionId: "session-home", configOptions: defaultModelOptions() };
          }
        }
      });
      const adapter = buildAdapter(controls, storagePath, "/test");
      const created = await adapter.createSession({ workspacePath: "~" });
      assert.equal(created.key, "devin:session-home");
      cleanup();
    });

    it("expands ~/... below the real home directory", async () => {
      const { storagePath, cleanup } = tmpStorage();
      const relative = `.lynk-devin-test-${Date.now()}`;
      const expected = join(homedir(), relative);
      const controls = createConfigurableDevinProcess({
        handlers: {
          sessionNew: (params) => {
            assert.equal(params.cwd, expected);
            return { sessionId: "session-home-child", configOptions: defaultModelOptions() };
          }
        }
      });
      const adapter = buildAdapter(controls, storagePath, "/test");
      try {
        const created = await adapter.createSession({
          workspacePath: `~/${relative}`,
          createWorkspaceIfMissing: true
        });
        assert.equal(created.workspacePath, expected);
      } finally {
        cleanup();
        rmSync(expected, { recursive: true, force: true });
      }
    });

    it("throws devin.workspace_not_found when folder missing and createIfMissing false", async () => {
      const { storagePath, cleanup } = tmpStorage();
      const controls = createConfigurableDevinProcess();
      const adapter = buildAdapter(controls, storagePath, "/test");
      await assert.rejects(
        adapter.createSession({ workspacePath: "/definitely/missing/path", createWorkspaceIfMissing: false }),
        (error) => error instanceof ChatClientError && error.code === "devin.workspace_not_found"
      );
      cleanup();
    });

    it("creates missing workspace recursively when createIfMissing true", async () => {
      const { storagePath, cleanup } = tmpStorage();
      const parent = mkdtempSync(join(tmpdir(), "devin-create-"));
      const newWorkspace = join(parent, "nested", "workspace");
      const controls = createConfigurableDevinProcess({
        handlers: {
          sessionNew: (params) => {
            assert.equal(params.cwd, newWorkspace);
            return { sessionId: "session-created", configOptions: defaultModelOptions() };
          }
        }
      });
      const adapter = buildAdapter(controls, storagePath, "/test");
      const created = await adapter.createSession({ workspacePath: newWorkspace, createWorkspaceIfMissing: true });
      assert.equal(created.workspacePath, newWorkspace);
      cleanup();
      rmSync(parent, { recursive: true, force: true });
    });

    it("rejects a file path as workspace", async () => {
      const { storagePath, cleanup } = tmpStorage();
      const file = join(tmpdir(), `devin-file-${Date.now()}`);
      writeFileSync(file, "not a folder");
      const controls = createConfigurableDevinProcess();
      const adapter = buildAdapter(controls, storagePath, "/test");
      await assert.rejects(
        adapter.createSession({ workspacePath: file, createWorkspaceIfMissing: true }),
        /not a folder/
      );
      cleanup();
      rmSync(file, { force: true });
    });

    it("isolates sessions by workspace", async () => {
      const { storagePath, cleanup } = tmpStorage();
      const dirA = mkdtempSync(join(tmpdir(), "devin-a-"));
      const dirB = mkdtempSync(join(tmpdir(), "devin-b-"));
      let newCount = 0;
      const controls = createConfigurableDevinProcess({
        handlers: {
          sessionNew: (params) => {
            newCount += 1;
            return {
              sessionId: `session-${params.cwd === dirA ? "a" : "b"}`,
              configOptions: defaultModelOptions()
            };
          }
        }
      });
      const adapter = buildAdapter(controls, storagePath, "/test");
      const createdA = await adapter.createSession({ workspacePath: dirA });
      const createdB = await adapter.createSession({ workspacePath: dirB });
      assert.equal(createdA.key, "devin:session-a");
      assert.equal(createdB.key, "devin:session-b");
      assert.equal(createdA.workspacePath, dirA);
      assert.equal(createdB.workspacePath, dirB);
      assert.equal(newCount, 2);
      cleanup();
      rmSync(dirA, { recursive: true, force: true });
      rmSync(dirB, { recursive: true, force: true });
    });
  });

  describe("createSession", () => {
    it("uses exact devin:<returned-id> as key", async () => {
      const { storagePath, cleanup } = tmpStorage();
      const controls = createConfigurableDevinProcess({
        handlers: {
          sessionNew: () => ({ sessionId: "zinc-potato", configOptions: defaultModelOptions() })
        }
      });
      const adapter = buildAdapter(controls, storagePath);
      const created = await adapter.createSession({});
      assert.equal(created.key, "devin:zinc-potato");
      assert.equal(created.sessionId, "zinc-potato");
      cleanup();
    });

    it("uses ACP returned identity and sends only cwd with an empty MCP list", async () => {
      const { storagePath, cleanup } = tmpStorage();
      const dir = mkdtempSync(join(tmpdir(), "devin-payload-"));
      const controls = createConfigurableDevinProcess({
        handlers: {
          sessionNew: (params) => {
            assert.deepEqual(params, { cwd: dir, mcpServers: [] });
            return { sessionId: "acp-authoritative", configOptions: defaultModelOptions() };
          }
        }
      });
      const adapter = buildAdapter(controls, storagePath, dir);
      const created = await adapter.createSession({
        key: "devin:caller-supplied",
        workspacePath: dir,
        model: "default"
      });
      assert.equal(created.key, "devin:acp-authoritative");
      assert.equal(created.sessionId, "acp-authoritative");
      cleanup();
      rmSync(dir, { recursive: true, force: true });
    });

    it("sets model config option when requested model is advertised", async () => {
      const { storagePath, cleanup } = tmpStorage();
      let setConfigCalled = false;
      const controls = createConfigurableDevinProcess({
        handlers: {
          sessionNew: () => ({ sessionId: "m1", configOptions: defaultModelOptions() }),
          sessionSetConfigOption: (params) => {
            assert.equal(params.configId, "model");
            assert.equal(params.value, "claude-sonnet");
            setConfigCalled = true;
            return {
              configOptions: [
                {
                  id: "model",
                  name: "Model",
                  type: "select",
                  category: "model",
                  currentValue: "claude-sonnet",
                  options: [
                    { value: "default", name: "Devin default" },
                    { value: "claude-sonnet", name: "Claude Sonnet" }
                  ]
                }
              ]
            };
          }
        }
      });
      const adapter = buildAdapter(controls, storagePath);
      const created = await adapter.createSession({ model: "claude-sonnet" });
      assert.equal(setConfigCalled, true);
      assert.equal(created.key, "devin:m1");
      cleanup();
    });

    it("leaves current value unchanged when requested model is default", async () => {
      const { storagePath, cleanup } = tmpStorage();
      let setConfigCalled = false;
      const controls = createConfigurableDevinProcess({
        handlers: {
          sessionNew: () => ({ sessionId: "m2", configOptions: defaultModelOptions() }),
          sessionSetConfigOption: () => {
            setConfigCalled = true;
            return { configOptions: defaultModelOptions() };
          }
        }
      });
      const adapter = buildAdapter(controls, storagePath);
      await adapter.createSession({ model: "default" });
      assert.equal(setConfigCalled, false);
      cleanup();
    });

    it("persists model and workspace metadata immediately", async () => {
      const { storagePath, cleanup } = tmpStorage();
      const dir = mkdtempSync(join(tmpdir(), "devin-persist-"));
      const controls = createConfigurableDevinProcess({
        handlers: {
          sessionNew: () => ({ sessionId: "p1", configOptions: defaultModelOptions() })
        }
      });
      const adapter = buildAdapter(controls, storagePath, dir);
      await adapter.createSession({ workspacePath: dir, model: "claude-sonnet" });
      const persisted = JSON.parse(readFileSync(storagePath, "utf8")) as { sessions: Array<{ key: string; model: string; metadata: Record<string, unknown> }> };
      assert.equal(persisted.sessions.length, 1);
      assert.equal(persisted.sessions[0]?.key, "devin:p1");
      assert.equal(persisted.sessions[0]?.model, "claude-sonnet");
      assert.equal(persisted.sessions[0]?.metadata.workspacePath, dir);
      cleanup();
      rmSync(dir, { recursive: true, force: true });
    });

    it("reconstructs two empty Lynk sessions with their separate workspaces", async () => {
      const { storagePath, cleanup } = tmpStorage();
      const dirA = mkdtempSync(join(tmpdir(), "devin-reconstruct-a-"));
      const dirB = mkdtempSync(join(tmpdir(), "devin-reconstruct-b-"));
      const firstControls = createConfigurableDevinProcess({
        handlers: {
          sessionNew: (params) => ({
            sessionId: params.cwd === dirA ? "persist-a" : "persist-b",
            configOptions: defaultModelOptions()
          })
        }
      });
      const first = buildAdapter(firstControls, storagePath);
      await first.createSession({ workspacePath: dirA, model: "claude-sonnet" });
      await first.createSession({ workspacePath: dirB });
      first.close();

      const capsWithoutList = {
        ...devinCapabilities(),
        agentCapabilities: { loadSession: true, sessionCapabilities: {}, promptCapabilities: {} }
      };
      const reconstructed = buildAdapter(
        createConfigurableDevinProcess({ capabilities: capsWithoutList }),
        storagePath
      );
      const listed = await reconstructed.listSessions(10);
      assert.deepEqual(
        listed.sessions.map((session) => [session.key, session.workspacePath, session.model]).sort(),
        [
          ["devin:persist-a", dirA, "claude-sonnet"],
          ["devin:persist-b", dirB, "default"]
        ]
      );
      cleanup();
      rmSync(dirA, { recursive: true, force: true });
      rmSync(dirB, { recursive: true, force: true });
    });
  });

  describe("history and load", () => {
    it("loads and replaces history with replay", async () => {
      const { storagePath, cleanup } = tmpStorage();
      const dir = mkdtempSync(join(tmpdir(), "devin-load-"));
      const creatorControls = createConfigurableDevinProcess({
        handlers: {
          sessionNew: () => ({ sessionId: "load-1", configOptions: defaultModelOptions() })
        }
      });
      const creator = buildAdapter(creatorControls, storagePath, dir);
      await creator.createSession({ workspacePath: dir });
      creator.close();

      let loadCalled = false;
      let loaderControls: FakeControls;
      loaderControls = createConfigurableDevinProcess({
        handlers: {
          sessionLoad: (params) => {
            loadCalled = true;
            assert.equal(params.sessionId, "load-1");
            assert.equal(params.cwd, dir);
            loaderControls.pushReplay?.([
              textChunk("load-1", "user_message_chunk", "Hello ", "m1"),
              textChunk("load-1", "user_message_chunk", "world", "m1"),
              textChunk("load-1", "agent_message_chunk", "Hi ", "m2"),
              textChunk("load-1", "agent_message_chunk", "there", "m2")
            ]);
            return { configOptions: defaultModelOptions() };
          }
        }
      });
      const adapter = buildAdapter(loaderControls, storagePath, dir);

      const history = await adapter.history("devin:load-1");
      assert.equal(loadCalled, true);
      assert.equal(history.messages.length, 2);
      assert.equal(history.messages[0]?.role, "user");
      assert.equal(history.messages[0]?.text, "Hello world");
      assert.equal(history.messages[1]?.role, "assistant");
      assert.equal(history.messages[1]?.text, "Hi there");
      cleanup();
      rmSync(dir, { recursive: true, force: true });
    });

    it("does not call load again once attached", async () => {
      const { storagePath, cleanup } = tmpStorage();
      const creatorControls = createConfigurableDevinProcess({
        handlers: {
          sessionNew: () => ({ sessionId: "load-twice", configOptions: defaultModelOptions() })
        }
      });
      const creator = buildAdapter(creatorControls, storagePath);
      await creator.createSession({});
      creator.close();

      let loadCount = 0;
      const controls = createConfigurableDevinProcess({
        handlers: {
          sessionLoad: () => {
            loadCount += 1;
            return { configOptions: defaultModelOptions() };
          }
        }
      });
      const adapter = buildAdapter(controls, storagePath);
      await adapter.history("devin:load-twice");
      await adapter.history("devin:load-twice");
      assert.equal(loadCount, 1);
      cleanup();
    });

    it("leaves cache unchanged when load fails", async () => {
      const { storagePath, cleanup } = tmpStorage();
      const creatorControls = createConfigurableDevinProcess({
        handlers: {
          sessionNew: () => ({ sessionId: "load-fail", configOptions: defaultModelOptions() })
        }
      });
      const creator = buildAdapter(creatorControls, storagePath);
      await creator.createSession({});
      const creatorStore = (creator as unknown as { store: InMemoryHarnessSessionStore }).store;
      creatorStore.replaceHistory("devin:load-fail", [{ id: "keep", role: "user", text: "keep me", timestamp: 1 }]);
      creator.close();

      const loaderControls = createConfigurableDevinProcess({
        handlers: {
          sessionLoad: () => {
            throw new Error("load failed");
          }
        }
      });
      const adapter = buildAdapter(loaderControls, storagePath);
      const store = (adapter as unknown as { store: InMemoryHarnessSessionStore }).store;

      await assert.rejects(adapter.history("devin:load-fail"));
      const history = store.history("devin:load-fail");
      assert.equal(history.messages[0]?.text, "keep me");
      cleanup();
    });

    it("clears attachment on transport restart and triggers a load", async () => {
      const { storagePath, cleanup } = tmpStorage();
      let loadCount = 0;
      const controls: FakeControls[] = [];
      let factoryCalls = 0;
      const factory = {
        create: async () => {
          factoryCalls += 1;
          let pushReplay: ((notifications: SessionNotification[]) => void) | undefined;
          const c = createConfigurableDevinProcess({
            handlers: {
              sessionNew: () => ({ sessionId: "restart", configOptions: defaultModelOptions() }),
              sessionLoad: () => {
                loadCount += 1;
                return { configOptions: defaultModelOptions() };
              }
            }
          });
          pushReplay = c.pushReplay;
          controls.push(c);
          return c.process;
        }
      };
      const adapter = new DevinSessionAdapter({
        command: "devin acp",
        cwd: "/test",
        storagePath,
        startupTimeoutMs: LONG_TIMEOUT_MS,
        requestTimeoutMs: LONG_TIMEOUT_MS,
        processFactory: factory
      });
      await adapter.createSession({});
      await adapter.history("devin:restart");
      assert.equal(loadCount, 0);

      controls[0]!.exit(1);
      await new Promise((resolve) => setTimeout(resolve, 100));

      await adapter.history("devin:restart");
      assert.equal(factoryCalls, 2);
      assert.equal(loadCount, 1);
      cleanup();
    });

    it("uses persisted cwd and replaces stale cache after adapter reconstruction", async () => {
      const { storagePath, cleanup } = tmpStorage();
      const dir = mkdtempSync(join(tmpdir(), "devin-reconstructed-load-"));
      const firstControls = createConfigurableDevinProcess({
        handlers: {
          sessionNew: () => ({ sessionId: "reconstructed", configOptions: defaultModelOptions() })
        }
      });
      const first = buildAdapter(firstControls, storagePath);
      await first.createSession({ workspacePath: dir });
      const firstStore = (first as unknown as { store: InMemoryHarnessSessionStore }).store;
      firstStore.replaceHistory("devin:reconstructed", [
        { id: "stale", role: "assistant", text: "stale local cache", timestamp: 1 }
      ]);
      first.close();

      let secondControls: FakeControls;
      secondControls = createConfigurableDevinProcess({
        handlers: {
          sessionLoad: (params) => {
            assert.deepEqual(params, {
              sessionId: "reconstructed",
              cwd: dir,
              mcpServers: []
            });
            secondControls.pushReplay?.([
              textChunk("reconstructed", "user_message_chunk", "authoritative question", "u1"),
              textChunk("reconstructed", "agent_message_chunk", "authoritative answer", "a1")
            ]);
            return { configOptions: defaultModelOptions() };
          }
        }
      });
      const reconstructed = buildAdapter(secondControls, storagePath);
      const history = await reconstructed.history("devin:reconstructed");
      assert.deepEqual(history.messages.map((message) => message.text), [
        "authoritative question",
        "authoritative answer"
      ]);
      cleanup();
      rmSync(dir, { recursive: true, force: true });
    });
  });

  describe("session list and catalog", () => {
    it("follows pagination and merges ACP metadata", async () => {
      const { storagePath, cleanup } = tmpStorage();
      let listCalls = 0;
      const controls = createConfigurableDevinProcess({
        handlers: {
          sessionList: (params) => {
            listCalls += 1;
            if (!params.cursor) {
              return {
                sessions: [{ sessionId: "page-1", cwd: "/tmp/a", title: "A", updatedAt: new Date(1000).toISOString() }],
                nextCursor: "c2"
              };
            }
            return {
              sessions: [{ sessionId: "page-2", cwd: "/tmp/b", title: "B", updatedAt: new Date(2000).toISOString() }]
            };
          }
        }
      });
      const adapter = buildAdapter(controls, storagePath);
      const list = await adapter.listSessions(10);
      assert.equal(listCalls, 2);
      assert.equal(list.sessions.length, 2);
      assert.equal(list.sessions[0]?.sessionId, "page-2");
      assert.equal(list.sessions[0]?.workspaceName, "b");
      cleanup();
    });

    it("returns local summaries when list capability is unavailable", async () => {
      const { storagePath, cleanup } = tmpStorage();
      const capsWithoutList = {
        ...devinCapabilities(),
        agentCapabilities: { loadSession: true, sessionCapabilities: {}, promptCapabilities: {} }
      };
      const controls = createConfigurableDevinProcess({
        capabilities: capsWithoutList,
        handlers: {
          sessionNew: () => ({ sessionId: "local-only", configOptions: [] })
        }
      });
      const adapter = buildAdapter(controls, storagePath);
      await adapter.createSession({});
      const list = await adapter.listSessions(10);
      assert.equal(list.sessions.length, 1);
      assert.equal(list.sessions[0]?.key, "devin:local-only");
      cleanup();
    });

    it("merges remote sessions into local store", async () => {
      const { storagePath, cleanup } = tmpStorage();
      const controls = createConfigurableDevinProcess({
        handlers: {
          sessionList: () => ({
            sessions: [{ sessionId: "remote-1", cwd: "/tmp/remote", title: "Remote", updatedAt: new Date(5000).toISOString() }]
          })
        }
      });
      const adapter = buildAdapter(controls, storagePath);
      await adapter.listSessions(10);
      const persisted = JSON.parse(readFileSync(storagePath, "utf8")) as { sessions: Array<{ key: string; metadata: Record<string, unknown> }> };
      assert.ok(persisted.sessions.some((s) => s.key === "devin:remote-1" && s.metadata.workspacePath === "/tmp/remote"));
      cleanup();
    });

    it("uses remote title cwd and time while retaining local model fallback", async () => {
      const { storagePath, cleanup } = tmpStorage();
      const localDir = mkdtempSync(join(tmpdir(), "devin-local-merge-"));
      const remoteDir = mkdtempSync(join(tmpdir(), "devin-remote-merge-"));
      const remoteUpdatedAt = new Date("2026-07-10T12:34:56.000Z");
      const controls = createConfigurableDevinProcess({
        handlers: {
          sessionNew: () => ({ sessionId: "merge-1", configOptions: defaultModelOptions() }),
          sessionList: () => ({
            sessions: [{
              sessionId: "merge-1",
              cwd: remoteDir,
              title: "Remote authority",
              updatedAt: remoteUpdatedAt.toISOString()
            }]
          })
        }
      });
      const adapter = buildAdapter(controls, storagePath);
      await adapter.createSession({ workspacePath: localDir, model: "claude-sonnet" });
      const list = await adapter.listSessions(10);
      assert.equal(list.sessions.length, 1);
      assert.equal(list.sessions[0]?.label, "Remote authority");
      assert.equal(list.sessions[0]?.workspacePath, remoteDir);
      assert.equal(list.sessions[0]?.updatedAt, remoteUpdatedAt.getTime());
      assert.equal(list.sessions[0]?.model, "claude-sonnet");
      cleanup();
      rmSync(localDir, { recursive: true, force: true });
      rmSync(remoteDir, { recursive: true, force: true });
    });
  });

  describe("model discovery", () => {
    it("probes session/new to discover models and closes probe", async () => {
      const { storagePath, cleanup } = tmpStorage();
      let newCount = 0;
      let closeCount = 0;
      const controls = createConfigurableDevinProcess({
        handlers: {
          sessionNew: () => {
            newCount += 1;
            return { sessionId: "probe-1", configOptions: defaultModelOptions() };
          },
          sessionClose: () => {
            closeCount += 1;
          }
        }
      });
      const adapter = buildAdapter(controls, storagePath);
      const models = await adapter.listModels();
      assert.equal(models.length, 2);
      assert.equal(models[0]?.id, "default");
      assert.equal(models[1]?.id, "claude-sonnet");
      assert.equal(newCount, 1);
      assert.equal(closeCount, 1);
      cleanup();
    });

    it("caches model discovery per transport generation", async () => {
      const { storagePath, cleanup } = tmpStorage();
      let newCount = 0;
      const controls: FakeControls[] = [];
      const factory = {
        create: async () => {
          const c = createConfigurableDevinProcess({
            handlers: {
              sessionNew: () => {
                newCount += 1;
                return { sessionId: `probe-${newCount}`, configOptions: defaultModelOptions() };
              }
            }
          });
          controls.push(c);
          return c.process;
        }
      };
      const adapter = new DevinSessionAdapter({
        command: "devin acp",
        cwd: "/test",
        storagePath,
        startupTimeoutMs: LONG_TIMEOUT_MS,
        requestTimeoutMs: LONG_TIMEOUT_MS,
        processFactory: factory
      });
      await adapter.listModels();
      await adapter.listModels();
      assert.equal(newCount, 1);
      controls[0]!.exit(1);
      await new Promise((resolve) => setTimeout(resolve, 50));
      await adapter.listModels();
      assert.equal(newCount, 2);
      cleanup();
    });

    it("publishes only an honest devin default when no model config is advertised", async () => {
      const { storagePath, cleanup } = tmpStorage();
      const controls = createConfigurableDevinProcess({
        handlers: {
          sessionNew: () => ({ sessionId: "probe-default", configOptions: [] })
        }
      });
      const adapter = buildAdapter(controls, storagePath);
      const models = await adapter.listModels();
      assert.deepEqual(models.map((model) => [model.id, model.label, model.modelId]), [
        ["default", "Devin default", "default"]
      ]);
      cleanup();
    });

    it("does not call optional session/close when it is not advertised", async () => {
      const { storagePath, cleanup } = tmpStorage();
      let closeCount = 0;
      const capsWithoutClose = {
        ...devinCapabilities(),
        agentCapabilities: {
          loadSession: true,
          sessionCapabilities: { list: {}, additionalDirectories: {} },
          promptCapabilities: {}
        }
      };
      const controls = createConfigurableDevinProcess({
        capabilities: capsWithoutClose,
        handlers: {
          sessionNew: () => ({ sessionId: "probe-no-close", configOptions: [] }),
          sessionClose: () => {
            closeCount += 1;
          }
        }
      });
      const adapter = buildAdapter(controls, storagePath);
      await adapter.listModels();
      assert.equal(closeCount, 0);
      cleanup();
    });
  });

  describe("advertised commands and capabilities", () => {
    it("captures available commands from session setup and keeps tools empty", async () => {
      const { storagePath, cleanup } = tmpStorage();
      let controls: FakeControls;
      controls = createConfigurableDevinProcess({
        handlers: {
          sessionNew: () => {
            controls.pushReplay?.([availableCommandsUpdate("commands-1")]);
            return { sessionId: "commands-1", configOptions: defaultModelOptions() };
          }
        }
      });
      const adapter = buildAdapter(controls, storagePath);
      await adapter.createSession({});
      const commands = await adapter.listCommands("devin:commands-1");
      assert.deepEqual(commands, [{
        name: "review",
        description: "Review the workspace",
        source: "devin",
        acceptsArgs: true,
        args: [{
          name: "input",
          description: "Optional focus",
          type: "string",
          required: true
        }]
      }]);
      assert.deepEqual(await adapter.effectiveTools("devin:commands-1"), []);
      assert.equal(adapter.capabilities.supportsAttachments, false);
      cleanup();
    });
  });

  describe("patchSession", () => {
    it("updates model and reasoning via ACP set_config_option when attached", async () => {
      const { storagePath, cleanup } = tmpStorage();
      const setCalls: Array<{ configId: string; value: string }> = [];
      const controls = createConfigurableDevinProcess({
        handlers: {
          sessionNew: () => ({ sessionId: "patch-1", configOptions: defaultModelOptions() }),
          sessionSetConfigOption: (params) => {
            setCalls.push({ configId: params.configId, value: params.value as string });
            return {
              configOptions: [
                {
                  id: "model",
                  name: "Model",
                  type: "select",
                  category: "model",
                  currentValue: params.value as string,
                  options: (defaultModelOptions()[0] as { options: SessionConfigSelectOptions }).options
                },
                {
                  id: "thought",
                  name: "Thought level",
                  type: "select",
                  category: "thought_level",
                  currentValue: "deep",
                  options: (defaultModelOptions()[1] as { options: SessionConfigSelectOptions }).options
                }
              ]
            };
          }
        }
      });
      const adapter = buildAdapter(controls, storagePath);
      await adapter.createSession({});
      await adapter.patchSession("devin:patch-1", { model: "claude-sonnet", thinking: "deep" });
      assert.equal(setCalls.length, 2);
      assert.deepEqual(setCalls[0], { configId: "model", value: "claude-sonnet" });
      assert.deepEqual(setCalls[1], { configId: "thought", value: "deep" });
      cleanup();
    });

    it("rejects unknown model values", async () => {
      const { storagePath, cleanup } = tmpStorage();
      const controls = createConfigurableDevinProcess({
        handlers: {
          sessionNew: () => ({ sessionId: "patch-bad", configOptions: defaultModelOptions() })
        }
      });
      const adapter = buildAdapter(controls, storagePath);
      await adapter.createSession({});
      await assert.rejects(
        adapter.patchSession("devin:patch-bad", { model: "unknown" }),
        ChatClientError
      );
      cleanup();
    });
  });

  describe("Wave 4 boundary", () => {
    it("sendChat throws instead of pretending to work", async () => {
      const { storagePath, cleanup } = tmpStorage();
      const controls = createConfigurableDevinProcess();
      const adapter = buildAdapter(controls, storagePath);
      await assert.rejects(
        adapter.sendChat({ sessionKey: "devin:x", message: "hi" }),
        /Wave 4/
      );
      cleanup();
    });

    it("abort throws instead of implementing cancellation", async () => {
      const { storagePath, cleanup } = tmpStorage();
      const controls = createConfigurableDevinProcess();
      const adapter = buildAdapter(controls, storagePath);
      await assert.rejects(adapter.abort("devin:x"), /Wave 4/);
      cleanup();
    });

    it("effective tools are empty", async () => {
      const { storagePath, cleanup } = tmpStorage();
      const controls = createConfigurableDevinProcess();
      const adapter = buildAdapter(controls, storagePath);
      const tools = await adapter.effectiveTools("devin:x");
      assert.equal(tools.length, 0);
      cleanup();
    });
  });

  describe("close and health", () => {
    it("health delegates to transport health", async () => {
      const { storagePath, cleanup } = tmpStorage();
      const controls = createConfigurableDevinProcess({
        handlers: {
          sessionNew: () => ({ sessionId: "health-1", configOptions: defaultModelOptions() })
        }
      });
      const adapter = buildAdapter(controls, storagePath);
      await adapter.createSession({});
      const health = await adapter.health();
      assert.equal((health as { state: string }).state, "ready");
      cleanup();
    });

    it("close terminates transport", async () => {
      const { storagePath, cleanup } = tmpStorage();
      const controls = createConfigurableDevinProcess({
        handlers: {
          sessionNew: () => ({ sessionId: "health-close", configOptions: defaultModelOptions() })
        }
      });
      const adapter = buildAdapter(controls, storagePath);
      await adapter.createSession({});
      adapter.close();
      await new Promise((resolve) => setTimeout(resolve, 50));
      const health = await adapter.health();
      assert.equal((health as { state: string }).state, "stopped");
      cleanup();
    });
  });
});
