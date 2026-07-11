import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { methods, PROTOCOL_VERSION, RequestError } from "@agentclientprotocol/sdk";
import type { InitializeRequest, InitializeResponse, ListSessionsRequest } from "@agentclientprotocol/sdk";
import { DevinAcpError, type DevinAcpEvent } from "./DevinAcpTypes.js";
import { DevinAcpClient } from "./DevinAcpClient.js";
import {
  baselineCapabilities,
  buildClient,
  createAuthRequiringProcess,
  createFakeDevinProcess,
  createInterceptedProcess,
  createSpawnFailingProcess,
  devinCapabilities,
  LONG_TIMEOUT_MS,
  RequestError as FixtureRequestError,
  RequestPermissionRequest,
  SessionNotification
} from "./DevinAcpFixtures.js";

describe("DevinAcpClient", () => {
  describe("initialization and capabilities", () => {
    it("sends the expected initialize request and captures capabilities", async () => {
      const { process } = createFakeDevinProcess();
      const client = buildClient({ process });
      const caps = await client.ensureStarted();

      assert.equal(caps.protocolVersion, PROTOCOL_VERSION);
      assert.equal(caps.agentName, "Devin");
      assert.equal(caps.agentVersion, "3000.1.27");
      assert.equal(caps.loadSession, true);
      assert.equal(caps.listSessions, true);
      assert.equal(caps.additionalDirectories, true);
      assert.equal(caps.closeSession, true);
      assert.equal(caps.resumeSession, false);
      assert.equal(caps.promptImage, false);
      assert.equal(caps.authLogout, false);
      assert.equal(caps.authMethods.length, 1);
      assert.equal(caps.authMethods[0]?.id, "devin");
      assert.equal(caps.authMethods[0]?.name, "Devin");
    });

    it("advertises Lynk client info and minimal client capabilities", async () => {
      let captured: InitializeRequest | undefined;
      const { process: interceptProcess, agent: interceptAgent } = createInterceptedProcess((req) => {
        captured = req;
        return devinCapabilities();
      });
      const interceptClient = buildClient({ process: interceptProcess });
      await interceptClient.ensureStarted();

      assert.ok(captured);
      assert.equal(captured?.protocolVersion, PROTOCOL_VERSION);
      assert.equal(captured?.clientInfo?.name, "lynk-bridge");
      assert.equal(captured?.clientInfo?.title, "Lynk");
      assert.equal(typeof captured?.clientInfo?.version, "string");
      assert.equal(captured?.clientCapabilities?.fs?.readTextFile, false);
      assert.equal(captured?.clientCapabilities?.fs?.writeTextFile, false);
      assert.equal(captured?.clientCapabilities?.terminal, false);

      await interceptClient.close();
      void interceptAgent;
    });

    it("rejects with protocol_mismatch when agent returns a different protocol version", async () => {
      const { process } = createFakeDevinProcess({
        capabilities: { ...devinCapabilities(), protocolVersion: 999 }
      });
      const client = buildClient({ process });
      await assert.rejects(client.ensureStarted(), (error) => {
        return error instanceof DevinAcpError && error.code === "protocol_mismatch";
      });
    });

    it("classifies auth-required errors from initialize", async () => {
      const { process, agent } = createAuthRequiringProcess();
      const client = buildClient({ process });
      await assert.rejects(client.ensureStarted(), (error) => {
        return error instanceof DevinAcpError && error.code === "auth_required";
      });
      void agent;
    });

    it("times out when initialization does not complete in time", async () => {
      const { process } = createFakeDevinProcess({ delayMs: 100_000 });
      const client = buildClient({ process, startupTimeoutMs: 50 });
      await assert.rejects(client.ensureStarted(), (error) => {
        return error instanceof DevinAcpError && error.code === "startup_timeout";
      });
    });

    it("classifies malformed_transport when initialize returns a parse error", async () => {
      const { process, agent } = createFakeDevinProcess({
        initializeError: FixtureRequestError.parseError("bad initialize")
      });
      const client = buildClient({ process, startupTimeoutMs: 1000 });
      await assert.rejects(client.ensureStarted(), (error) => {
        return error instanceof DevinAcpError && error.code === "malformed_transport";
      });
      void agent;
    });

    it("rejects active request when process exits unexpectedly", async () => {
      const { process, agent, exit } = createFakeDevinProcess({ hangList: true });
      const client = buildClient({ process });
      await client.ensureStarted();

      const requestPromise = client.sessionList();
      await new Promise((resolve) => setTimeout(resolve, 50));
      exit(1);

      await assert.rejects(requestPromise, (error) => {
        return error instanceof DevinAcpError && error.code === "unexpected_exit";
      });
      void agent;
    });

    it("shares a single startup promise across concurrent ensureStarted calls", async () => {
      let creates = 0;
      let initializeCalls = 0;
      const factory = {
        create: async () => {
          creates += 1;
          return createInterceptedProcess((req) => {
            initializeCalls += 1;
            assert.equal(req.protocolVersion, PROTOCOL_VERSION);
            return devinCapabilities();
          }).process;
        }
      };
      const client = new DevinAcpClient({
        command: "devin acp",
        cwd: "/test",
        startupTimeoutMs: LONG_TIMEOUT_MS,
        requestTimeoutMs: LONG_TIMEOUT_MS,
        processFactory: factory
      });
      const [a, b] = await Promise.all([client.ensureStarted(), client.ensureStarted()]);
      assert.equal(creates, 1);
      assert.equal(initializeCalls, 1);
      assert.equal(a.agentName, b.agentName);
    });

    it("lets an injected process factory run without a host Devin executable", async () => {
      const { process } = createFakeDevinProcess();
      const client = new DevinAcpClient({
        command: "definitely-not-installed-devin acp",
        cwd: "/test",
        startupTimeoutMs: LONG_TIMEOUT_MS,
        requestTimeoutMs: LONG_TIMEOUT_MS,
        processFactory: { create: async () => process }
      });

      const caps = await client.ensureStarted();
      assert.equal(caps.agentName, "Devin");
      await client.close();
    });

    it("recovers from unexpected exit with a new process on ensureStarted", async () => {
      const processes: ReturnType<typeof createFakeDevinProcess>[] = [];
      let creates = 0;
      const factory = {
        create: async () => {
          creates += 1;
          const controls = createFakeDevinProcess();
          processes.push(controls);
          return controls.process;
        }
      };
      const client = new DevinAcpClient({
        command: "devin acp",
        cwd: "/test",
        startupTimeoutMs: LONG_TIMEOUT_MS,
        requestTimeoutMs: LONG_TIMEOUT_MS,
        processFactory: factory
      });

      await client.ensureStarted();
      assert.equal(creates, 1);
      assert.equal(client.currentState, "ready");

      processes[0]!.exit(1);
      await new Promise((resolve) => setTimeout(resolve, 50));
      assert.equal(client.currentState, "failed");

      await client.ensureStarted();
      assert.equal(creates, 2);
      assert.equal(client.currentState, "ready");

      const listResult = await client.sessionList();
      assert.ok(Array.isArray(listResult.sessions));
    });

    it("classifies injected spawn errors as spawn_failure", async () => {
      let creates = 0;
      const factory = {
        create: async () => {
          creates += 1;
          return createSpawnFailingProcess("injected spawn error");
        }
      };
      const client = new DevinAcpClient({
        command: "devin acp",
        cwd: "/test",
        startupTimeoutMs: LONG_TIMEOUT_MS,
        requestTimeoutMs: LONG_TIMEOUT_MS,
        processFactory: factory
      });
      await assert.rejects(client.ensureStarted(), (error) => {
        return error instanceof DevinAcpError && error.code === "spawn_failure";
      });
      assert.equal(creates, 1);
    });
  });

  describe("secret-safe error messages", () => {
    it("sanitizes initialize errors containing credentials", async () => {
      const secret = "super-secret-value";
      const { process } = createFakeDevinProcess({
        initializeError: FixtureRequestError.parseError(undefined, `initialize failed with token=${secret}`)
      });
      const client = buildClient({ process });

      const lifecycleErrors: DevinAcpEvent[] = [];
      client.addEventListener((event) => {
        if (event.type === "lifecycle" && event.error) {
          lifecycleErrors.push(event);
        }
      });

      let thrown: unknown;
      try {
        await client.ensureStarted();
      } catch (error) {
        thrown = error;
      }

      assert.ok(thrown instanceof DevinAcpError);
      assert.ok(!(thrown as DevinAcpError).message.includes(secret));
      assert.ok(
        (thrown as DevinAcpError).message.includes("[redacted]"),
        (thrown as DevinAcpError).message
      );

      assert.equal(lifecycleErrors.length, 1);
      const lifecycleError = (lifecycleErrors[0] as { error: { message: string } }).error;
      assert.ok(!lifecycleError.message.includes(secret));

      const health = client.health();
      assert.ok(health.error);
      assert.ok(!health.error!.message.includes(secret));
    });

    it("sanitizes spawn errors containing credentials", async () => {
      const secret = "super-secret-value";
      let creates = 0;
      const factory = {
        create: async () => {
          creates += 1;
          return createSpawnFailingProcess(`spawn failed: token=${secret}`);
        }
      };
      const client = new DevinAcpClient({
        command: "devin acp",
        cwd: "/test",
        startupTimeoutMs: LONG_TIMEOUT_MS,
        requestTimeoutMs: LONG_TIMEOUT_MS,
        processFactory: factory
      });

      const lifecycleErrors: DevinAcpEvent[] = [];
      client.addEventListener((event) => {
        if (event.type === "lifecycle" && event.error) {
          lifecycleErrors.push(event);
        }
      });

      let thrown: unknown;
      try {
        await client.ensureStarted();
      } catch (error) {
        thrown = error;
      }

      assert.ok(thrown instanceof DevinAcpError);
      assert.equal((thrown as DevinAcpError).code, "spawn_failure");
      assert.ok(!(thrown as DevinAcpError).message.includes(secret));
      assert.ok(
        (thrown as DevinAcpError).message.includes("[redacted]"),
        (thrown as DevinAcpError).message
      );

      assert.equal(lifecycleErrors.length, 1);
      const lifecycleError = (lifecycleErrors[0] as { error: { message: string } }).error;
      assert.ok(!lifecycleError.message.includes(secret));

      const health = client.health();
      assert.ok(health.error);
      assert.ok(!health.error!.message.includes(secret));
    });

  });

  describe("session operations", () => {
    it("forwards session/update notifications to listeners", async () => {
      const { process, agent } = createFakeDevinProcess();
      const client = buildClient({ process });
      await client.ensureStarted();

      const events: DevinAcpEvent[] = [];
      client.addEventListener((event) => events.push(event));

      const notification: SessionNotification = {
        sessionId: "session-1",
        update: { sessionUpdate: "agent_message_chunk", content: { type: "text", text: "hello" } }
      };
      await agent.client.notify(methods.client.session.update, notification);
      await new Promise((resolve) => setTimeout(resolve, 50));

      assert.equal(events.length, 1);
      assert.equal(events[0]?.type, "session/update");
      assert.equal((events[0] as { notification: SessionNotification }).notification.sessionId, "session-1");
    });

    it("returns cancelled permission response when no handler is installed", async () => {
      const { process, agent } = createFakeDevinProcess();
      const client = buildClient({ process });
      await client.ensureStarted();

      const request: RequestPermissionRequest = {
        sessionId: "session-1",
        toolCall: { toolCallId: "tc-1" },
        options: [
          { optionId: "allow", name: "Allow", kind: "allow_once" },
          { optionId: "deny", name: "Deny", kind: "reject_once" }
        ]
      };
      const response = await agent.client.request(methods.client.session.requestPermission, request);
      assert.equal(response.outcome.outcome, "cancelled");
    });

    it("forwards permission requests to the installed handler", async () => {
      const { process, agent } = createFakeDevinProcess();
      const client = buildClient({ process });
      await client.ensureStarted();

      client.setPermissionHandler(async (request) => ({
        outcome: { outcome: "selected", optionId: request.options[0]?.optionId ?? "allow" }
      }));

      const request: RequestPermissionRequest = {
        sessionId: "session-1",
        toolCall: { toolCallId: "tc-1" },
        options: [
          { optionId: "allow", name: "Allow", kind: "allow_once" },
          { optionId: "deny", name: "Deny", kind: "reject_once" }
        ]
      };
      const response = await agent.client.request(methods.client.session.requestPermission, request);
      assert.equal(response.outcome.outcome, "selected");
      assert.equal((response.outcome as { optionId: string }).optionId, "allow");
    });

    it("initializes before checking optional session capabilities", async () => {
      const { process } = createFakeDevinProcess();
      const client = buildClient({ process });

      const result = await client.sessionList();
      assert.ok(Array.isArray(result.sessions));
      assert.equal(client.currentState, "ready");
    });

    it("rejects optional session capabilities when not advertised", async () => {
      const capsWithoutList: InitializeResponse = {
        ...devinCapabilities(),
        agentCapabilities: { loadSession: true, sessionCapabilities: {}, promptCapabilities: {} }
      };
      const { process } = createFakeDevinProcess({ capabilities: capsWithoutList });
      const client = buildClient({ process });

      await assert.rejects(client.sessionList(), (error) => {
        return error instanceof DevinAcpError && error.code === "capability_unavailable";
      });
    });

    it("rejects sessionNew with additionalDirectories when agent does not advertise it", async () => {
      const { process } = createFakeDevinProcess({ capabilities: baselineCapabilities() });
      const client = buildClient({ process });

      await assert.rejects(
        client.sessionNew({ cwd: "/test", additionalDirectories: [], mcpServers: [] }),
        (error) => error instanceof DevinAcpError && error.code === "capability_unavailable"
      );
      await assert.rejects(
        client.sessionNew({ cwd: "/test", additionalDirectories: ["/extra"], mcpServers: [] }),
        (error) => error instanceof DevinAcpError && error.code === "capability_unavailable"
      );
    });

    it("rejects sessionLoad with additionalDirectories when agent does not advertise it", async () => {
      const { process } = createFakeDevinProcess({
        capabilities: { ...baselineCapabilities(), agentCapabilities: { loadSession: true, sessionCapabilities: {}, promptCapabilities: {} } }
      });
      const client = buildClient({ process });

      await assert.rejects(
        client.sessionLoad({ cwd: "/test", sessionId: "s-1", additionalDirectories: [], mcpServers: [] }),
        (error) => error instanceof DevinAcpError && error.code === "capability_unavailable"
      );
      await assert.rejects(
        client.sessionLoad({ cwd: "/test", sessionId: "s-1", additionalDirectories: ["/extra"], mcpServers: [] }),
        (error) => error instanceof DevinAcpError && error.code === "capability_unavailable"
      );
    });

    it("rejects sessionList with additionalDirectories when agent does not advertise it", async () => {
      const capsWithoutAdditional: InitializeResponse = {
        ...devinCapabilities(),
        agentCapabilities: { loadSession: true, sessionCapabilities: { list: {} }, promptCapabilities: {} }
      };
      const { process } = createFakeDevinProcess({ capabilities: capsWithoutAdditional });
      const client = buildClient({ process });

      await assert.rejects(
        client.sessionList({ additionalDirectories: [] as unknown as never } as ListSessionsRequest),
        (error) => error instanceof DevinAcpError && error.code === "capability_unavailable"
      );
    });

    it("allows sessionNew and sessionLoad with additionalDirectories when advertised", async () => {
      const { process, agent } = createFakeDevinProcess({ capabilities: devinCapabilities() });
      const client = buildClient({ process });
      await client.ensureStarted();

      const newResult = await client.sessionNew({ cwd: "/test", additionalDirectories: ["/extra"], mcpServers: [] });
      assert.equal(newResult.sessionId, "test-session-1");

      const loadResult = await client.sessionLoad({
        cwd: "/test",
        sessionId: "s-1",
        additionalDirectories: [],
        mcpServers: []
      });
      assert.equal(typeof loadResult, "object");
      void agent;
    });

    it("performs prompt and new session on baseline capabilities", async () => {
      const { process, agent } = createFakeDevinProcess();
      const client = buildClient({ process });
      await client.ensureStarted();

      const newResult = await client.sessionNew({ cwd: "/test", mcpServers: [] });
      assert.equal(newResult.sessionId, "test-session-1");

      const promptResult = await client.sessionPrompt({
        sessionId: "test-session-1",
        prompt: [{ type: "text", text: "hello" }]
      });
      assert.equal(promptResult.stopReason, "end_turn");
      void agent;
    });

    it("redacts sensitive command arguments in health snapshot", async () => {
      const secretToken = "sk-1234567890abcdef";
      const secretKey = "AKIAIOSFODNN7EXAMPLE";
      const command = `devin acp --token ${secretToken} --api-key=${secretKey} --cwd /test`;
      const { process } = createFakeDevinProcess();
      const client = new DevinAcpClient({
        command,
        cwd: "/test",
        startupTimeoutMs: LONG_TIMEOUT_MS,
        requestTimeoutMs: LONG_TIMEOUT_MS,
        processFactory: { create: async () => process }
      });
      await client.ensureStarted();

      const health = client.health();
      assert.ok(!health.command.includes(secretToken));
      assert.ok(!health.command.includes(secretKey));
      assert.ok(health.command.includes("--token [redacted]"));
      assert.ok(health.command.includes("--api-key [redacted]"));
      assert.ok(health.command.includes("--cwd /test"));
    });

    it("waits for SIGTERM and escalates to SIGKILL when ignored", async () => {
      const controls = createFakeDevinProcess({ ignoreSigterm: true });
      const client = buildClient({ process: controls.process, teardownGraceMs: 100 });
      await client.ensureStarted();

      const closeStart = Date.now();
      await client.close();
      const closeElapsed = Date.now() - closeStart;

      const health = client.health();
      assert.equal(health.state, "stopped");
      assert.notEqual(health.error?.code, "unexpected_exit");
      assert.ok(closeElapsed >= 90, "close should wait for SIGTERM grace before SIGKILL");

      const exit = await controls.process.exited;
      assert.equal(exit.signal, "SIGKILL");
    });
  });
});
