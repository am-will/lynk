import { tmpdir } from "node:os";
import { join } from "node:path";
import { agent, methods, PROTOCOL_VERSION, ndJsonStream, RequestError } from "@agentclientprotocol/sdk";

import type {
  AgentConnection,
  AuthMethod,
  CancelNotification,
  CloseSessionRequest,
  InitializeRequest,
  InitializeResponse,
  ListSessionsRequest,
  ListSessionsResponse,
  LoadSessionRequest,
  LoadSessionResponse,
  NewSessionRequest,
  NewSessionResponse,
  PromptRequest,
  PromptResponse,
  RequestPermissionRequest,
  SessionNotification,
  SetSessionConfigOptionRequest,
  SetSessionConfigOptionResponse
} from "@agentclientprotocol/sdk";
import { DevinAcpClient } from "./DevinAcpClient.js";
import { DevinAcpError, type DevinAcpEvent, type DevinAcpProcess, type DevinAcpProcessExit, type DevinAcpProcessFactory } from "./DevinAcpTypes.js";

export const LONG_TIMEOUT_MS = 30_000;

export function devinCapabilities(): InitializeResponse {
  return {
    protocolVersion: PROTOCOL_VERSION,
    agentInfo: { name: "Devin", version: "3000.1.27", title: "Devin" },
    agentCapabilities: {
      loadSession: true,
      sessionCapabilities: {
        list: {},
        additionalDirectories: {},
        close: {}
      },
      promptCapabilities: {}
    },
    authMethods: [{ id: "devin", name: "Devin" }] as AuthMethod[]
  };
}

export function baselineCapabilities(): InitializeResponse {
  return {
    protocolVersion: PROTOCOL_VERSION,
    agentInfo: { name: "Devin", version: "3000.1.27", title: "Devin" },
    agentCapabilities: {
      loadSession: false,
      sessionCapabilities: {},
      promptCapabilities: {}
    },
    authMethods: []
  };
}

export interface FakeControls {
  process: DevinAcpProcess;
  agent: AgentConnection;
  pushStderr: (text: string) => void;
  pushStderrRaw: (text: string) => void;
  pushReplay?: (notifications: SessionNotification[]) => void;
  exit: (code: number | null, signal?: NodeJS.Signals | null) => void;
}

export function createFakeDevinProcess(
  options: {
    capabilities?: InitializeResponse;
    delayMs?: number;
    initializeError?: Error;
    hangList?: boolean;
    ignoreSigterm?: boolean;
  } = {}
): FakeControls {
  const capabilities = options.capabilities ?? devinCapabilities();
  const abort = new AbortController();
  const clientToAgent = new TransformStream<Uint8Array>();
  const agentToClient = new TransformStream<Uint8Array>();
  let stderrController: ReadableStreamDefaultController<Uint8Array>;
  let resolveExit!: (value: DevinAcpProcessExit) => void;
  let settled = false;

  const stderrStream = new ReadableStream<Uint8Array>({
    start(controller) {
      stderrController = controller;
    }
  });

  const exited = new Promise<DevinAcpProcessExit>((resolve) => {
    resolveExit = resolve;
  });

  const agentApp = agent({ name: "fake-devin" })
    .onRequest(methods.agent.initialize, async () => {
      await delay(options.delayMs ?? 0, abort.signal);
      if (abort.signal.aborted) {
        return capabilities;
      }
      if (options.initializeError) {
        throw options.initializeError;
      }
      return capabilities;
    })
    .onRequest(methods.agent.session.new, async () => ({ sessionId: "test-session-1" }))
    .onRequest(methods.agent.session.list, async () => {
      if (options.hangList) {
        return new Promise<ListSessionsResponse>(() => {});
      }
      return { sessions: [] };
    })
    .onRequest(methods.agent.session.load, async () => ({}))
    .onRequest(methods.agent.session.close, async () => ({}))
    .onRequest(methods.agent.session.setConfigOption, async () => ({ configOptions: [] }))
    .onRequest(methods.agent.session.prompt, async () => ({ stopReason: "end_turn" }));

  const connection = agentApp.connect(ndJsonStream(agentToClient.writable, clientToAgent.readable));

  const finish = (code: number | null, signal?: NodeJS.Signals | null): void => {
    if (settled) {
      return;
    }
    settled = true;
    try {
      abort.abort();
    } catch {
      // ignore
    }
    try {
      stderrController?.close();
    } catch {
      // ignore
    }
    try {
      agentToClient.writable.getWriter().close().catch(() => {});
    } catch {
      // ignore
    }
    try {
      connection.close();
    } catch {
      // ignore
    }
    resolveExit({ code, signal: signal ?? null });
  };

  const process: DevinAcpProcess = {
    command: "devin acp",
    executable: "/fake/devin",
    args: ["acp"],
    cwd: "/fake",
    stdin: clientToAgent.writable,
    stdout: agentToClient.readable,
    stderr: stderrStream,
    exited,
    kill(signal?) {
      if (options.ignoreSigterm && signal === "SIGTERM") {
        return;
      }
      finish(null, signal ?? null);
    }
  };

  const pushStderrRaw = (text: string): void => {
    try {
      stderrController?.enqueue(new TextEncoder().encode(text));
    } catch {
      // ignore
    }
  };

  return {
    process,
    agent: connection,
    pushStderr: (text: string) => pushStderrRaw(text + "\n"),
    pushStderrRaw,
    exit: finish
  };
}

export function createAuthRequiringProcess(): FakeControls {
  const clientToAgent = new TransformStream<Uint8Array>();
  const agentToClient = new TransformStream<Uint8Array>();
  let stderrController: ReadableStreamDefaultController<Uint8Array>;
  let resolveExit!: (value: DevinAcpProcessExit) => void;
  let settled = false;

  const stderrStream = new ReadableStream<Uint8Array>({
    start(controller) {
      stderrController = controller;
    }
  });

  const exited = new Promise<DevinAcpProcessExit>((resolve) => {
    resolveExit = resolve;
  });

  const agentApp = agent({ name: "fake-devin-auth" }).onRequest(methods.agent.initialize, () => {
    throw RequestError.authRequired("Authentication required.");
  });

  const connection = agentApp.connect(ndJsonStream(agentToClient.writable, clientToAgent.readable));

  const finish = (code: number | null, signal?: NodeJS.Signals | null): void => {
    if (settled) {
      return;
    }
    settled = true;
    try {
      stderrController?.close();
    } catch {
      // ignore
    }
    try {
      agentToClient.writable.getWriter().close().catch(() => {});
    } catch {
      // ignore
    }
    try {
      connection.close();
    } catch {
      // ignore
    }
    resolveExit({ code, signal: signal ?? null });
  };

  const process: DevinAcpProcess = {
    command: "devin acp",
    executable: "/fake/devin",
    args: ["acp"],
    cwd: "/fake",
    stdin: clientToAgent.writable,
    stdout: agentToClient.readable,
    stderr: stderrStream,
    exited,
    kill(signal?) {
      finish(null, signal ?? null);
    }
  };

  const pushStderrRaw = (): void => {};

  return {
    process,
    agent: connection,
    pushStderr: () => {},
    pushStderrRaw,
    exit: finish
  };
}

export function createInterceptedProcess(handler: (req: InitializeRequest) => InitializeResponse): FakeControls {
  const clientToAgent = new TransformStream<Uint8Array>();
  const agentToClient = new TransformStream<Uint8Array>();
  let stderrController: ReadableStreamDefaultController<Uint8Array>;
  let resolveExit!: (value: DevinAcpProcessExit) => void;
  let settled = false;

  const stderrStream = new ReadableStream<Uint8Array>({
    start(controller) {
      stderrController = controller;
    }
  });

  const exited = new Promise<DevinAcpProcessExit>((resolve) => {
    resolveExit = resolve;
  });

  const agentApp = agent({ name: "fake-devin-intercept" }).onRequest(methods.agent.initialize, (ctx) => {
    return handler(ctx.params as InitializeRequest);
  });

  const connection = agentApp.connect(ndJsonStream(agentToClient.writable, clientToAgent.readable));

  const finish = (code: number | null, signal?: NodeJS.Signals | null): void => {
    if (settled) {
      return;
    }
    settled = true;
    try {
      stderrController?.close();
    } catch {
      // ignore
    }
    try {
      agentToClient.writable.getWriter().close().catch(() => {});
    } catch {
      // ignore
    }
    try {
      connection.close();
    } catch {
      // ignore
    }
    resolveExit({ code, signal: signal ?? null });
  };

  const process: DevinAcpProcess = {
    command: "devin acp",
    executable: "/fake/devin",
    args: ["acp"],
    cwd: "/fake",
    stdin: clientToAgent.writable,
    stdout: agentToClient.readable,
    stderr: stderrStream,
    exited,
    kill(signal?) {
      finish(null, signal ?? null);
    }
  };

  const pushStderrRaw = (): void => {};

  return {
    process,
    agent: connection,
    pushStderr: () => {},
    pushStderrRaw,
    exit: finish
  };
}

export function createSpawnFailingProcess(message = "spawn failed"): DevinAcpProcess {
  const clientToAgent = new TransformStream<Uint8Array>();
  const agentToClient = new TransformStream<Uint8Array>();
  const stderrStream = new ReadableStream<Uint8Array>({ start() {} });

  const exited = new Promise<DevinAcpProcessExit>((resolve) => {
    setTimeout(() => {
      resolve({ code: null, signal: null, spawnError: new Error(message) });
    }, 10);
  });

  return {
    command: "devin acp",
    executable: "/fake/devin",
    args: ["acp"],
    cwd: "/fake",
    stdin: clientToAgent.writable,
    stdout: agentToClient.readable,
    stderr: stderrStream,
    exited,
    kill() {}
  };
}

export function delay(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, ms);
    if (signal) {
      signal.addEventListener(
        "abort",
        () => {
          clearTimeout(timer);
          resolve();
        },
        { once: true }
      );
    }
  });
}

export function buildClient(
  overrides: {
    process?: DevinAcpProcess;
    processFactory?: DevinAcpProcessFactory;
    startupTimeoutMs?: number;
    teardownGraceMs?: number;
    onEvent?: (event: DevinAcpEvent) => void;
  } = {}
): DevinAcpClient {
  let processFactory = overrides.processFactory;
  if (!processFactory && overrides.process) {
    processFactory = { create: async () => overrides.process! };
  }
  return new DevinAcpClient({
    command: "devin acp",
    cwd: "/test",
    startupTimeoutMs: overrides.startupTimeoutMs ?? LONG_TIMEOUT_MS,
    requestTimeoutMs: LONG_TIMEOUT_MS,
    teardownGraceMs: overrides.teardownGraceMs,
    processFactory,
    onEvent: overrides.onEvent
  });
}

export function processGlobal(): NodeJS.Process {
  return process;
}

export interface DevinSessionHandlers {
  sessionNew?: (params: NewSessionRequest) => NewSessionResponse | Promise<NewSessionResponse>;
  sessionList?: (params: ListSessionsRequest) => ListSessionsResponse | Promise<ListSessionsResponse>;
  sessionLoad?: (params: LoadSessionRequest) => LoadSessionResponse | Promise<LoadSessionResponse>;
  sessionSetConfigOption?: (
    params: SetSessionConfigOptionRequest
  ) => SetSessionConfigOptionResponse | Promise<SetSessionConfigOptionResponse>;
  sessionClose?: (params: CloseSessionRequest) => void | Promise<void>;
  sessionPrompt?: (params: PromptRequest) => PromptResponse | Promise<PromptResponse>;
  sessionCancel?: (params: CancelNotification) => void | Promise<void>;
}

export function createConfigurableDevinProcess(
  options: {
    capabilities?: InitializeResponse;
    delayMs?: number;
    initializeError?: Error;
    handlers?: DevinSessionHandlers;
  } = {}
): FakeControls {
  const capabilities = options.capabilities ?? devinCapabilities();
  const abort = new AbortController();
  const clientToAgent = new TransformStream<Uint8Array>();
  const agentToClient = new TransformStream<Uint8Array>();
  let stderrController: ReadableStreamDefaultController<Uint8Array>;
  let resolveExit!: (value: DevinAcpProcessExit) => void;
  let settled = false;

  const stderrStream = new ReadableStream<Uint8Array>({
    start(controller) {
      stderrController = controller;
    }
  });

  const exited = new Promise<DevinAcpProcessExit>((resolve) => {
    resolveExit = resolve;
  });

  const agentAppBuilder = agent({ name: "fake-devin-session" })
    .onRequest(methods.agent.initialize, async () => {
      await delay(options.delayMs ?? 0, abort.signal);
      if (abort.signal.aborted) {
        return capabilities;
      }
      if (options.initializeError) {
        throw options.initializeError;
      }
      return capabilities;
    })
    .onRequest(methods.agent.session.new, async (ctx) => {
      const handler = options.handlers?.sessionNew;
      return handler ? await handler(ctx.params as NewSessionRequest) : { sessionId: "test-session-1" };
    })
    .onRequest(methods.agent.session.list, async (ctx) => {
      const handler = options.handlers?.sessionList;
      return handler ? await handler(ctx.params as ListSessionsRequest) : { sessions: [] };
    })
    .onRequest(methods.agent.session.load, async (ctx) => {
      const handler = options.handlers?.sessionLoad;
      return handler ? await handler(ctx.params as LoadSessionRequest) : {};
    })
    .onRequest(methods.agent.session.setConfigOption, async (ctx) => {
      const handler = options.handlers?.sessionSetConfigOption;
      return handler
        ? await handler(ctx.params as SetSessionConfigOptionRequest)
        : { configOptions: [] };
    })
    .onRequest(methods.agent.session.close, async (ctx) => {
      const handler = options.handlers?.sessionClose;
      if (handler) {
        await handler(ctx.params as CloseSessionRequest);
      }
    })
    .onRequest(methods.agent.session.prompt, async (ctx) => {
      const handler = options.handlers?.sessionPrompt;
      return handler ? await handler(ctx.params as PromptRequest) : { stopReason: "end_turn" };
    })
    .onNotification(methods.agent.session.cancel, async (ctx) => {
      await options.handlers?.sessionCancel?.(ctx.params as CancelNotification);
    });

  const agentApp = agentAppBuilder.connect(ndJsonStream(agentToClient.writable, clientToAgent.readable));

  const finish = (code: number | null, signal?: NodeJS.Signals | null): void => {
    if (settled) {
      return;
    }
    settled = true;
    try {
      abort.abort();
    } catch {
      // ignore
    }
    try {
      stderrController?.close();
    } catch {
      // ignore
    }
    try {
      agentToClient.writable.getWriter().close().catch(() => {});
    } catch {
      // ignore
    }
    try {
      agentApp.close();
    } catch {
      // ignore
    }
    resolveExit({ code, signal: signal ?? null });
  };

  const process: DevinAcpProcess = {
    command: "devin acp",
    executable: "/fake/devin",
    args: ["acp"],
    cwd: "/fake",
    stdin: clientToAgent.writable,
    stdout: agentToClient.readable,
    stderr: stderrStream,
    exited,
    kill(signal?) {
      finish(null, signal ?? null);
    }
  };

  const pushStderrRaw = (text: string): void => {
    try {
      stderrController?.enqueue(new TextEncoder().encode(text));
    } catch {
      // ignore
    }
  };

  const pushReplay = (notifications: SessionNotification[]): void => {
    for (const notification of notifications) {
      try {
        agentApp.client.notify(methods.client.session.update, notification);
      } catch {
        // ignore
      }
    }
  };

  return {
    process,
    agent: agentApp,
    pushStderr: (text: string) => pushStderrRaw(text + "\n"),
    pushStderrRaw,
    pushReplay,
    exit: finish
  };
}

export { RequestError, SessionNotification, RequestPermissionRequest };
