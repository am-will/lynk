import { client, methods, PROTOCOL_VERSION, ndJsonStream } from "@agentclientprotocol/sdk";
import type {
  AgentRequestMethod,
  AgentRequestParamsByMethod,
  AgentRequestResponsesByMethod,
  CancelNotification,
  CloseSessionRequest,
  InitializeRequest,
  ListSessionsRequest,
  ListSessionsResponse,
  LoadSessionRequest,
  LoadSessionResponse,
  NewSessionRequest,
  NewSessionResponse,
  PromptRequest,
  PromptResponse,
  RequestPermissionRequest,
  RequestPermissionResponse,
  SessionNotification,
  SetSessionConfigOptionRequest,
  SetSessionConfigOptionResponse
} from "@agentclientprotocol/sdk";
import type { ClientConnection } from "@agentclientprotocol/sdk";
import { normalizeDevinCapabilities } from "./DevinAcpCapabilities.js";
import { DevinAcpStderrCapture } from "./DevinAcpStderr.js";
import {
  DevinAcpError,
  type DevinAcpCapabilities,
  type DevinAcpClientOptions,
  type DevinAcpEvent,
  type DevinAcpHealth,
  type DevinAcpPermissionHandler,
  type DevinAcpProcess,
  type DevinAcpProcessFactory,
  type DevinAcpState
} from "./DevinAcpTypes.js";
import { createDefaultDevinAcpProcessFactory, resolveDevinAcpCommand } from "./DevinAcpProcess.js";
import {
  classifyAcpError,
  positiveMs,
  readPackageVersion,
  sanitizeDevinCommand,
  sanitizeDiagnosticText,
  sleep,
  withTimeout
} from "./DevinAcpClientSupport.js";

const DEFAULT_STARTUP_TIMEOUT_MS = 30_000;
const DEFAULT_REQUEST_TIMEOUT_MS = 600_000;
const DEFAULT_TEARDOWN_GRACE_MS = 2000;

export class DevinAcpClient {
  private readonly command: string;
  private readonly cwd: string;
  private readonly startupTimeoutMs: number;
  private readonly requestTimeoutMs: number;
  private readonly teardownGraceMs: number;
  private readonly processFactory: DevinAcpProcessFactory;
  private readonly listeners = new Set<(event: DevinAcpEvent) => void>();
  private readonly stderr = new DevinAcpStderrCapture();

  private permissionHandler?: DevinAcpPermissionHandler;
  private state: DevinAcpState = "stopped";
  private capabilities?: DevinAcpCapabilities;
  private connection?: ClientConnection;
  private process?: DevinAcpProcess;
  private startupPromise?: Promise<DevinAcpCapabilities>;
  private teardownPromise?: Promise<void>;
  private closed = false;
  private closing = false;
  private failureError?: DevinAcpError;
  private closeError?: DevinAcpError;
  private version: string;

  constructor(options: DevinAcpClientOptions = {}) {
    this.command = options.command?.trim() || "devin acp";
    this.cwd = options.cwd?.trim() || process.cwd();
    this.startupTimeoutMs = positiveMs(options.startupTimeoutMs, DEFAULT_STARTUP_TIMEOUT_MS);
    this.requestTimeoutMs = positiveMs(options.requestTimeoutMs, DEFAULT_REQUEST_TIMEOUT_MS);
    this.teardownGraceMs = positiveMs(options.teardownGraceMs, DEFAULT_TEARDOWN_GRACE_MS);
    this.processFactory = options.processFactory ?? createDefaultDevinAcpProcessFactory();
    this.version = readPackageVersion();
    if (options.onEvent) {
      this.listeners.add(options.onEvent);
    }
    if (options.onPermission) {
      this.permissionHandler = options.onPermission;
    }
  }

  get currentState(): DevinAcpState {
    return this.state;
  }

  get snapshot(): DevinAcpCapabilities | undefined {
    return this.capabilities;
  }

  addEventListener(handler: (event: DevinAcpEvent) => void): () => void {
    this.listeners.add(handler);
    return () => {
      this.listeners.delete(handler);
    };
  }

  setPermissionHandler(handler: DevinAcpPermissionHandler | undefined): void {
    this.permissionHandler = handler;
  }

  async ensureStarted(): Promise<DevinAcpCapabilities> {
    if (this.state === "ready" && this.capabilities) {
      return this.capabilities;
    }
    if (this.closed || this.closing) {
      throw this.closeError ?? new DevinAcpError("explicit_close", "Devin ACP client is closed.");
    }
    if (this.startupPromise) {
      return await this.startupPromise;
    }
    this.startupPromise = this.runStartup();
    try {
      const capabilities = await this.startupPromise;
      return capabilities;
    } finally {
      this.startupPromise = undefined;
    }
  }

  private async runStartup(): Promise<DevinAcpCapabilities> {
    await this.pendingTeardown();
    this.failureError = undefined;
    this.closeError = undefined;
    this.setState("starting");
    try {
      const resolution = resolveDevinAcpCommand(this.command);
      this.process = await this.processFactory.create({
        command: this.command,
        executable: resolution.resolvedPath,
        args: resolution.args,
        cwd: this.cwd
      });

      this.consumeStderr();
      this.connection = this.buildConnection(this.process);
      this.observeProcessExit();

      const initRequest = this.buildInitializeRequest();
      const initResult = await withTimeout(
        this.connection.agent.request(methods.agent.initialize, initRequest),
        this.startupTimeoutMs,
        () => this.fail(new DevinAcpError("startup_timeout", "Devin ACP initialization timed out."))
      );

      if (initResult.protocolVersion !== PROTOCOL_VERSION) {
        throw new DevinAcpError(
          "protocol_mismatch",
          `Devin ACP protocol version mismatch: expected ${PROTOCOL_VERSION}, received ${initResult.protocolVersion}.`
        );
      }

      this.capabilities = normalizeDevinCapabilities(initResult);
      this.setState("ready");
      return this.capabilities;
    } catch (error) {
      if (this.closeError) {
        throw this.closeError;
      }
      if (this.failureError) {
        throw this.failureError;
      }
      const classified = classifyAcpError(error);
      await this.fail(classified);
      throw classified;
    } finally {
      this.startupPromise = undefined;
    }
  }

  private async pendingTeardown(): Promise<void> {
    if (this.teardownPromise) {
      await this.teardownPromise;
    }
  }

  async request<Method extends AgentRequestMethod>(
    method: Method,
    params: AgentRequestParamsByMethod[Method],
    options?: { timeoutMs?: number }
  ): Promise<AgentRequestResponsesByMethod[Method]> {
    await this.ensureStarted();
    if (!this.connection || this.state !== "ready") {
      throw this.closeError ?? this.failureError ?? new DevinAcpError("not_ready", "Devin ACP connection is not ready.");
    }

    const timeoutMs = options?.timeoutMs ?? this.requestTimeoutMs;
    try {
      return await withTimeout(
        this.connection.agent.request(method, params),
        timeoutMs,
        () => this.fail(new DevinAcpError("request_timeout", `Devin ACP request ${method} timed out.`))
      );
    } catch (error) {
      if (this.closeError) {
        throw this.closeError;
      }
      if (this.failureError) {
        throw this.failureError;
      }
      throw classifyAcpError(error);
    }
  }

  async sessionNew(params: NewSessionRequest, options?: { timeoutMs?: number }): Promise<NewSessionResponse> {
    await this.ensureStarted();
    this.assertNoAdditionalDirectoriesUnlessSupported(params, "session/new");
    return this.request(methods.agent.session.new, params, options) as Promise<NewSessionResponse>;
  }

  async sessionList(params?: ListSessionsRequest, options?: { timeoutMs?: number }): Promise<ListSessionsResponse> {
    await this.ensureStarted();
    this.assertNoAdditionalDirectoriesUnlessSupported(params ?? {}, "session/list");
    this.assertCapability("listSessions", "session/list");
    return this.request(methods.agent.session.list, params ?? {}, options) as Promise<ListSessionsResponse>;
  }

  async sessionLoad(params: LoadSessionRequest, options?: { timeoutMs?: number }): Promise<LoadSessionResponse> {
    await this.ensureStarted();
    this.assertCapability("loadSession", "session/load");
    this.assertNoAdditionalDirectoriesUnlessSupported(params, "session/load");
    return this.request(methods.agent.session.load, params, options) as Promise<LoadSessionResponse>;
  }

  async sessionClose(params: CloseSessionRequest, options?: { timeoutMs?: number }): Promise<void> {
    await this.ensureStarted();
    this.assertCapability("closeSession", "session/close");
    await this.request(methods.agent.session.close, params, options);
  }

  async sessionSetConfigOption(
    params: SetSessionConfigOptionRequest,
    options?: { timeoutMs?: number }
  ): Promise<SetSessionConfigOptionResponse> {
    return this.request(methods.agent.session.setConfigOption, params, options) as Promise<SetSessionConfigOptionResponse>;
  }

  async sessionPrompt(params: PromptRequest, options?: { timeoutMs?: number }): Promise<PromptResponse> {
    return this.request(methods.agent.session.prompt, params, options) as Promise<PromptResponse>;
  }

  async sessionCancel(params: CancelNotification): Promise<void> {
    await this.ensureStarted();
    if (!this.connection || this.state !== "ready") {
      throw new DevinAcpError("not_ready", "Devin ACP connection is not ready.");
    }
    return this.connection.agent.notify(methods.agent.session.cancel, params);
  }

  async close(): Promise<void> {
    if (this.closed || this.closing) {
      return;
    }
    this.closing = true;
    this.setState("closing");
    this.closeError = new DevinAcpError("explicit_close", "Devin ACP client was closed.");
    await this.teardown();
    this.closed = true;
    this.closing = false;
    this.setState("stopped");
  }

  health(): DevinAcpHealth {
    const error = this.closeError ?? this.failureError ?? null;
    return {
      state: this.state,
      error: error ? { code: error.code, message: error.message } : null,
      command: sanitizeDevinCommand(this.command),
      cwd: this.cwd,
      agentName: this.capabilities?.agentName ?? null,
      agentVersion: this.capabilities?.agentVersion ?? null,
      capabilities: this.capabilities ?? null,
      stderr: this.stderr.snapshot()
    };
  }

  private buildInitializeRequest(): InitializeRequest {
    return {
      protocolVersion: PROTOCOL_VERSION,
      clientInfo: {
        name: "lynk-bridge",
        title: "Lynk",
        version: this.version
      },
      clientCapabilities: {
        fs: {
          readTextFile: false,
          writeTextFile: false
        },
        terminal: false
      }
    };
  }

  private buildConnection(process: DevinAcpProcess): ClientConnection {
    const app = client({ name: "lynk-bridge-devin" })
      .onRequest(methods.client.session.requestPermission, (ctx) => this.handlePermissionRequest(ctx.params))
      .onNotification(methods.client.session.update, (ctx) => this.handleSessionUpdate(ctx.params));

    return app.connect(ndJsonStream(process.stdin, process.stdout));
  }

  private async handlePermissionRequest(params: RequestPermissionRequest): Promise<RequestPermissionResponse> {
    const handler = this.permissionHandler;
    if (!handler) {
      return { outcome: { outcome: "cancelled" } };
    }
    try {
      return await handler(params);
    } catch {
      return { outcome: { outcome: "cancelled" } };
    }
  }

  private handleSessionUpdate(notification: SessionNotification): void {
    this.emit({ type: "session/update", notification });
  }

  private consumeStderr(): void {
    if (!this.process) {
      return;
    }
    const reader = this.process.stderr.getReader();
    const pump = async (): Promise<void> => {
      try {
        while (true) {
          const { done, value } = await reader.read();
          if (done) {
            break;
          }
          if (value) {
            this.stderr.append(value);
          }
        }
      } catch {
        // Stderr is diagnostic only; read errors are not fatal.
      } finally {
        try {
          reader.releaseLock();
        } catch {
          // ignore
        }
      }
    };
    pump().catch(() => {});
  }

  private observeProcessExit(): void {
    if (!this.process) {
      return;
    }
    const process = this.process;
    process.exited
      .then(({ code, signal, spawnError }) => {
        if (this.closed || this.closing || this.failureError) {
          return;
        }
        if (spawnError) {
          const message = sanitizeDiagnosticText(spawnError.message);
          this.fail(new DevinAcpError("spawn_failure", `Devin ACP process failed: ${message}`));
          return;
        }
        const reason = signal ? `signal ${signal}` : `exit code ${code ?? "unknown"}`;
        this.fail(new DevinAcpError("unexpected_exit", `Devin ACP process exited unexpectedly (${reason}).`));
      })
      .catch((error: unknown) => {
        if (this.closed || this.closing || this.failureError) {
          return;
        }
        const raw = error instanceof Error ? error.message : String(error);
        const message = sanitizeDiagnosticText(raw);
        this.fail(new DevinAcpError("spawn_failure", `Devin ACP process failed: ${message}`));
      });
  }

  private async fail(error: DevinAcpError): Promise<void> {
    if (this.closed || this.closing || this.failureError) {
      return;
    }
    this.failureError = error;
    this.setState("failed", error);
    await this.teardown();
  }

  private async teardown(): Promise<void> {
    if (this.teardownPromise) {
      return this.teardownPromise;
    }

    const connection = this.connection;
    const proc = this.process;
    this.connection = undefined;
    this.process = undefined;

    this.teardownPromise = (async () => {
      this.stderr.flush();
      try {
        connection?.close(this.closeError ?? this.failureError);
      } catch {
        // ignore
      }
      await this.terminateProcess(proc);
    })();

    try {
      await this.teardownPromise;
    } finally {
      this.teardownPromise = undefined;
    }
  }

  private async terminateProcess(proc?: DevinAcpProcess): Promise<void> {
    if (!proc) {
      return;
    }

    try {
      proc.kill("SIGTERM");
    } catch {
      try {
        proc.kill();
      } catch {
        // ignore
      }
    }

    const settled = await Promise.race([proc.exited.then(() => true), sleep(this.teardownGraceMs).then(() => false)]);
    if (settled) {
      return;
    }

    try {
      proc.kill("SIGKILL");
    } catch {
      try {
        proc.kill();
      } catch {
        // ignore
      }
    }

    await Promise.race([proc.exited, sleep(this.teardownGraceMs)]);
  }

  private emit(event: DevinAcpEvent): void {
    for (const listener of this.listeners) {
      try {
        listener(event);
      } catch {
        // Transport listeners must not crash the client.
      }
    }
  }

  private setState(state: DevinAcpState, error?: DevinAcpError): void {
    this.state = state;
    this.emit({ type: "lifecycle", state, error: error ?? null });
  }

  private assertCapability(name: keyof DevinAcpCapabilities, method: string): void {
    if (!this.capabilities || !this.capabilities[name]) {
      throw new DevinAcpError("capability_unavailable", `Devin ACP does not advertise ${method} support.`);
    }
  }

  private assertNoAdditionalDirectoriesUnlessSupported(params: Record<string, unknown>, method: string): void {
    if (hasAdditionalDirectoriesField(params)) {
      this.assertCapability("additionalDirectories", `${method} with additionalDirectories`);
    }
  }
}

function hasAdditionalDirectoriesField(params: Record<string, unknown>): boolean {
  return Object.prototype.hasOwnProperty.call(params, "additionalDirectories") && params.additionalDirectories !== undefined && params.additionalDirectories !== null;
}
