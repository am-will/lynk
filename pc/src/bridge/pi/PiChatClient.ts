import { randomUUID } from "node:crypto";
import { join } from "node:path";
import { createHostPaths, ownedPath } from "../../host/HostPaths.js";
import { basename } from "node:path";
import {
  SessionManager,
  type AgentSessionEvent,
  type AgentSessionRuntime,
  type SessionInfo
} from "@earendil-works/pi-coding-agent";
import type { AuditLog } from "../AuditLog.js";
import type { ResolvedChatAttachment } from "../../attachments/AttachmentTypes.js";
import { inlineCompatibilityAttachment } from "../../attachments/AttachmentCompatibility.js";
import type { GatewayChatSendResult, GatewayEvent, GatewayEventHandler } from "../chat/ChatTransportTypes.js";
import { DEFAULT_REASONING_OPTIONS } from "../chat/ModelCatalog.js";
import { InMemoryHarnessSessionStore, type HarnessStoredSession } from "../harness/InMemoryHarnessSessionStore.js";
import { HarnessRunLifecycle, type HarnessActiveRun } from "../harness/HarnessRunLifecycle.js";
import {
  firstMessageTitle,
  latestAssistantText,
  latestUsage,
  messagesFromPiEntries,
  readPiSessionModel,
  textFromPiMessage,
  usageFromPiMessage
} from "./PiHistoryNormalizer.js";
import { PiSdkClient, piModelId, type PiModel, type PiThinkingLevel } from "./PiSdkClient.js";
import { preparePiWorkspace } from "./PiWorkspace.js";
import { AdapterFailure } from "../harness/AdapterFailure.js";

interface PiRunResource {
  runtime: AgentSessionRuntime;
  unsubscribe?: () => void;
  finalEmitted: boolean;
}

type ActiveRun = HarnessActiveRun<PiRunResource>;

export interface PiClientLike {
  defaultCwd(): string;
  listModels(): PiModel[];
  createRuntime(options?: { cwd?: string; sessionPath?: string; model?: string; thinkingLevel?: string | null }): Promise<AgentSessionRuntime>;
  runWithTimeout<T>(label: string, run: () => Promise<T>, onTimeout?: () => Promise<void>): Promise<T>;
  abort(runtime: AgentSessionRuntime | undefined): Promise<void>;
  close(runtime?: AgentSessionRuntime): Promise<void>;
  health(): Promise<Record<string, unknown>>;
  findModel(selection: string | undefined | null): PiModel | undefined;
  normalizeThinkingLevel(level: string | null | undefined): PiThinkingLevel;
}

const PI_SESSION_PREFIX = "pi:";
const PI_SESSION_PATH_KEY = "piSessionPath";
const PI_SESSION_CWD_KEY = "piCwd";
const PI_DEFAULT_MODEL = "anthropic/claude-sonnet-4-5";
const PI_REASONING_OPTION_IDS = ["minimal", ...DEFAULT_REASONING_OPTIONS.map((option) => option.id)];

export class PiChatClient {
  private readonly client: PiClientLike;
  private readonly sessions: InMemoryHarnessSessionStore;
  private readonly handlers = new Set<GatewayEventHandler>();
  private readonly runtimes = new Map<string, AgentSessionRuntime>();
  private readonly runs: HarnessRunLifecycle<PiRunResource>;

  constructor(
    private readonly audit?: AuditLog,
    client?: PiClientLike,
    sessionStoragePath: string | null = ownedPath(createHostPaths().sessionsRoot, "pi-sessions.json"),
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
      legacyStoragePaths: [join(process.cwd(), "state", "pi-sessions.json")],
      persistEmptySessions: false
    });
    this.runs = new HarnessRunLifecycle(this.sessions, {
      concurrency: "per-session",
      busyMessage: "A Pi task is already running for this session"
    });
  }

  addEventListener(handler: GatewayEventHandler): () => void {
    this.handlers.add(handler);
    return () => this.handlers.delete(handler);
  }

  async history(sessionKey: string): Promise<unknown> {
    const session = await this.resolveStoredSession(sessionKey);
    if (!session) {
      throw new AdapterFailure("not_found", `Pi session not found: ${sessionKey}`, {
        harnessId: "pi",
        operation: "history"
      });
    }
    const sessionPath = sessionPathForSession(session);
    if (sessionPath) {
      const manager = SessionManager.open(sessionPath, undefined, directoryForSession(session));
      return {
        sessionId: manager.getSessionId(),
        thinkingLevel: this.sessions.ensureSession(sessionKey, manager.getSessionId()).thinkingLevel ?? manager.buildSessionContext().thinkingLevel,
        messages: messagesFromPiEntries(manager.getEntries())
      };
    }
    return this.sessions.history(session.key);
  }

  async sendChat(options: {
    sessionKey: string;
    sessionId?: string;
    message: string;
    attachments?: ResolvedChatAttachment[];
    thinking?: string;
    idempotencyKey?: string;
  }): Promise<GatewayChatSendResult> {
    const existingControls = this.storedSessionForKey(options.sessionKey);
    const session = await this.resolveStoredSession(options.sessionKey, options.sessionId)
      ?? (await this.createRuntimeBackedSession({
        model: existingControls?.model,
        thinkingLevel: existingControls?.thinkingLevel ?? options.thinking ?? "medium"
      })).session;
    this.runs.assertCanStart(session.key);
    this.sessions.setThinkingLevel(session, options.thinking ?? "medium");
    const runtime = await this.ensureRuntime(session, {
      model: session.model ?? undefined,
      thinkingLevel: session.thinkingLevel ?? options.thinking ?? "medium"
    });

    const runId = options.idempotencyKey ?? `pi_${randomUUID()}`;
    const resource: PiRunResource = { runtime, finalEmitted: false };
    const active = this.runs.start(session, runId, resource, () => resource.unsubscribe?.());
    resource.unsubscribe = runtime.session.subscribe((event) => this.handleSessionEvent(active, event));
    void this.processRun(active, options.message, options.attachments);
    return { runId, sessionKey: session.key };
  }

  async steerChat(options: {
    sessionKey: string;
    runId?: string;
    message: string;
    attachments?: ResolvedChatAttachment[];
  }): Promise<GatewayChatSendResult> {
    const active = this.activeFor(options.sessionKey, options.runId);
    if (!active) {
      throw new Error("No active Pi task is running for this session");
    }
    await active.resource.runtime.session.steer(options.message, imageAttachments(options.attachments));
    return { runId: active.runId, sessionKey: active.sessionKey };
  }

  async abort(sessionKey: string, runId?: string): Promise<unknown> {
    const active = this.activeFor(sessionKey, runId);
    if (!active) {
      return {};
    }
    await this.client.abort(active.resource.runtime);
    this.emit("chat", {
      sessionKey: active.sessionKey,
      runId: active.runId,
      state: "error",
      error: "Pi run stopped."
    });
    this.clearActiveRun(active);
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
        reasoningOptions: model.reasoning ? PI_REASONING_OPTION_IDS : undefined,
        defaultReasoningEffort: model.reasoning ? "medium" : undefined
      }))
    };
  }

  async listSessions(limit = 50): Promise<unknown> {
    const sessions = await this.listPiSessions(limit);
    return {
      sessions,
      defaults: {
        thinkingLevels: PI_REASONING_OPTION_IDS
      }
    };
  }

  async createSession(options: { key?: string; label?: string; model?: string; workspacePath?: string; createWorkspaceIfMissing?: boolean }): Promise<unknown> {
    const { session: local, runtime } = await this.createRuntimeBackedSession({
      label: options.label,
      model: options.model,
      workspacePath: options.workspacePath,
      createWorkspaceIfMissing: options.createWorkspaceIfMissing,
      thinkingLevel: "medium"
    });
    return {
      key: local.key,
      sessionId: runtime.session.sessionId,
      label: local.label,
      displayName: local.displayName,
      workspacePath: runtime.cwd,
      workspaceName: workspaceNameFromPath(runtime.cwd)
    };
  }

  async patchSession(sessionKey: string, patch: Record<string, unknown>): Promise<unknown> {
    const session = this.sessions.ensureSession(sessionKey);
    const runtime = this.runtimes.get(sessionKey);
    if (runtime && typeof patch.model === "string") {
      await runtime.session.setModel(this.client.findModel(patch.model) ?? runtime.session.model as PiModel);
    }
    if (runtime && typeof patch.thinking === "string") {
      runtime.session.setThinkingLevel(this.client.normalizeThinkingLevel(patch.thinking));
    }
    this.sessions.patchSession(sessionKey, patch);
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
    this.runs.close();
    void this.sessions.close().catch((error) => console.warn(`[pi] session persistence flush failed: ${String(error)}`));
  }

  async flushPersistence(): Promise<void> {
    await this.sessions.flushPersistence();
  }

  private async processRun(active: ActiveRun, message: string, attachments?: ResolvedChatAttachment[]): Promise<void> {
    try {
      await this.client.runWithTimeout(
        "Pi run",
        () => active.resource.runtime.session.prompt(message, { images: imageAttachments(attachments) }),
        () => active.resource.runtime.session.abort()
      );
      if (!active.resource.finalEmitted) {
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
      this.clearActiveRun(active);
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
        active.resource.finalEmitted = true;
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
    const text = latestAssistantText(active.resource.runtime.session.messages);
    if (!text) {
      return;
    }
    this.emit("chat", {
      sessionKey: active.sessionKey,
      runId: active.runId,
      state: "final",
      message: text,
      usage: latestUsage(active.resource.runtime.session.messages)
    });
  }

  private emit(event: string, payload: unknown): void {
    const gatewayEvent: GatewayEvent = { event, payload };
    for (const handler of this.handlers) {
      handler(gatewayEvent);
    }
  }

  private activeFor(sessionKey: string, runId?: string): ActiveRun | undefined {
    return this.runs.activeFor(sessionKey, runId);
  }

  private clearActiveRun(active: ActiveRun): void {
    this.runs.clear(active);
  }

  private async resolveStoredSession(sessionKey: string, sessionId?: string): Promise<HarnessStoredSession | undefined> {
    const session = this.storedSessionForKey(sessionKey)
      ?? this.storedSessionForId(sessionId ?? piSessionIdFromKey(sessionKey));
    if (session) {
      return await this.hydrateStoredSession(session);
    }
    const match = await this.findPiSessionInfo(sessionId ?? piSessionIdFromKey(sessionKey));
    return match ? this.storeSessionInfo(match) : undefined;
  }

  private async hydrateStoredSession(session: HarnessStoredSession): Promise<HarnessStoredSession | undefined> {
    if (!sessionPathForSession(session)) {
      const match = await this.findPiSessionInfo(session.sessionId);
      if (match) {
        return this.storeSessionInfo(match, session);
      }
      return undefined;
    }
    return session;
  }

  private async createRuntimeBackedSession(options: {
    label?: string;
    model?: string;
    workspacePath?: string;
    createWorkspaceIfMissing?: boolean;
    thinkingLevel?: string | null;
  }): Promise<{ session: HarnessStoredSession; runtime: AgentSessionRuntime }> {
    const cwd = preparePiWorkspace(options.workspacePath, options.createWorkspaceIfMissing === true) ?? this.client.defaultCwd();
    const runtime = await this.client.createRuntime({
      cwd,
      model: options.model,
      thinkingLevel: options.thinkingLevel ?? "medium"
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
    return { session: local, runtime };
  }

  private async ensureRuntime(
    session: HarnessStoredSession,
    options: { model?: string; thinkingLevel?: string | null } = {}
  ): Promise<AgentSessionRuntime> {
    const existing = this.runtimes.get(session.key);
    if (existing) {
      if (options.model) {
        await existing.session.setModel(this.client.findModel(options.model) ?? existing.session.model as PiModel);
      }
      if (options.thinkingLevel) {
        existing.session.setThinkingLevel(this.client.normalizeThinkingLevel(options.thinkingLevel));
      }
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
    const local = this.storeSessionInfo(info);
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

  private storeSessionInfo(info: SessionInfo, session = this.sessions.ensureSession(`${PI_SESSION_PREFIX}${info.id}`, info.id)): HarnessStoredSession {
    this.sessions.setSessionId(session, info.id);
    this.sessions.setMetadata(session, PI_SESSION_PATH_KEY, info.path);
    this.sessions.setMetadata(session, PI_SESSION_CWD_KEY, info.cwd);
    session.label = info.name ?? firstMessageTitle(info.firstMessage) ?? session.label;
    session.displayName = session.label;
    return session;
  }

  private storedSessionForKey(sessionKey: string | undefined): HarnessStoredSession | undefined {
    return this.sessions.listStoredSessions(Number.MAX_SAFE_INTEGER)
      .find((session) => session.key === sessionKey);
  }

  private storedSessionForId(sessionId: string | undefined): HarnessStoredSession | undefined {
    return sessionId ? this.sessions.listStoredSessions(Number.MAX_SAFE_INTEGER)
      .find((session) => session.sessionId === sessionId) : undefined;
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

function imageAttachments(attachments: ResolvedChatAttachment[] | undefined): Array<{ type: "image"; data: string; mimeType: string }> {
  return (attachments ?? [])
    .filter((attachment) => attachment.kind === "image")
    .map(inlineCompatibilityAttachment)
    .map((attachment) => ({
      type: "image" as const,
      data: attachment.contentBase64,
      mimeType: attachment.mimeType
    }));
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
