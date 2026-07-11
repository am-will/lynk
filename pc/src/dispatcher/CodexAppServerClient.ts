import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { createInterface, type Interface } from "node:readline";
import type { ResolvedChatAttachment } from "../attachments/AttachmentTypes.js";
import type { AuditLog } from "../bridge/AuditLog.js";
import { defaultWorkspaceRoot } from "../host/HostPaths.js";
import type { AgentClient, AgentRequestOptions, AgentRunResult, AgentStatusSink } from "./AgentClient.js";
import { PHONE_AGENT_SYSTEM_PROMPT, buildPhoneAgentPrompt } from "./safetyPrompt.js";
import { AdapterFailure, translateAdapterError } from "../bridge/harness/AdapterFailure.js";

const DEFAULT_RPC_TIMEOUT_MS = 30_000;
const MAX_RPC_LINE_BYTES = 1_048_576;
const TERM_GRACE_MS = 1_500;
const KILL_GRACE_MS = 500;

interface JsonRpcRequest {
  id?: number;
  method?: string;
  params?: unknown;
  result?: unknown;
  error?: unknown;
}

interface PendingRpc {
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
  generation: number;
  method: string;
}

interface PendingTurn {
  threadId: string;
  turnId: string;
  finalMessage: string[];
  usage?: Record<string, unknown>;
  resolve: (value: AgentRunResult) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
  generation: number;
}

type CodexUserInput =
  | { type: "text"; text: string }
  | { type: "image"; url: string }
  | { type: "localImage"; path: string };

export interface RealtimeTranscriptDelta {
  role: string;
  delta: string;
  text?: string;
  isFinal: boolean;
  itemId?: string | null;
}

export interface RealtimeSpeechStarted {
  role?: string;
  itemId?: string | null;
}

export interface RealtimeEventSink {
  sdp(sdp: string): void;
  transcriptDelta(delta: RealtimeTranscriptDelta): void;
  itemAdded(item: unknown): void;
  speechStarted(event: RealtimeSpeechStarted): void;
  error(message: string): void;
  closed(reason: string | null): void;
}

export interface RealtimeStartOptions {
  deviceId: string;
  sdp: string;
  systemPrompt?: string;
  model?: string;
  reasoningEffort?: string;
}

export interface RealtimeSession {
  deviceId: string;
  threadId: string;
  realtimeSessionId?: string | null;
}

export interface CodexThreadListOptions {
  limit?: number;
  cursor?: string;
  cwd?: string | string[];
  searchTerm?: string;
  archived?: boolean | null;
}

interface ActiveRealtimeSession {
  deviceId: string;
  threadId: string;
  sink: RealtimeEventSink;
}

function commandParts(command: string): string[] {
  return command.match(/(?:[^\s"]+|"[^"]*")+/g)?.map((part) => part.replace(/^"|"$/g, "")) ?? [];
}

function isBlockedFinalMessage(message: string): boolean {
  const normalized = message.trim().toLowerCase();
  return normalized.startsWith("blocked:");
}

function isCompleteFinalMessage(message: string): boolean {
  const normalized = message.trim().toLowerCase();
  return normalized.startsWith("task_complete:");
}

function buildRealtimeBaseInstructions(systemPrompt?: string): string {
  const prompt = systemPrompt?.trim() || PHONE_AGENT_SYSTEM_PROMPT;
  return `${prompt}

Realtime voice mode:
- The user is speaking through the Android voice interface, so keep spoken responses concise.
- Use the android-phone MCP tools when phone observation or action is needed.
- Preserve the same safety rules as text mode; sensitive OS prompts remain manual.`.trim();
}

function estimatePromptTokens(text: string): number {
  return Math.ceil(text.length / 4);
}

function promptMetrics(text: string): { chars: number; estimatedTokens: number } {
  return {
    chars: text.length,
    estimatedTokens: estimatePromptTokens(text)
  };
}

function isSpeechStartedItem(item: unknown): boolean {
  if (!item || typeof item !== "object") {
    return false;
  }
  const type = (item as { type?: unknown; event?: { type?: unknown } }).type
    ?? (item as { event?: { type?: unknown } }).event?.type;
  return typeof type === "string" && type.toLowerCase().includes("speech_started");
}

function itemStringField(item: unknown, key: string): string | undefined {
  if (!item || typeof item !== "object") {
    return undefined;
  }
  const value = (item as Record<string, unknown>)[key];
  return typeof value === "string" ? value : undefined;
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" ? value as Record<string, unknown> : undefined;
}

export function normalizeCodexUsage(value: unknown): Record<string, unknown> | undefined {
  const record = asRecord(value);
  if (!record) {
    return undefined;
  }
  const usage = asRecord(record.usage)
    ?? asRecord(record.tokenUsage)
    ?? asRecord(record.token_usage)
    ?? asRecord(asRecord(record.msg)?.info)
    ?? record;
  const totalUsage = asRecord(usage.total)
    ?? asRecord(usage.totalTokenUsage)
    ?? asRecord(usage.total_token_usage)
    ?? usage;
  const inputTokens = firstNumberField(totalUsage, [
    "inputTokens",
    "input_tokens",
    "promptTokens",
    "prompt_tokens",
    "totalInputTokens",
    "total_input_tokens"
  ]);
  const outputTokens = firstNumberField(totalUsage, [
    "outputTokens",
    "output_tokens",
    "completionTokens",
    "completion_tokens",
    "totalOutputTokens",
    "total_output_tokens"
  ]);
  const totalTokens = firstNumberField(totalUsage, [
    "totalTokens",
    "total_tokens",
    "tokensUsed",
    "tokens_used",
    "total",
    "used"
  ]) ?? sumTokens(inputTokens, outputTokens);
  const contextTokens = firstNumberField(usage, [
    "modelContextWindow",
    "model_context_window",
    "contextTokens",
    "context_tokens",
    "contextWindow",
    "context_window",
    "contextLength",
    "context_length",
    "maxInputTokens",
    "max_input_tokens"
  ]) ?? firstNumberField(totalUsage, [
    "contextTokens",
    "context_tokens",
    "contextWindow",
    "context_window",
    "contextLength",
    "context_length",
    "maxInputTokens",
    "max_input_tokens"
  ]);
  const normalized: Record<string, unknown> = {};
  if (inputTokens !== undefined) {
    normalized.inputTokens = inputTokens;
  }
  if (outputTokens !== undefined) {
    normalized.outputTokens = outputTokens;
  }
  if (totalTokens !== undefined) {
    normalized.totalTokens = totalTokens;
  }
  if (contextTokens !== undefined) {
    normalized.contextTokens = contextTokens;
  }
  return Object.keys(normalized).length > 0 ? normalized : undefined;
}

function firstNumberField(record: Record<string, unknown>, keys: string[]): number | undefined {
  for (const key of keys) {
    const value = record[key];
    if (typeof value === "number" && Number.isFinite(value)) {
      return value;
    }
  }
  return undefined;
}

function sumTokens(inputTokens: number | undefined, outputTokens: number | undefined): number | undefined {
  if (inputTokens === undefined && outputTokens === undefined) {
    return undefined;
  }
  return (inputTokens ?? 0) + (outputTokens ?? 0);
}

export class CodexAppServerClient implements AgentClient {
  private child?: ChildProcessWithoutNullStreams;
  private stdoutBuffer = "";
  private nextId = 1;
  private initialized = false;
  private generation = 0;
  private starting?: Promise<void>;
  private closed = false;
  private readonly pending = new Map<number, PendingRpc>();
  private readonly latestUsageByThread = new Map<string, Record<string, unknown>>();
  private readonly loadedThreads = new Set<string>();
  private pendingTurn?: PendingTurn;
  private activeSink?: AgentStatusSink;
  private readonly realtimeSessions = new Map<string, ActiveRealtimeSession>();

  constructor(
    private readonly audit?: AuditLog,
    private readonly command = process.env.CODEX_APP_SERVER_COMMAND ?? "codex app-server --listen stdio://",
    private readonly cwd = process.env.CODEX_AGENT_CWD ?? defaultWorkspaceRoot(),
    private readonly approvalPolicy = process.env.CODEX_APP_SERVER_APPROVAL_POLICY?.trim() || "never",
    private readonly sandbox = process.env.CODEX_APP_SERVER_SANDBOX?.trim() || "workspace-write"
  ) {}

  async submitUserRequest(
    text: string,
    sink: AgentStatusSink,
    options: AgentRequestOptions = {}
  ): Promise<AgentRunResult> {
    this.activeSink = sink;
    await this.ensureStarted();
    const baseInstructions = options.systemPrompt?.trim() || PHONE_AGENT_SYSTEM_PROMPT;
    const threadId = options.threadId
      ? await this.resumeThread({ threadId: options.threadId, model: options.model, cwd: options.cwd })
      : await this.createThread({
        model: options.model,
        cwd: options.cwd,
        ...(options.useSessionInstructions ? { baseInstructions } : {})
      });
    this.audit?.record("codex_turn_starting", undefined, { threadId, text, model: options.model, reasoningEffort: options.reasoningEffort });
    sink.working("Sending request to Codex app-server");
    const turnInput = options.useSessionInstructions ? text : buildPhoneAgentPrompt(text, options.systemPrompt);
    this.audit?.record("codex_prompt_metrics", options.deviceId, {
      threadId,
      path: "dispatcher.submitUserRequest",
      baseInstructionDelivery: options.useSessionInstructions ? "thread" : "turn",
      baseInstructions: promptMetrics(baseInstructions),
      userText: promptMetrics(text),
      turnInput: promptMetrics(turnInput)
    });
    const result = await this.request("turn/start", {
      threadId,
      input: codexUserInput(turnInput, options.attachments),
      cwd: options.cwd ?? this.cwd,
      approvalPolicy: this.approvalPolicy,
      model: options.model,
      effort: options.reasoningEffort,
      personality: "pragmatic"
    });
    const turnId = (result as { turn?: { id?: string } }).turn?.id;
    if (!turnId) {
      throw new Error("Codex app-server turn/start returned no turn id");
    }
    this.audit?.record("codex_turn_started", undefined, { threadId, turnId });
    return await this.waitForTurn(threadId, turnId);
  }

  async startRealtime(options: RealtimeStartOptions, sink: RealtimeEventSink): Promise<RealtimeSession> {
    await this.ensureStarted();
    const threadId = await this.createThread({
      model: options.model,
      baseInstructions: buildRealtimeBaseInstructions(options.systemPrompt)
    });
    this.realtimeSessions.set(threadId, { deviceId: options.deviceId, threadId, sink });
    this.audit?.record("codex_realtime_starting", options.deviceId, {
      threadId,
      model: options.model,
      reasoningEffort: options.reasoningEffort
    });

    try {
      const result = await this.request("thread/realtime/start", {
        threadId,
        transport: { type: "webrtc", sdp: options.sdp },
        outputModality: "audio",
        reasoningEffort: options.reasoningEffort
      });
      const realtimeSessionId = (result as { realtimeSessionId?: string | null; session?: { id?: string | null } })?.realtimeSessionId
        ?? (result as { session?: { id?: string | null } })?.session?.id
        ?? null;
      this.audit?.record("codex_realtime_started", options.deviceId, { threadId, realtimeSessionId });
      return { deviceId: options.deviceId, threadId, realtimeSessionId };
    } catch (error) {
      this.realtimeSessions.delete(threadId);
      throw new Error(`Codex app-server rejected experimental thread/realtime/start: ${error instanceof Error ? error.message : String(error)}`);
    }
  }

  async stopRealtime(threadId: string, reason = "Stopped by user"): Promise<void> {
    const session = this.realtimeSessions.get(threadId);
    if (!session) {
      return;
    }

    try {
      await this.request("thread/realtime/stop", { threadId, reason });
      this.audit?.record("codex_realtime_stop_requested", session.deviceId, { threadId, reason });
    } catch (error) {
      const message = `Codex app-server rejected experimental thread/realtime/stop: ${error instanceof Error ? error.message : String(error)}`;
      session.sink.error(message);
      this.audit?.record("codex_realtime_stop_error", session.deviceId, { threadId, error: message });
    } finally {
      if (this.realtimeSessions.delete(threadId)) {
        session.sink.closed(reason);
      }
    }
  }

  async close(): Promise<void> {
    if (this.closed) return;
    this.closed = true;
    for (const session of this.realtimeSessions.values()) {
      session.sink.closed("Codex client closed");
    }
    this.realtimeSessions.clear();
    const generation = this.generation;
    await this.failGeneration(generation, new AdapterFailure("cancelled", "Codex client closed", {
      harnessId: "codex",
      operation: "close"
    }), true);
    this.loadedThreads.clear();
    this.latestUsageByThread.clear();
  }

  async listModels(): Promise<unknown> {
    await this.ensureStarted();
    return await this.request("model/list", {});
  }

  async readModelProviderCapabilities(options: { model?: string; provider?: string } = {}): Promise<unknown> {
    await this.ensureStarted();
    return await this.request("modelProvider/capabilities/read", {
      ...(options.model ? { model: options.model } : {}),
      ...(options.provider ? { provider: options.provider } : {})
    });
  }

  async listThreads(options: CodexThreadListOptions = {}): Promise<unknown> {
    await this.ensureStarted();
    return await this.request("thread/list", {
      limit: options.limit ?? 50,
      sortKey: "updated_at",
      sortDirection: "desc",
      sourceKinds: [],
      ...(options.cursor ? { cursor: options.cursor } : {}),
      ...(options.cwd ? { cwd: options.cwd } : {}),
      ...(options.searchTerm ? { searchTerm: options.searchTerm } : {}),
      ...(options.archived !== undefined ? { archived: options.archived } : {})
    });
  }

  async readThread(threadId: string, includeTurns = false): Promise<unknown> {
    await this.ensureStarted();
    return await this.request("thread/read", { threadId, includeTurns });
  }

  async createThread(options: { model?: string; baseInstructions?: string; cwd?: string } = {}): Promise<string> {
    await this.ensureStarted();
    const result = await this.request("thread/start", {
      model: options.model,
      cwd: options.cwd ?? this.cwd,
      approvalPolicy: this.approvalPolicy,
      sandbox: this.sandbox,
      personality: "pragmatic",
      serviceName: "android_phone_agent",
      baseInstructions: options.baseInstructions
    });
    const thread = (result as { thread?: { id?: string } }).thread;
    if (!thread?.id) {
      throw new Error("Codex app-server thread/start returned no thread id");
    }
    this.loadedThreads.add(thread.id);
    return thread.id;
  }

  async resumeThread(options: { threadId: string; model?: string; cwd?: string }): Promise<string> {
    await this.ensureStarted();
    if (this.loadedThreads.has(options.threadId)) {
      return options.threadId;
    }
    const result = await this.request("thread/resume", {
      threadId: options.threadId,
      cwd: options.cwd ?? this.cwd,
      approvalPolicy: this.approvalPolicy,
      sandbox: this.sandbox,
      personality: "pragmatic",
      ...(options.model ? { model: options.model } : {})
    });
    const thread = (result as { thread?: { id?: string } }).thread;
    const threadId = thread?.id ?? options.threadId;
    this.loadedThreads.add(threadId);
    return threadId;
  }

  async steer(text: string, attachments?: ResolvedChatAttachment[]): Promise<void> {
    const pendingTurn = this.pendingTurn;
    if (!pendingTurn) {
      throw new Error("No active Codex turn to steer");
    }
    await this.request("turn/steer", {
      threadId: pendingTurn.threadId,
      expectedTurnId: pendingTurn.turnId,
      input: codexUserInput(text, attachments)
    });
    this.audit?.record("codex_turn_steered", undefined, {
      threadId: pendingTurn.threadId,
      turnId: pendingTurn.turnId,
      text
    });
  }

  async interrupt(reason = "Stopped by user"): Promise<void> {
    const pendingTurn = this.pendingTurn;
    if (pendingTurn) {
      clearTimeout(pendingTurn.timer);
      this.pendingTurn = undefined;
      try {
        await this.request("turn/interrupt", {
          threadId: pendingTurn.threadId,
          turnId: pendingTurn.turnId
        });
        this.audit?.record("codex_turn_interrupted", undefined, {
          threadId: pendingTurn.threadId,
          turnId: pendingTurn.turnId,
          reason
        });
      } catch (error) {
        this.audit?.record("codex_turn_interrupt_error", undefined, {
          threadId: pendingTurn.threadId,
          turnId: pendingTurn.turnId,
          reason,
          error: error instanceof Error ? error.message : String(error)
        });
      } finally {
        pendingTurn.resolve({
          threadId: pendingTurn.threadId,
          turnId: pendingTurn.turnId,
          finalMessage: `BLOCKED: ${reason}`,
          error: reason,
          usage: pendingTurn.usage ?? this.latestUsageByThread.get(pendingTurn.threadId)
        });
      }
    }
  }

  private async ensureStarted(): Promise<void> {
    if (this.closed) {
      throw new AdapterFailure("cancelled", "Codex client is closed", { harnessId: "codex", operation: "start" });
    }
    if (this.initialized && this.child) {
      return;
    }
    if (this.starting) return await this.starting;

    const generation = ++this.generation;
    const starting = this.startGeneration(generation);
    this.starting = starting;
    try {
      await starting;
    } finally {
      if (this.starting === starting) this.starting = undefined;
    }
  }

  private async startGeneration(generation: number): Promise<void> {

    const [bin, ...args] = commandParts(this.command);
    if (!bin) {
      throw new Error("CODEX_APP_SERVER_COMMAND is empty");
    }

    const child = spawn(bin, args, {
      cwd: this.cwd,
      env: process.env
    });
    this.child = child;
    this.stdoutBuffer = "";

    child.stderr.on("data", (chunk) => {
      if (generation !== this.generation) return;
      this.activeSink?.info(chunk.toString().trim());
    });
    child.stdout.on("data", (chunk) => this.handleStdoutChunk(generation, chunk));
    child.on("error", (cause) => {
      void this.failGeneration(generation, new AdapterFailure("unavailable", `Codex app-server process failed: ${cause.message}`, {
        cause,
        harnessId: "codex",
        operation: "process"
      }), true);
    });
    child.on("exit", (code, signal) => {
      void this.failGeneration(generation, new AdapterFailure(
        "unavailable",
        `Codex app-server exited with code ${code ?? "null"} signal ${signal ?? "null"}`,
        { harnessId: "codex", operation: "process" }
      ), false);
    });
    child.stdin.on("error", (cause) => {
      void this.failGeneration(generation, new AdapterFailure("unavailable", `Codex app-server stdin failed: ${cause.message}`, {
        cause,
        harnessId: "codex",
        operation: "stdin"
      }), true);
    });

    try {
      await this.request("initialize", {
        clientInfo: {
          name: "android_phone_agent",
          title: "Android Phone Agent Dispatcher",
          version: "0.1.0"
        },
        capabilities: { experimentalApi: true }
      }, generation);
      if (generation !== this.generation || this.child !== child) {
        throw new AdapterFailure("cancelled", "Codex startup generation was superseded", {
          harnessId: "codex",
          operation: "initialize"
        });
      }
      this.notify("initialized", {}, generation);
      this.initialized = true;
    } catch (error) {
      await this.failGeneration(generation, error instanceof Error ? error : new Error(String(error)), true);
      throw error;
    }
  }

  private request(method: string, params?: unknown, generation = this.generation): Promise<unknown> {
    const id = this.nextId++;
    const payload: JsonRpcRequest = { id, method, params };
    return new Promise((resolve, reject) => {
      const timeoutMs = positiveEnvInt("CODEX_RPC_TIMEOUT_MS", DEFAULT_RPC_TIMEOUT_MS);
      const timer = setTimeout(() => {
        const pending = this.pending.get(id);
        if (!pending || pending.generation !== generation) return;
        this.pending.delete(id);
        const error = new AdapterFailure("timeout", `Codex RPC ${method} timed out after ${timeoutMs}ms`, {
          harnessId: "codex",
          operation: method
        });
        reject(error);
        void this.failGeneration(generation, error, true);
      }, timeoutMs);
      timer.unref?.();
      this.pending.set(id, { resolve, reject, timer, generation, method });
      this.audit?.record("codex_rpc_request", undefined, { id, method, params });
      try {
        this.write(payload, generation);
      } catch (error) {
        clearTimeout(timer);
        this.pending.delete(id);
        reject(error instanceof Error ? error : new Error(String(error)));
      }
    });
  }

  private waitForTurn(threadId: string, turnId: string): Promise<AgentRunResult> {
    if (this.pendingTurn) {
      throw new Error("A Codex turn is already running for this dispatcher");
    }

    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pendingTurn = undefined;
        const error = new AdapterFailure("timeout", `Timed out waiting for Codex turn ${turnId} to complete`, {
          harnessId: "codex",
          operation: "turn/completed"
        });
        reject(error);
        void this.failGeneration(this.generation, error, true);
      }, Number.parseInt(process.env.CODEX_TURN_TIMEOUT_MS ?? "600000", 10));

      this.pendingTurn = {
        threadId,
        turnId,
        finalMessage: [],
        resolve,
        reject,
        timer,
        generation: this.generation
      };
    });
  }

  private notify(method: string, params?: unknown, generation = this.generation): void {
    this.write({ method, params }, generation);
  }

  private write(payload: JsonRpcRequest, generation = this.generation): void {
    if (!this.child || generation !== this.generation) {
      throw new Error("Codex app-server process is not started");
    }
    const frame = `${JSON.stringify(payload)}\n`;
    if (Buffer.byteLength(frame) > MAX_RPC_LINE_BYTES) {
      throw new AdapterFailure("protocol", `Codex RPC frame exceeds ${MAX_RPC_LINE_BYTES} bytes`, {
        harnessId: "codex",
        operation: payload.method
      });
    }
    this.child.stdin.write(frame);
  }

  private handleStdoutChunk(generation: number, chunk: Buffer | string): void {
    if (generation !== this.generation) return;
    this.stdoutBuffer += chunk.toString();
    if (Buffer.byteLength(this.stdoutBuffer) > MAX_RPC_LINE_BYTES && !this.stdoutBuffer.includes("\n")) {
      void this.failGeneration(generation, new AdapterFailure("protocol", `Codex RPC line exceeds ${MAX_RPC_LINE_BYTES} bytes`, {
        harnessId: "codex",
        operation: "stdout"
      }), true);
      return;
    }
    while (true) {
      const newline = this.stdoutBuffer.indexOf("\n");
      if (newline < 0) return;
      const line = this.stdoutBuffer.slice(0, newline);
      this.stdoutBuffer = this.stdoutBuffer.slice(newline + 1);
      if (Buffer.byteLength(line) > MAX_RPC_LINE_BYTES) {
        void this.failGeneration(generation, new AdapterFailure("protocol", `Codex RPC line exceeds ${MAX_RPC_LINE_BYTES} bytes`, {
          harnessId: "codex",
          operation: "stdout"
        }), true);
        return;
      }
      this.handleLine(line, generation);
    }
  }

  private handleLine(line: string, generation: number): void {
    if (generation !== this.generation) return;
    if (!line.trim()) {
      return;
    }
    let message: any;
    try {
      message = JSON.parse(line);
    } catch (cause) {
      void this.failGeneration(generation, new AdapterFailure("protocol", "Codex app-server emitted invalid JSON on stdout", {
        cause,
        harnessId: "codex",
        operation: "stdout"
      }), true);
      return;
    }

    if (typeof message.id === "number" && this.pending.has(message.id)) {
      const pending = this.pending.get(message.id)!;
      if (pending.generation !== generation) return;
      this.pending.delete(message.id);
      clearTimeout(pending.timer);
      if (message.error) {
        this.audit?.record("codex_rpc_response", undefined, { id: message.id, error: message.error });
        pending.reject(translateAdapterError(message.error, {
          harnessId: "codex",
          operation: pending.method,
          fallbackCode: "rejected"
        }));
      } else {
        this.audit?.record("codex_rpc_response", undefined, { id: message.id, result: message.result });
        pending.resolve(message.result);
      }
      return;
    }

    if (typeof message.id === "number" && typeof message.method === "string") {
      this.handleServerRequest(message);
      return;
    }

    this.audit?.record("codex_app_server_message", undefined, message);
    this.handleNotification(message);
  }

  private async failGeneration(generation: number, error: Error, terminate: boolean): Promise<void> {
    if (generation !== this.generation) return;
    const child = this.child;
    this.generation += 1;
    this.child = undefined;
    this.initialized = false;
    this.stdoutBuffer = "";
    for (const [id, pending] of this.pending) {
      if (pending.generation !== generation) continue;
      clearTimeout(pending.timer);
      this.pending.delete(id);
      pending.reject(error);
    }
    if (this.pendingTurn?.generation === generation) {
      clearTimeout(this.pendingTurn.timer);
      this.pendingTurn.reject(error);
      this.pendingTurn = undefined;
    }
    this.loadedThreads.clear();
    this.latestUsageByThread.clear();
    for (const session of this.realtimeSessions.values()) {
      session.sink.error(error.message);
      session.sink.closed(error.message);
    }
    this.realtimeSessions.clear();
    this.activeSink?.error(error.message);
    if (!child) return;
    if (terminate && child.exitCode === null && child.signalCode === null) {
      child.kill("SIGTERM");
      if (!await waitForChildExit(child, TERM_GRACE_MS)) {
        child.kill("SIGKILL");
        await waitForChildExit(child, KILL_GRACE_MS);
      }
    }
    removeChildListeners(child);
  }

  private handleServerRequest(message: { id: number; method: string; params?: any }): void {
    if (message.method === "mcpServer/elicitation/request") {
      const serverName = message.params?.serverName;
      const approvalKind = message.params?._meta?.codex_approval_kind;
      const toolDescription = message.params?._meta?.tool_description ?? "MCP tool";
      if (serverName === "android-phone" && approvalKind === "mcp_tool_call") {
        this.activeSink?.tool(`approved android-phone tool: ${toolDescription}`);
        this.write({
          id: message.id,
          result: { action: "accept", content: {} }
        });
        return;
      }
    }

    this.activeSink?.error(`Declined unsupported app-server request: ${message.method}`);
    this.write({
      id: message.id,
      result: { action: "decline", content: null }
    });
  }

  private findRealtimeSession(params?: any): ActiveRealtimeSession | undefined {
    const threadId = params?.threadId;
    if (typeof threadId === "string") {
      return this.realtimeSessions.get(threadId);
    }
    if (this.realtimeSessions.size === 1) {
      return this.realtimeSessions.values().next().value;
    }
    return undefined;
  }

  private handleRealtimeNotification(message: { method?: string; params?: any }): boolean {
    const method = message.method;
    if (!method?.startsWith("thread/realtime/")) {
      return false;
    }

    const session = this.findRealtimeSession(message.params);
    if (!session) {
      this.audit?.record("codex_realtime_unmatched_notification", undefined, message);
      return true;
    }

    const params = message.params ?? {};
    this.audit?.record("codex_realtime_notification", session.deviceId, {
      method,
      threadId: session.threadId
    });

    if (method === "thread/realtime/sdp" && typeof params.sdp === "string") {
      session.sink.sdp(params.sdp);
      return true;
    }

    if (method === "thread/realtime/transcript/delta") {
      const delta = typeof params.delta === "string" ? params.delta : "";
      const role = typeof params.role === "string" ? params.role : "assistant";
      session.sink.transcriptDelta({
        role,
        delta,
        isFinal: false,
        itemId: typeof params.itemId === "string" ? params.itemId : undefined
      });
      return true;
    }

    if (method === "thread/realtime/transcript/done") {
      const text = typeof params.text === "string" ? params.text : "";
      const role = typeof params.role === "string" ? params.role : "assistant";
      session.sink.transcriptDelta({
        role,
        delta: "",
        text,
        isFinal: true,
        itemId: typeof params.itemId === "string" ? params.itemId : undefined
      });
      return true;
    }

    if (method === "thread/realtime/itemAdded") {
      const item = params.item ?? params;
      session.sink.itemAdded(item);
      if (isSpeechStartedItem(item)) {
        session.sink.speechStarted({
          role: itemStringField(item, "role"),
          itemId: itemStringField(item, "itemId") ?? itemStringField(item, "item_id") ?? null
        });
      }
      return true;
    }

    if (method === "thread/realtime/speechStarted" || method === "thread/realtime/speech_started") {
      session.sink.speechStarted({
        role: typeof params.role === "string" ? params.role : undefined,
        itemId: typeof params.itemId === "string" ? params.itemId : null
      });
      return true;
    }

    if (method === "thread/realtime/error") {
      const text = typeof params.message === "string" ? params.message : "Codex realtime session failed";
      this.realtimeSessions.delete(session.threadId);
      session.sink.error(text);
      session.sink.closed(text);
      return true;
    }

    if (method === "thread/realtime/closed") {
      const reason = typeof params.reason === "string" ? params.reason : null;
      this.realtimeSessions.delete(session.threadId);
      session.sink.closed(reason);
      return true;
    }

    return true;
  }

  private handleNotification(message: { method?: string; params?: any }): void {
    if (this.handleRealtimeNotification(message)) {
      return;
    }

    if (message.method === "thread/tokenUsage/updated") {
      const pendingTurn = this.pendingTurn;
      const usage = normalizeCodexUsage(message.params);
      const threadId = typeof message.params?.threadId === "string" ? message.params.threadId : undefined;
      if (threadId && usage) {
        this.latestUsageByThread.set(threadId, usage);
      }
      if (pendingTurn && usage && (!threadId || threadId === pendingTurn.threadId)) {
        pendingTurn.usage = { ...pendingTurn.usage, ...usage };
      }
      return;
    }

    const sink = this.activeSink;
    if (!sink || !message.method) {
      return;
    }

    if (message.method === "item/agentMessage/delta") {
      const text = message.params?.delta ?? message.params?.textDelta;
      if (typeof text === "string" && text.trim()) {
        this.pendingTurn?.finalMessage.push(text);
      }
      return;
    }

    if (message.method === "turn/started") {
      sink.working("Codex started working");
      return;
    }

    if (message.method === "turn/completed") {
      const turnId = message.params?.turn?.id ?? message.params?.turnId;
      const pendingTurn = this.pendingTurn;
      if (pendingTurn && (!turnId || turnId === pendingTurn.turnId)) {
        const usage = normalizeCodexUsage(message.params?.usage ?? message.params?.turn?.usage ?? message.params?.turn?.tokenUsage);
        if (usage) {
          pendingTurn.usage = { ...pendingTurn.usage, ...usage };
        }
        clearTimeout(pendingTurn.timer);
        this.pendingTurn = undefined;
        const finalMessage = pendingTurn.finalMessage.join("").trim();
        this.resolveCompletedTurn(pendingTurn, finalMessage);
        const blocked = isBlockedFinalMessage(finalMessage);
        const complete = isCompleteFinalMessage(finalMessage);
        if (blocked) {
          sink.error(finalMessage || "Codex reported the phone task is blocked");
        } else if (complete) {
          sink.done(finalMessage);
        } else {
          sink.done(finalMessage ? `Codex stopped: ${finalMessage}` : "Codex turn completed without a final task status");
        }
        return;
      }
      sink.done("Codex turn completed");
      return;
    }

    if (message.method === "item/started" || message.method === "item/completed") {
      const item = message.params?.item;
      if (item?.type === "mcpToolCall") {
        sink.tool(`${item.server ?? "mcp"}.${item.tool ?? "tool"} ${item.status ?? ""}`.trim());
      }
    }
  }

  private resolveCompletedTurn(pendingTurn: PendingTurn, finalMessage: string): void {
    const blocked = isBlockedFinalMessage(finalMessage);
    pendingTurn.resolve({
      threadId: pendingTurn.threadId,
      turnId: pendingTurn.turnId,
      finalMessage,
      error: blocked ? finalMessage : undefined,
      usage: pendingTurn.usage ?? this.latestUsageByThread.get(pendingTurn.threadId)
    });
  }
}

function positiveEnvInt(name: string, fallback: number): number {
  const parsed = Number.parseInt(process.env[name] ?? "", 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

async function waitForChildExit(child: ChildProcessWithoutNullStreams, timeoutMs: number): Promise<boolean> {
  if (child.exitCode !== null || child.signalCode !== null) return true;
  return await new Promise<boolean>((resolve) => {
    const onExit = () => finish(true);
    const timer = setTimeout(() => finish(false), timeoutMs);
    timer.unref?.();
    const finish = (exited: boolean) => {
      clearTimeout(timer);
      child.off("exit", onExit);
      resolve(exited);
    };
    child.once("exit", onExit);
  });
}

function removeChildListeners(child: ChildProcessWithoutNullStreams): void {
  child.removeAllListeners();
  child.stdin.removeAllListeners();
  child.stdout.removeAllListeners();
  child.stderr.removeAllListeners();
}

function codexUserInput(text: string, attachments: ResolvedChatAttachment[] | undefined): CodexUserInput[] {
  const input: CodexUserInput[] = [{ type: "text", text }];
  for (const attachment of attachments ?? []) {
    if (!isImageAttachment(attachment)) {
      continue;
    }
    input.push({
      type: "localImage",
      path: attachment.localPath
    });
  }
  return input;
}

function isImageAttachment(attachment: ResolvedChatAttachment): boolean {
  return attachment.kind === "image" || attachment.mimeType.startsWith("image/");
}
