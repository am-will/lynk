import { createHash, randomUUID } from "node:crypto";
import { join } from "node:path";
import type { AuditLog } from "./AuditLog.js";
import type { AgentRunResult, AgentStatusSink } from "../dispatcher/AgentClient.js";
import { CodexAppServerClient } from "../dispatcher/CodexAppServerClient.js";
import { PHONE_AGENT_SYSTEM_PROMPT } from "../dispatcher/promptPolicy.js";
import { codexAppServerContextWindow, DEFAULT_REASONING_OPTIONS } from "./chat/ModelCatalog.js";
import { InMemoryHarnessSessionStore, type HarnessStoredSession } from "./harness/InMemoryHarnessSessionStore.js";
import type { GatewayChatSendResult, GatewayEvent, GatewayEventHandler } from "./chat/ChatTransportTypes.js";
import { prepareCodexWorkspace } from "./codex/CodexWorkspace.js";
import {
  CODEX_THREAD_MAX_LIST,
  CODEX_THREAD_PAGE_SIZE,
  CODEX_THREAD_SESSION_KEY_PREFIX,
  type CodexThreadRecord,
  chatMessagesFromCodexThreadRead,
  codexThreadDisplayName,
  codexThreadIdFromSessionKey,
  normalizeCodexThread,
  normalizeCodexThreadList,
  secondsToMillis,
  workspaceNameFromPath
} from "./codex/CodexThreads.js";

interface ActiveRun {
  sessionKey: string;
  runId: string;
}

const CODEX_BASE_INSTRUCTIONS_FINGERPRINT_KEY = "codexBaseInstructionsFingerprint";
const LEGACY_CODEX_BASE_INSTRUCTIONS_BOUND_KEY = "codexBaseInstructionsBound";
const CODEX_THREAD_CWD_KEY = "codexThreadCwd";
const CODEX_THREAD_PATH_KEY = "codexThreadPath";

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" ? value as Record<string, unknown> : undefined;
}

function stringField(record: Record<string, unknown> | undefined, key: string): string | undefined {
  const value = record?.[key];
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function arrayField(record: Record<string, unknown> | undefined, key: string): unknown[] {
  const value = record?.[key];
  return Array.isArray(value) ? value : [];
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

export class CodexChatClient {
  private readonly client: CodexAppServerClient;
  private readonly sessions: InMemoryHarnessSessionStore;
  private readonly handlers = new Set<GatewayEventHandler>();
  private readonly threadsById = new Map<string, CodexThreadRecord>();
  private active?: ActiveRun;

  constructor(
    private readonly audit?: AuditLog,
    client?: CodexAppServerClient,
    sessionStoragePath: string | null = join(process.cwd(), "state", "codex-sessions.json"),
    options: { command?: string; cwd?: string; approvalPolicy?: string; sandbox?: string } = {}
  ) {
    this.client = client ?? new CodexAppServerClient(audit, options.command, options.cwd, options.approvalPolicy, options.sandbox);
    this.sessions = new InMemoryHarnessSessionStore("codex", {
      defaultModel: "gpt-5.3-codex",
      modelProvider: "codex",
      storagePath: sessionStoragePath ?? undefined
    });
  }

  addEventListener(handler: GatewayEventHandler): () => void {
    this.handlers.add(handler);
    return () => this.handlers.delete(handler);
  }

  async history(sessionKey: string): Promise<unknown> {
    const threadId = codexThreadIdFromSessionKey(sessionKey);
    if (threadId) {
      const payload = await this.client.readThread(threadId, true);
      const thread = normalizeCodexThread(asRecord(payload)?.thread);
      if (thread) {
        this.rememberThread(thread);
      }
      return {
        sessionId: thread?.id ?? threadId,
        messages: chatMessagesFromCodexThreadRead(payload)
      };
    }
    return this.sessions.history(sessionKey);
  }

  async sendChat(options: {
    sessionKey: string;
    sessionId?: string;
    message: string;
    thinking?: string;
    idempotencyKey?: string;
  }): Promise<GatewayChatSendResult> {
    if (this.active) {
      throw new Error("A Codex task is already running");
    }
    const session = this.sessions.ensureSession(options.sessionKey, options.sessionId);
    const threadId = codexThreadIdFromSessionKey(session.key) ?? session.sessionId;
    this.sessions.setThinkingLevel(session, options.thinking);
    await this.ensureAppServerThread(session);
    this.sessions.appendUserMessage(session, options.message, options.idempotencyKey);
    this.audit?.record("codex_chat_prompt_metrics", undefined, {
      path: "bridge.sendChat",
      sessionKey: session.key,
      sessionId: session.sessionId,
      message: promptMetrics(options.message)
    });

    const runId = options.idempotencyKey ?? `codex_${randomUUID()}`;
    this.sessions.setActiveRun(session, runId);
    this.active = { sessionKey: session.key, runId };
    void this.processRun(session, runId, options.message, session.model, session.thinkingLevel, threadCwdForSession(session, this.threadsById.get(threadId)));
    return { runId, sessionKey: session.key };
  }

  async abort(_sessionKey: string, runId?: string): Promise<unknown> {
    if (!this.active || (runId && this.active.runId !== runId)) {
      return {};
    }
    await this.client.interrupt?.("Stopped from Android chat");
    this.emit("chat", {
      sessionKey: this.active.sessionKey,
      runId: this.active.runId,
      state: "error",
      error: "Codex run stopped."
    });
    return { status: "stopping" };
  }

  async steerChat(options: {
    sessionKey: string;
    sessionId?: string;
    runId?: string;
    message: string;
    thinking?: string;
    idempotencyKey?: string;
  }): Promise<GatewayChatSendResult> {
    if (!this.active) {
      throw new Error("No active Codex turn to steer");
    }
    if (this.active.sessionKey !== options.sessionKey || (options.runId && this.active.runId !== options.runId)) {
      throw new Error("Active Codex turn does not match the requested steer target");
    }
    const session = this.sessions.ensureSession(options.sessionKey, options.sessionId);
    this.sessions.appendUserMessage(session, options.message, options.idempotencyKey);
    await this.client.steer(options.message);
    return { runId: this.active.runId, sessionKey: this.active.sessionKey };
  }

  async listModels(): Promise<unknown> {
    const payload = await this.client.listModels().catch(() => undefined);
    const rawModels = Array.isArray(asRecord(payload)?.data)
      ? asRecord(payload)?.data as unknown[]
      : Array.isArray(asRecord(payload)?.models)
        ? asRecord(payload)?.models as unknown[]
        : [];
    if (rawModels.length > 0) {
      const models = await Promise.all(rawModels.map((item) => this.normalizeCodexModel(item)));
      return {
        models: models.filter(Boolean),
        defaults: {
          thinkingLevels: ["low", "medium", "high", "xhigh"]
        }
      };
    }
    return {
      models: [
        codexFallbackModel("gpt-5.5", "GPT-5.5", "medium"),
        codexFallbackModel("gpt-5.4", "gpt-5.4", "medium"),
        codexFallbackModel("gpt-5.4-mini", "GPT-5.4-Mini", "medium"),
        codexFallbackModel("gpt-5.3-codex", "gpt-5.3-codex", "medium"),
        codexFallbackModel("gpt-5.3-codex-spark", "GPT-5.3-Codex-Spark", "high")
      ],
      defaults: {
        thinkingLevels: ["low", "medium", "high", "xhigh"]
      }
    };
  }

  async listSessions(limit = 50): Promise<unknown> {
    const threads = await this.listCodexThreads(codexThreadListLimit(limit));
    if (threads.length > 0) {
      for (const thread of threads) {
        this.rememberThread(thread);
      }
      return {
        sessions: threads.map((thread) => this.threadToSessionSummary(thread)),
        defaults: {
          thinkingLevels: ["low", "medium", "high", "xhigh"]
        }
      };
    }

    return {
      sessions: this.sessions.listSessions(limit),
      defaults: {
        thinkingLevels: ["low", "medium", "high", "xhigh"]
      }
    };
  }

  async createSession(options: { key?: string; label?: string; model?: string; workspacePath?: string; createWorkspaceIfMissing?: boolean }): Promise<unknown> {
    const workspacePath = prepareCodexWorkspace(options.workspacePath, options.createWorkspaceIfMissing === true);
    const threadId = await this.client.createThread({
      model: options.model,
      baseInstructions: PHONE_AGENT_SYSTEM_PROMPT,
      ...(workspacePath ? { cwd: workspacePath } : {})
    });
    const key = `${CODEX_THREAD_SESSION_KEY_PREFIX}${threadId}`;
    const created = this.sessions.createSession({
      key,
      label: options.label ?? threadId,
      model: options.model
    });
    const session = this.sessions.ensureSession(key, threadId);
    this.sessions.setSessionId(session, threadId);
    this.sessions.setMetadata(session, CODEX_BASE_INSTRUCTIONS_FINGERPRINT_KEY, promptFingerprint(PHONE_AGENT_SYSTEM_PROMPT));
    if (workspacePath) {
      this.sessions.setMetadata(session, CODEX_THREAD_CWD_KEY, workspacePath);
    }
    return {
      ...created,
      sessionId: session.sessionId,
      workspacePath: workspacePath ?? null,
      workspaceName: workspaceNameFromPath(workspacePath)
    };
  }

  async patchSession(sessionKey: string, patch: Record<string, unknown>): Promise<unknown> {
    this.sessions.patchSession(sessionKey, patch);
    return { ok: true };
  }

  async listCommands(): Promise<unknown> {
    return {
      commands: [
        { name: "status", description: "Show Codex status", textAliases: ["/status"], acceptsArgs: false },
        { name: "new", description: "Start a new Codex chat", textAliases: ["/new"], acceptsArgs: false },
        { name: "help", description: "Show available Codex commands", textAliases: ["/help"], acceptsArgs: false }
      ]
    };
  }

  async effectiveTools(): Promise<unknown> {
    return { tools: [] };
  }

  async health(): Promise<unknown> {
    try {
      await this.client.listModels();
      return { ok: true, harness: "codex", active: Boolean(this.active) };
    } catch (error) {
      return {
        ok: false,
        harness: "codex",
        active: Boolean(this.active),
        error: error instanceof Error ? error.message : String(error)
      };
    }
  }

  close(): void {
    void this.client.close();
  }

  private async processRun(
    session: HarnessStoredSession,
    runId: string,
    text: string,
    model: string | undefined,
    reasoningEffort: string | undefined,
    cwd: string | undefined
  ): Promise<void> {
    try {
      const threadId = codexThreadIdFromSessionKey(session.key) ?? session.sessionId;
      const result = await this.client.submitUserRequest(text, this.statusSink(session.key, runId), {
        threadId,
        cwd,
        systemPrompt: PHONE_AGENT_SYSTEM_PROMPT,
        model,
        reasoningEffort,
        taskKind: "general",
        useSessionInstructions: Boolean(codexThreadIdFromSessionKey(session.key)) || codexBaseInstructionsBound(session)
      });
      if (result.threadId) {
        this.sessions.setSessionId(session, result.threadId);
      }
      const finalText = finalTextFromResult(result);
      this.sessions.upsertAssistantMessage(session, runId, finalText);
      this.sessions.setUsage(session, result.usage);
      this.emit("chat", {
        sessionKey: session.key,
        runId,
        state: "final",
        message: finalText
      });
    } catch (error) {
      this.emit("chat", {
        sessionKey: session.key,
        runId,
        state: "error",
        error: error instanceof Error ? error.message : String(error)
      });
    } finally {
      if (this.active?.runId === runId) {
        this.active = undefined;
      }
      this.sessions.clearActiveRun(session, runId);
    }
  }

  private statusSink(sessionKey: string, runId: string): AgentStatusSink {
    return {
      info: (text) => this.emitToolInfo(sessionKey, runId, "info", text),
      working: (text) => this.emitToolInfo(sessionKey, runId, "running", text),
      tool: (text) => this.emitToolInfo(sessionKey, runId, "running", text),
      done: () => undefined,
      error: (text) => this.emitToolInfo(sessionKey, runId, "failed", text)
    };
  }

  private emitToolInfo(sessionKey: string, runId: string, status: string, text: string): void {
    this.emit("agent", {
      sessionKey,
      runId,
      type: "tool",
      toolName: "codex",
      status,
      data: { message: text }
    });
  }

  private emit(event: string, payload: unknown): void {
    const gatewayEvent: GatewayEvent = { event, payload };
    for (const handler of this.handlers) {
      handler(gatewayEvent);
    }
  }

  private async normalizeCodexModel(item: unknown): Promise<Record<string, unknown> | undefined> {
    const record = asRecord(item);
    const id = stringField(record, "id") ?? stringField(record, "model");
    if (!id || record?.hidden === true) {
      return undefined;
    }
    const provider = stringField(record, "modelProvider") ?? stringField(record, "provider") ?? "openai";
    const capabilities = await this.client.readModelProviderCapabilities({ model: id, provider }).catch(() => undefined);
    return {
      id,
      key: id,
      name: stringField(record, "displayName") ?? stringField(record, "name") ?? id,
      provider: "codex",
      contextWindow: contextWindowFromPayload(record)
        ?? contextWindowFromPayload(capabilities)
        ?? codexAppServerContextWindow(id),
      available: true,
      reasoningOptions: arrayField(record, "supportedReasoningEfforts"),
      defaultReasoningEffort: stringField(record, "defaultReasoningEffort")
    };
  }

  private async ensureAppServerThread(session: HarnessStoredSession, model?: string): Promise<void> {
    if (codexThreadIdFromSessionKey(session.key)) {
      this.sessions.setSessionId(session, codexThreadIdFromSessionKey(session.key)!);
      return;
    }
    if (!isLocalCodexSessionId(session.key, session.sessionId)) {
      return;
    }
    const threadId = await this.client.createThread({
      model: model ?? session.model,
      baseInstructions: PHONE_AGENT_SYSTEM_PROMPT
    });
    this.sessions.setSessionId(session, threadId);
    this.sessions.setMetadata(session, CODEX_BASE_INSTRUCTIONS_FINGERPRINT_KEY, promptFingerprint(PHONE_AGENT_SYSTEM_PROMPT));
  }

  private async listCodexThreads(maxThreads: number): Promise<CodexThreadRecord[]> {
    const threads: CodexThreadRecord[] = [];
    let cursor: string | undefined;
    while (threads.length < maxThreads) {
      const payload = await this.client.listThreads({
        limit: Math.min(CODEX_THREAD_PAGE_SIZE, maxThreads - threads.length),
        ...(cursor ? { cursor } : {})
      }).catch((error) => {
        this.audit?.record("codex_thread_list_error", undefined, {
          error: error instanceof Error ? error.message : String(error)
        });
        return undefined;
      });
      const page = normalizeCodexThreadList(payload);
      if (page.length === 0) {
        break;
      }
      threads.push(...page);
      cursor = stringField(asRecord(payload), "nextCursor");
      if (!cursor) {
        break;
      }
    }
    return threads;
  }

  private rememberThread(thread: CodexThreadRecord): void {
    this.threadsById.set(thread.id, thread);
    const key = `${CODEX_THREAD_SESSION_KEY_PREFIX}${thread.id}`;
    const session = this.sessions.ensureSession(key, thread.id);
    this.sessions.setSessionId(session, thread.id);
    if (thread.cwd) {
      this.sessions.setMetadata(session, CODEX_THREAD_CWD_KEY, thread.cwd);
    }
    if (thread.path) {
      this.sessions.setMetadata(session, CODEX_THREAD_PATH_KEY, thread.path);
    }
  }

  private threadToSessionSummary(thread: CodexThreadRecord): Record<string, unknown> {
    const displayName = codexThreadDisplayName(thread);
    const workspacePath = thread.hasWorkspace ? thread.cwd ?? null : null;
    return {
      key: `${CODEX_THREAD_SESSION_KEY_PREFIX}${thread.id}`,
      sessionId: thread.id,
      label: displayName,
      displayName,
      workspacePath,
      workspaceName: workspaceNameFromPath(workspacePath),
      threadPath: thread.path ?? null,
      preview: thread.preview ?? null,
      source: thread.source ?? null,
      model: this.sessions.ensureSession(`${CODEX_THREAD_SESSION_KEY_PREFIX}${thread.id}`, thread.id).model ?? "gpt-5.3-codex",
      modelProvider: thread.modelProvider ?? "codex",
      updatedAt: secondsToMillis(thread.updatedAt ?? thread.createdAt),
      hasActiveRun: false,
      thinkingLevel: null
    };
  }
}

function finalTextFromResult(result: AgentRunResult): string {
  return result.finalMessage || result.error || "";
}

function isLocalCodexSessionId(sessionKey: string, sessionId: string): boolean {
  if (codexThreadIdFromSessionKey(sessionKey)) {
    return false;
  }
  const localSessionId = sessionKey
    .replace(/^codex:/, "")
    .replace(/[^a-zA-Z0-9_.:-]+/g, "-")
    .replace(/^-+|-+$/g, "") || "default";
  return sessionId === localSessionId;
}

function codexBaseInstructionsBound(session: HarnessStoredSession): boolean {
  const metadata = session.metadata ?? {};
  return metadata[CODEX_BASE_INSTRUCTIONS_FINGERPRINT_KEY] === promptFingerprint(PHONE_AGENT_SYSTEM_PROMPT)
    || metadata[LEGACY_CODEX_BASE_INSTRUCTIONS_BOUND_KEY] === true;
}

function promptFingerprint(prompt: string): string {
  return createHash("sha256").update(prompt).digest("hex");
}

function threadCwdForSession(session: HarnessStoredSession, thread?: CodexThreadRecord): string | undefined {
  return thread?.cwd ?? stringField(session.metadata, CODEX_THREAD_CWD_KEY);
}

function codexThreadListLimit(limit: number): number {
  if (!Number.isFinite(limit)) {
    return CODEX_THREAD_PAGE_SIZE;
  }
  return Math.min(Math.max(Math.trunc(limit), 1), CODEX_THREAD_MAX_LIST);
}

function codexFallbackModel(id: string, name: string, defaultReasoningEffort: string): Record<string, unknown> {
  return {
    id,
    key: id,
    name,
    provider: "codex",
    contextWindow: codexAppServerContextWindow(id),
    available: true,
    reasoningOptions: DEFAULT_REASONING_OPTIONS.map((option) => option.id),
    defaultReasoningEffort
  };
}

function contextWindowFromPayload(value: unknown, depth = 0): number | undefined {
  if (depth > 3) {
    return undefined;
  }
  const record = asRecord(value);
  if (!record) {
    return undefined;
  }
  const direct = firstPositiveNumberField(record, [
    "contextWindow",
    "context_window",
    "contextLength",
    "context_length",
    "maxContextTokens",
    "max_context_tokens",
    "maxInputTokens",
    "max_input_tokens",
    "inputTokenLimit",
    "input_token_limit"
  ]);
  if (direct !== undefined) {
    return direct;
  }
  for (const nested of Object.values(record)) {
    const found = contextWindowFromPayload(nested, depth + 1);
    if (found !== undefined) {
      return found;
    }
  }
  return undefined;
}

function firstPositiveNumberField(record: Record<string, unknown>, keys: string[]): number | undefined {
  for (const key of keys) {
    const value = record[key];
    if (typeof value === "number" && Number.isFinite(value) && value > 0) {
      return value;
    }
  }
  return undefined;
}
