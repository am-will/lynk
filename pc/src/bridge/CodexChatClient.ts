import { randomUUID } from "node:crypto";
import { join } from "node:path";
import type { AuditLog } from "./AuditLog.js";
import type { AgentRunResult, AgentStatusSink } from "../dispatcher/AgentClient.js";
import { CodexAppServerClient } from "../dispatcher/CodexAppServerClient.js";
import { InMemoryHarnessSessionStore, type HarnessStoredSession } from "./harness/InMemoryHarnessSessionStore.js";
import type { GatewayChatSendResult, GatewayEvent, GatewayEventHandler } from "./OpenClawGatewayChatClient.js";

interface ActiveRun {
  sessionKey: string;
  runId: string;
}

const CODEX_CONTEXT_WINDOWS: Record<string, number> = {
  "gpt-5.5": 400_000,
  "gpt-5.4": 400_000,
  "gpt-5.4-mini": 400_000,
  "gpt-5.3-codex": 400_000,
  "gpt-5.3-codex-spark": 400_000,
  "gpt-5.2": 400_000,
  "gpt-5.2-codex": 400_000,
  "gpt-5.1-codex-max": 400_000,
  "gpt-5.1-codex-mini": 400_000
};

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
  private active?: ActiveRun;

  constructor(private readonly audit?: AuditLog, client?: CodexAppServerClient, sessionStoragePath: string | null = join(process.cwd(), "state", "codex-sessions.json")) {
    this.client = client ?? new CodexAppServerClient(audit);
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
    void this.processRun(session, runId, options.message, session.model, session.thinkingLevel);
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
    return {
      sessions: this.sessions.listSessions(limit),
      defaults: {
        thinkingLevels: ["low", "medium", "high", "xhigh"]
      }
    };
  }

  async createSession(options: { key?: string; label?: string; model?: string }): Promise<unknown> {
    const created = this.sessions.createSession(options);
    const key = created.key ?? options.key;
    if (!key) {
      return created;
    }
    const session = this.sessions.ensureSession(key);
    await this.ensureAppServerThread(session, options.model);
    return {
      ...created,
      sessionId: session.sessionId
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
    return { ok: true, harness: "codex", active: Boolean(this.active) };
  }

  close(): void {
    void this.client.close();
  }

  private async processRun(
    session: HarnessStoredSession,
    runId: string,
    text: string,
    model: string | undefined,
    reasoningEffort: string | undefined
  ): Promise<void> {
    try {
      const result = await this.client.submitUserRequest(text, this.statusSink(session.key, runId), {
        threadId: session.sessionId,
        model,
        reasoningEffort,
        taskKind: "general"
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
        ?? CODEX_CONTEXT_WINDOWS[id],
      available: true,
      reasoningOptions: arrayField(record, "supportedReasoningEfforts"),
      defaultReasoningEffort: stringField(record, "defaultReasoningEffort")
    };
  }

  private async ensureAppServerThread(session: HarnessStoredSession, model?: string): Promise<void> {
    if (!isLocalCodexSessionId(session.key, session.sessionId)) {
      return;
    }
    const threadId = await this.client.createThread({ model: model ?? session.model });
    this.sessions.setSessionId(session, threadId);
  }
}

function finalTextFromResult(result: AgentRunResult): string {
  return result.finalMessage || result.error || "";
}

function isLocalCodexSessionId(sessionKey: string, sessionId: string): boolean {
  const localSessionId = sessionKey
    .replace(/^codex:/, "")
    .replace(/[^a-zA-Z0-9_.:-]+/g, "-")
    .replace(/^-+|-+$/g, "") || "default";
  return sessionId === localSessionId;
}

function codexFallbackModel(id: string, name: string, defaultReasoningEffort: string): Record<string, unknown> {
  return {
    id,
    key: id,
    name,
    provider: "codex",
    contextWindow: CODEX_CONTEXT_WINDOWS[id],
    available: true,
    reasoningOptions: ["low", "medium", "high", "xhigh"],
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

