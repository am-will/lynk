import { randomUUID } from "node:crypto";
import { basename } from "node:path";
import {
  buildSessionContext,
  SessionManager,
  type AgentSessionEvent,
  type AgentSessionRuntime,
  type SessionEntry,
  type SessionInfo
} from "@earendil-works/pi-coding-agent";
import type { AuditLog } from "../AuditLog.js";
import type { ChatAttachment } from "../../protocol/messages.js";
import type { GatewayChatSendResult, GatewayEvent, GatewayEventHandler } from "../chat/ChatTransportTypes.js";
import { DEFAULT_REASONING_OPTIONS } from "../chat/ModelCatalog.js";
import { InMemoryHarnessSessionStore, type HarnessStoredSession } from "../harness/InMemoryHarnessSessionStore.js";
import { PiSdkClient, piModelId, type PiModel } from "./PiSdkClient.js";

interface ActiveRun {
  sessionKey: string;
  runId: string;
  runtime: AgentSessionRuntime;
  unsubscribe?: () => void;
  finalEmitted: boolean;
}

const PI_SESSION_PREFIX = "pi:";
const PI_SESSION_PATH_KEY = "piSessionPath";
const PI_SESSION_CWD_KEY = "piCwd";
const PI_DEFAULT_MODEL = "anthropic/claude-sonnet-4-5";

export class PiChatClient {
  private readonly client: PiSdkClient;
  private readonly sessions: InMemoryHarnessSessionStore;
  private readonly handlers = new Set<GatewayEventHandler>();
  private readonly runtimes = new Map<string, AgentSessionRuntime>();
  private active?: ActiveRun;

  constructor(
    private readonly audit?: AuditLog,
    client?: PiSdkClient,
    sessionStoragePath: string | null = "state/pi-sessions.json",
    options: {
      cwd?: string;
      agentDir?: string;
      defaultModel?: string;
      timeoutMs?: number;
    } = {}
  ) {
    this.client = client ?? new PiSdkClient(audit, options);
    this.sessions = new InMemoryHarnessSessionStore("pi", {
      defaultModel: options.defaultModel ?? process.env.PI_DEFAULT_MODEL?.trim() ?? PI_DEFAULT_MODEL,
      modelProvider: "pi",
      storagePath: sessionStoragePath ?? undefined,
      persistEmptySessions: false
    });
  }

  addEventListener(handler: GatewayEventHandler): () => void {
    this.handlers.add(handler);
    return () => this.handlers.delete(handler);
  }

  async history(sessionKey: string): Promise<unknown> {
    const session = await this.ensureStoredSession(sessionKey);
    const sessionPath = sessionPathForSession(session);
    if (sessionPath) {
      const manager = SessionManager.open(sessionPath, undefined, directoryForSession(session));
      return {
        sessionId: manager.getSessionId(),
        thinkingLevel: this.sessions.ensureSession(sessionKey, manager.getSessionId()).thinkingLevel ?? manager.buildSessionContext().thinkingLevel,
        messages: messagesFromPiEntries(manager.getEntries())
      };
    }
    return this.sessions.history(sessionKey);
  }

  async sendChat(options: {
    sessionKey: string;
    sessionId?: string;
    message: string;
    attachments?: ChatAttachment[];
    thinking?: string;
    idempotencyKey?: string;
  }): Promise<GatewayChatSendResult> {
    if (this.active) {
      throw new Error("A Pi task is already running");
    }
    const session = await this.ensureStoredSession(options.sessionKey, options.sessionId);
    this.sessions.setThinkingLevel(session, options.thinking ?? "medium");
    const runtime = await this.ensureRuntime(session, {
      model: session.model ?? undefined,
      thinkingLevel: session.thinkingLevel ?? options.thinking ?? "medium"
    });

    const runId = options.idempotencyKey ?? `pi_${randomUUID()}`;
    this.sessions.setActiveRun(session, runId);
    const active: ActiveRun = { sessionKey: session.key, runId, runtime, finalEmitted: false };
    active.unsubscribe = runtime.session.subscribe((event) => this.handleSessionEvent(active, event));
    this.active = active;
    void this.processRun(active, options.message, options.attachments);
    return { runId, sessionKey: session.key };
  }

  async steerChat(options: {
    sessionKey: string;
    runId?: string;
    message: string;
    attachments?: ChatAttachment[];
  }): Promise<GatewayChatSendResult> {
    if (!this.active || this.active.sessionKey !== options.sessionKey || (options.runId && this.active.runId !== options.runId)) {
      throw new Error("No active Pi task is running for this session");
    }
    await this.active.runtime.session.steer(options.message, imageAttachments(options.attachments));
    return { runId: this.active.runId, sessionKey: this.active.sessionKey };
  }

  async abort(_sessionKey: string, runId?: string): Promise<unknown> {
    if (!this.active || (runId && this.active.runId !== runId)) {
      return {};
    }
    const active = this.active;
    await this.client.abort(active.runtime);
    this.emit("chat", {
      sessionKey: active.sessionKey,
      runId: active.runId,
      state: "error",
      error: "Pi run stopped."
    });
    active.unsubscribe?.();
    this.active = undefined;
    return { status: "stopping" };
  }

  async listModels(): Promise<unknown> {
    return {
      models: this.client.listModels().map((model) => ({
        id: piModelId(model),
        key: piModelId(model),
        name: model.name,
        modelId: piModelId(model),
        provider: "pi",
        contextWindow: model.contextWindow,
        available: true,
        reasoningOptions: model.reasoning ? DEFAULT_REASONING_OPTIONS.map((option) => option.id) : undefined,
        defaultReasoningEffort: model.reasoning ? "medium" : undefined
      }))
    };
  }

  async listSessions(limit = 50): Promise<unknown> {
    const sessions = await this.listPiSessions(limit);
    return {
      sessions,
      defaults: {
        thinkingLevels: DEFAULT_REASONING_OPTIONS.map((option) => option.id)
      }
    };
  }

  async createSession(options: { key?: string; label?: string; model?: string; workspacePath?: string }): Promise<unknown> {
    const cwd = options.workspacePath?.trim() || this.client.defaultCwd();
    const runtime = await this.client.createRuntime({
      cwd,
      model: options.model,
      thinkingLevel: "medium"
    });
    const key = `${PI_SESSION_PREFIX}${runtime.session.sessionId}`;
    const local = this.sessions.ensureSession(key, runtime.session.sessionId);
    local.label = options.label?.trim() || runtime.session.sessionName || "Pi session";
    local.displayName = local.label;
    local.model = options.model?.trim() || piModelId(runtime.session.model as PiModel);
    this.sessions.setSessionId(local, runtime.session.sessionId);
    this.sessions.setMetadata(local, PI_SESSION_PATH_KEY, runtime.session.sessionFile);
    this.sessions.setMetadata(local, PI_SESSION_CWD_KEY, runtime.cwd);
    this.runtimes.set(key, runtime);
    return {
      key,
      sessionId: runtime.session.sessionId,
      label: local.label,
      displayName: local.displayName,
      workspacePath: runtime.cwd,
      workspaceName: workspaceNameFromPath(runtime.cwd)
    };
  }

  async patchSession(sessionKey: string, patch: Record<string, unknown>): Promise<unknown> {
    this.sessions.patchSession(sessionKey, patch);
    const session = this.sessions.ensureSession(sessionKey);
    const runtime = this.runtimes.get(sessionKey);
    if (runtime && typeof patch.model === "string") {
      await runtime.session.setModel(this.client.findModel(patch.model) ?? runtime.session.model as PiModel);
    }
    if (runtime && typeof patch.thinking === "string") {
      runtime.session.setThinkingLevel(this.client.normalizeThinkingLevel(patch.thinking));
    }
    return { ok: true };
  }

  async listCommands(): Promise<unknown> {
    return { commands: [] };
  }

  async effectiveTools(sessionKey: string): Promise<unknown> {
    const runtime = this.runtimes.get(sessionKey);
    return {
      tools: runtime?.session.getAllTools().map((tool) => ({
        name: tool.name,
        description: tool.description
      })) ?? []
    };
  }

  async health(): Promise<unknown> {
    return await this.client.health();
  }

  close(): void {
    for (const runtime of this.runtimes.values()) {
      void this.client.close(runtime);
    }
    this.runtimes.clear();
    this.active?.unsubscribe?.();
    this.active = undefined;
  }

  private async processRun(active: ActiveRun, message: string, attachments?: ChatAttachment[]): Promise<void> {
    try {
      await this.client.runWithTimeout(
        "Pi run",
        () => active.runtime.session.prompt(message, { images: imageAttachments(attachments) }),
        () => active.runtime.session.abort()
      );
      if (!active.finalEmitted) {
        this.emitFinalFromSession(active);
      }
    } catch (error) {
      this.emit("chat", {
        sessionKey: active.sessionKey,
        runId: active.runId,
        state: "error",
        error: errorMessage(error)
      });
    } finally {
      active.unsubscribe?.();
      const session = this.sessions.ensureSession(active.sessionKey);
      this.sessions.clearActiveRun(session, active.runId);
      if (this.active?.runId === active.runId) {
        this.active = undefined;
      }
    }
  }

  private handleSessionEvent(active: ActiveRun, event: AgentSessionEvent): void {
    if (event.type === "message_update") {
      const update = event.assistantMessageEvent;
      if (update.type === "text_delta" && update.delta) {
        this.emit("chat", {
          sessionKey: active.sessionKey,
          runId: active.runId,
          state: "delta",
          delta: update.delta
        });
      }
      if (update.type === "thinking_delta" && update.delta) {
        this.emit("agent", {
          sessionKey: active.sessionKey,
          runId: active.runId,
          type: "reasoning.delta",
          data: { delta: update.delta, state: "reasoning" }
        });
      }
      return;
    }
    if (event.type === "tool_execution_start") {
      this.emitTool(active, event.toolCallId, event.toolName, "running", { args: event.args });
      return;
    }
    if (event.type === "tool_execution_update") {
      this.emitTool(active, event.toolCallId, event.toolName, "running", { args: event.args, output: event.partialResult });
      return;
    }
    if (event.type === "tool_execution_end") {
      this.emitTool(active, event.toolCallId, event.toolName, event.isError ? "failed" : "completed", {
        output: event.result,
        error: event.isError ? errorMessage(event.result) : undefined
      });
      return;
    }
    if (event.type === "turn_end") {
      const text = textFromPiMessage(event.message);
      if (text) {
        active.finalEmitted = true;
        this.emit("chat", {
          sessionKey: active.sessionKey,
          runId: active.runId,
          state: "final",
          message: text,
          usage: usageFromPiMessage(event.message)
        });
      }
    }
  }

  private emitTool(
    active: ActiveRun,
    eventId: string,
    toolName: string,
    status: "running" | "completed" | "failed",
    data: Record<string, unknown>
  ): void {
    this.emit("agent", {
      sessionKey: active.sessionKey,
      runId: active.runId,
      eventId: `pi_tool_${eventId}`,
      type: "tool",
      data: {
        toolName,
        title: `Pi ${toolName}`,
        status,
        ...data
      }
    });
  }

  private emitFinalFromSession(active: ActiveRun): void {
    const text = latestAssistantText(active.runtime.session.messages);
    if (!text) {
      return;
    }
    this.emit("chat", {
      sessionKey: active.sessionKey,
      runId: active.runId,
      state: "final",
      message: text,
      usage: latestUsage(active.runtime.session.messages)
    });
  }

  private emit(event: string, payload: unknown): void {
    const gatewayEvent: GatewayEvent = { event, payload };
    for (const handler of this.handlers) {
      handler(gatewayEvent);
    }
  }

  private async ensureStoredSession(sessionKey: string, sessionId?: string): Promise<HarnessStoredSession> {
    const session = this.sessions.ensureSession(sessionKey, sessionId ?? piSessionIdFromKey(sessionKey));
    if (!sessionPathForSession(session)) {
      const match = await this.findPiSessionInfo(session.sessionId);
      if (match) {
        this.sessions.setMetadata(session, PI_SESSION_PATH_KEY, match.path);
        this.sessions.setMetadata(session, PI_SESSION_CWD_KEY, match.cwd);
        session.label = match.name ?? firstMessageTitle(match.firstMessage) ?? session.label;
        session.displayName = session.label;
      }
    }
    return session;
  }

  private async ensureRuntime(
    session: HarnessStoredSession,
    options: { model?: string; thinkingLevel?: string | null } = {}
  ): Promise<AgentSessionRuntime> {
    const existing = this.runtimes.get(session.key);
    if (existing) {
      return existing;
    }
    const sessionPath = sessionPathForSession(session);
    const runtime = await this.client.createRuntime({
      cwd: directoryForSession(session) ?? this.client.defaultCwd(),
      sessionPath,
      model: options.model,
      thinkingLevel: options.thinkingLevel
    });
    this.sessions.setSessionId(session, runtime.session.sessionId);
    this.sessions.setMetadata(session, PI_SESSION_PATH_KEY, runtime.session.sessionFile);
    this.sessions.setMetadata(session, PI_SESSION_CWD_KEY, runtime.cwd);
    this.runtimes.set(session.key, runtime);
    return runtime;
  }

  private async listPiSessions(limit: number): Promise<Record<string, unknown>[]> {
    const current = await SessionManager.list(this.client.defaultCwd()).catch(() => []);
    const all = await SessionManager.listAll().catch(() => []);
    const byPath = new Map<string, SessionInfo>();
    for (const info of [...current, ...all]) {
      byPath.set(info.path, info);
    }
    return [...byPath.values()]
      .filter((info) => info.messageCount > 0)
      .map((info) => this.sessionInfoToSummary(info))
      .sort((left, right) => (numberField(left, "updatedAt") ?? 0) - (numberField(right, "updatedAt") ?? 0))
      .reverse()
      .slice(0, Math.max(1, limit));
  }

  private sessionInfoToSummary(info: SessionInfo): Record<string, unknown> {
    const key = `${PI_SESSION_PREFIX}${info.id}`;
    const local = this.sessions.ensureSession(key, info.id);
    this.sessions.setSessionId(local, info.id);
    this.sessions.setMetadata(local, PI_SESSION_PATH_KEY, info.path);
    this.sessions.setMetadata(local, PI_SESSION_CWD_KEY, info.cwd);
    const model = readPiSessionModel(info.path) ?? local.model ?? PI_DEFAULT_MODEL;
    return {
      key,
      sessionId: info.id,
      label: info.name ?? firstMessageTitle(info.firstMessage) ?? "Pi session",
      displayName: info.name ?? firstMessageTitle(info.firstMessage) ?? "Pi session",
      workspacePath: info.cwd || null,
      workspaceName: workspaceNameFromPath(info.cwd),
      threadPath: info.path,
      preview: info.firstMessage || null,
      source: "pi",
      model,
      modelProvider: "pi",
      updatedAt: info.modified.getTime(),
      hasActiveRun: Boolean(local.activeRunId),
      thinkingLevel: local.thinkingLevel ?? null
    };
  }

  private async findPiSessionInfo(sessionId: string | undefined): Promise<SessionInfo | undefined> {
    if (!sessionId) {
      return undefined;
    }
    const all = await SessionManager.listAll().catch(() => []);
    return all.find((session) => session.id === sessionId);
  }
}

function piSessionIdFromKey(sessionKey: string | undefined | null): string | undefined {
  return sessionKey?.startsWith(PI_SESSION_PREFIX) ? sessionKey.slice(PI_SESSION_PREFIX.length) || undefined : undefined;
}

function sessionPathForSession(session: HarnessStoredSession): string | undefined {
  return stringField(session.metadata, PI_SESSION_PATH_KEY);
}

function directoryForSession(session: HarnessStoredSession): string | undefined {
  return stringField(session.metadata, PI_SESSION_CWD_KEY);
}

function workspaceNameFromPath(path: string | null | undefined): string | null {
  return path ? basename(path) || path : null;
}

function imageAttachments(attachments: ChatAttachment[] | undefined): Array<{ type: "image"; data: string; mimeType: string }> {
  return (attachments ?? [])
    .filter((attachment) => attachment.kind === "image" && Boolean(attachment.contentBase64))
    .map((attachment) => ({
      type: "image" as const,
      data: attachment.contentBase64 ?? "",
      mimeType: attachment.mimeType
    }));
}

function messagesFromPiEntries(entries: SessionEntry[]): Array<Record<string, unknown>> {
  return entries
    .map((entry): Record<string, unknown> | undefined => {
      if (entry.type === "message") {
        const text = textFromPiMessage(entry.message);
        if (!text) {
          return undefined;
        }
        return {
          id: entry.id,
          role: roleFromPiMessage(entry.message),
          text,
          timestamp: Date.parse(entry.timestamp) || null
        };
      }
      if (entry.type === "compaction" || entry.type === "branch_summary") {
        return {
          id: entry.id,
          role: "system",
          text: entry.summary,
          timestamp: Date.parse(entry.timestamp) || null
        };
      }
      if (entry.type === "custom_message") {
        const text = textFromContent(entry.content);
        return text ? {
          id: entry.id,
          role: "system",
          text,
          timestamp: Date.parse(entry.timestamp) || null
        } : undefined;
      }
      return undefined;
    })
    .filter((message): message is Record<string, unknown> => message !== undefined);
}

function readPiSessionModel(sessionPath: string): string | undefined {
  try {
    const manager = SessionManager.open(sessionPath);
    const context = buildSessionContext(manager.getEntries());
    return context.model ? `${context.model.provider}/${context.model.modelId}` : undefined;
  } catch {
    return undefined;
  }
}

function textFromPiMessage(message: unknown): string {
  const record = asRecord(message);
  if (!record) {
    return "";
  }
  if (typeof record.content === "string") {
    return record.content;
  }
  if (Array.isArray(record.content)) {
    return textFromContent(record.content);
  }
  return stringField(record, "text") ?? stringField(record, "summary") ?? "";
}

function textFromContent(content: unknown): string {
  if (typeof content === "string") {
    return content;
  }
  if (!Array.isArray(content)) {
    return "";
  }
  return content
    .map((item) => {
      const record = asRecord(item);
      if (!record) {
        return "";
      }
      const type = stringField(record, "type");
      if (type === "text") {
        return stringField(record, "text") ?? "";
      }
      if (type === "toolCall") {
        return `Using ${stringField(record, "name") ?? "tool"}`;
      }
      return "";
    })
    .filter(Boolean)
    .join("\n");
}

function roleFromPiMessage(message: unknown): string {
  const role = stringField(asRecord(message), "role");
  return role === "toolResult" ? "tool" : role ?? "assistant";
}

function latestAssistantText(messages: readonly unknown[]): string {
  for (const message of [...messages].reverse()) {
    if (roleFromPiMessage(message) === "assistant") {
      const text = textFromPiMessage(message);
      if (text) {
        return text;
      }
    }
  }
  return "";
}

function latestUsage(messages: readonly unknown[]): Record<string, unknown> | undefined {
  for (const message of [...messages].reverse()) {
    const usage = asRecord(asRecord(message)?.usage);
    if (!usage) {
      continue;
    }
    const inputTokens = numberField(usage, "input");
    const outputTokens = numberField(usage, "output");
    const totalTokens = numberField(usage, "totalTokens")
      ?? [inputTokens, outputTokens, numberField(usage, "cacheRead"), numberField(usage, "cacheWrite")]
        .filter((value): value is number => value !== undefined)
        .reduce((sum, value) => sum + value, 0);
    return { inputTokens, outputTokens, totalTokens };
  }
  return undefined;
}

function usageFromPiMessage(message: unknown): Record<string, unknown> | undefined {
  return latestUsage([message]);
}

function firstMessageTitle(text: string | undefined): string | undefined {
  const clean = text?.trim();
  if (!clean) {
    return undefined;
  }
  return clean.length > 80 ? `${clean.slice(0, 77)}...` : clean;
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" ? value as Record<string, unknown> : undefined;
}

function stringField(record: Record<string, unknown> | undefined, key: string): string | undefined {
  const value = record?.[key];
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function numberField(record: Record<string, unknown> | undefined, key: string): number | undefined {
  const value = record?.[key];
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function errorMessage(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }
  if (typeof error === "string") {
    return error;
  }
  try {
    return JSON.stringify(error);
  } catch {
    return String(error);
  }
}
