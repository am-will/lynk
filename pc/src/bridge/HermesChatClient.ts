import { randomUUID } from "node:crypto";
import { HermesApiClient, type HermesSseEvent } from "../dispatcher/HermesApiClient.js";
import type { BridgeConfig } from "./config.js";
import { discoverHermesModels } from "./HermesModelDiscovery.js";
import type { GatewayChatSendResult, GatewayEvent, GatewayEventHandler } from "./OpenClawGatewayChatClient.js";

interface StoredMessage {
  id: string;
  role: "user" | "assistant" | "system";
  text: string;
  timestamp: number;
}

interface StoredSession {
  key: string;
  sessionId: string;
  label: string;
  displayName?: string;
  model?: string;
  thinkingLevel?: string;
  messages: StoredMessage[];
  updatedAt: number;
  activeRunId?: string | null;
  usage?: Record<string, unknown>;
}

interface ActiveChatRun {
  sessionKey: string;
  controller: AbortController;
  cancelled: boolean;
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" ? value as Record<string, unknown> : undefined;
}

function firstStringField(value: unknown, keys: string[]): string | undefined {
  if (typeof value === "string" && value.trim()) {
    return value.trim();
  }
  if (Array.isArray(value)) {
    for (const item of value) {
      const nested = firstStringField(item, keys);
      if (nested) {
        return nested;
      }
    }
    return undefined;
  }
  const record = asRecord(value);
  if (!record) {
    return undefined;
  }
  for (const key of keys) {
    const field = record[key];
    if (typeof field === "string" && field.trim()) {
      return field.trim();
    }
  }
  for (const field of Object.values(record)) {
    const nested = firstStringField(field, keys);
    if (nested) {
      return nested;
    }
  }
  return undefined;
}

function outputText(value: unknown): string | undefined {
  return firstStringField(value, ["output", "final_output", "finalMessage", "message", "text", "content", "delta"]);
}

function eventToolName(value: unknown): string | undefined {
  const record = asRecord(value);
  const nested = asRecord(record?.data) ?? record;
  const field = nested?.toolName ?? nested?.tool ?? nested?.name ?? nested?.function_name;
  return typeof field === "string" && field.trim() ? field.trim() : undefined;
}

function eventStatus(value: unknown): string | undefined {
  const record = asRecord(value);
  const status = record?.status ?? record?.state ?? record?.phase;
  return typeof status === "string" && status.trim() ? status.trim() : undefined;
}

function sanitizeSessionId(value: string): string {
  return value.replace(/[^a-zA-Z0-9_.:-]+/g, "-").replace(/^-+|-+$/g, "") || "default";
}

function mergeHermesModels(primary: unknown[], fallback: unknown[]): unknown[] {
  const seen = new Set<string>();
  const result: unknown[] = [];
  for (const model of [...primary, ...fallback]) {
    const record = asRecord(model);
    const id = firstStringField(record, ["id", "key", "name"]);
    if (!id || seen.has(id)) {
      continue;
    }
    seen.add(id);
    result.push(model);
  }
  return result;
}

export class HermesChatClient {
  private readonly api: HermesApiClient;
  private readonly sessions = new Map<string, StoredSession>();
  private readonly handlers = new Set<GatewayEventHandler>();
  private readonly activeRuns = new Map<string, ActiveChatRun>();

  constructor(private readonly config: BridgeConfig, api?: HermesApiClient) {
    if (!config.hermesApiKey) {
      throw new Error("HERMES_API_KEY is required to use the Hermes harness.");
    }
    this.api = api ?? new HermesApiClient({
      apiBaseUrl: config.hermesApiBaseUrl,
      apiKey: config.hermesApiKey,
      model: config.hermesModel,
      runTimeoutMs: config.hermesRunTimeoutMs
    });
  }

  addEventListener(handler: GatewayEventHandler): () => void {
    this.handlers.add(handler);
    return () => this.handlers.delete(handler);
  }

  async history(sessionKey: string): Promise<unknown> {
    const session = this.ensureSession(sessionKey);
    return {
      sessionId: session.sessionId,
      thinkingLevel: session.thinkingLevel,
      messages: session.messages.map((message) => ({
        id: message.id,
        role: message.role,
        text: message.text,
        timestamp: message.timestamp
      }))
    };
  }

  async sendChat(options: {
    sessionKey: string;
    sessionId?: string;
    message: string;
    thinking?: string;
    idempotencyKey?: string;
  }): Promise<GatewayChatSendResult> {
    const session = this.ensureSession(options.sessionKey, options.sessionId);
    session.thinkingLevel = options.thinking ?? session.thinkingLevel;
    session.messages.push({
      id: `user_${options.idempotencyKey ?? randomUUID()}`,
      role: "user",
      text: options.message,
      timestamp: Date.now()
    });
    session.updatedAt = Date.now();

    const created = await this.api.createRun({
      input: options.message,
      sessionId: session.sessionId,
      model: session.model,
      idempotencyKey: options.idempotencyKey
    });
    const runId = created.runId;
    const controller = new AbortController();
    session.activeRunId = runId;
    this.activeRuns.set(runId, {
      sessionKey: session.key,
      controller,
      cancelled: false
    });
    void this.processRun(session.key, runId, controller);
    return { runId, sessionKey: session.key };
  }

  async abort(_sessionKey: string, runId?: string): Promise<unknown> {
    if (!runId) {
      return {};
    }
    const active = this.activeRuns.get(runId);
    if (active) {
      active.cancelled = true;
      active.controller.abort();
    }
    await this.api.stopRun(runId);
    this.emit("agent", {
      type: "run.cancelled",
      sessionKey: active?.sessionKey ?? _sessionKey,
      runId,
      data: { message: "Hermes run stopped." }
    });
    return { status: "stopping" };
  }

  async listModels(): Promise<unknown> {
    const payload = await this.api.listModels().catch(() => undefined);
    const rawModels = Array.isArray(asRecord(payload)?.data)
      ? asRecord(payload)?.data as unknown[]
      : Array.isArray(asRecord(payload)?.models)
        ? asRecord(payload)?.models as unknown[]
        : [];
    const apiModels = rawModels.length > 0
      ? rawModels.map((item) => {
        const record = asRecord(item);
        const id = firstStringField(record, ["id", "name"]) ?? this.config.hermesModel;
        return {
          id,
          key: id,
          name: firstStringField(record, ["name", "id"]) ?? id,
          provider: "hermes",
          available: true
        };
      })
      : [{
        id: this.config.hermesModel,
        key: this.config.hermesModel,
        name: this.config.hermesModel,
        provider: "hermes",
        available: true
      }];
    const models = mergeHermesModels(discoverHermesModels(this.config.hermesModel), apiModels);
    return { models };
  }

  async listSessions(limit = 50): Promise<unknown> {
    const sessions = [...this.sessions.values()]
      .sort((left, right) => right.updatedAt - left.updatedAt)
      .slice(0, limit)
      .map((session) => ({
        key: session.key,
        sessionId: session.sessionId,
        label: session.label,
        displayName: session.displayName ?? session.label,
        model: session.model ?? this.config.hermesModel,
        modelProvider: "hermes",
        updatedAt: session.updatedAt,
        hasActiveRun: Boolean(session.activeRunId),
        thinkingLevel: session.thinkingLevel ?? null,
        inputTokens: Number(asRecord(session.usage)?.input_tokens ?? asRecord(session.usage)?.inputTokens) || null,
        outputTokens: Number(asRecord(session.usage)?.output_tokens ?? asRecord(session.usage)?.outputTokens) || null,
        totalTokens: Number(asRecord(session.usage)?.total_tokens ?? asRecord(session.usage)?.totalTokens) || null
      }));
    return {
      sessions,
      defaults: {
        thinkingLevels: ["low", "medium", "high", "xhigh"]
      }
    };
  }

  async createSession(options: { key?: string; label?: string; model?: string }): Promise<unknown> {
    const key = options.key?.trim() || `hermes:${randomUUID()}`;
    const session = this.ensureSession(key);
    session.label = options.label?.trim() || session.label;
    session.displayName = options.label?.trim() || session.displayName;
    session.model = options.model?.trim() || session.model;
    session.updatedAt = Date.now();
    return {
      key: session.key,
      sessionId: session.sessionId,
      label: session.label,
      displayName: session.displayName
    };
  }

  async patchSession(sessionKey: string, patch: Record<string, unknown>): Promise<unknown> {
    const session = this.ensureSession(sessionKey);
    if (typeof patch.model === "string" && patch.model.trim()) {
      session.model = patch.model.trim();
    }
    if (typeof patch.thinking === "string" && patch.thinking.trim()) {
      session.thinkingLevel = patch.thinking.trim();
    }
    if (typeof patch.displayName === "string" && patch.displayName.trim()) {
      session.displayName = patch.displayName.trim();
    }
    session.updatedAt = Date.now();
    return { ok: true };
  }

  async listCommands(): Promise<unknown> {
    return {
      commands: [
        { name: "status", description: "Show Hermes status", textAliases: ["/status"], acceptsArgs: false },
        { name: "new", description: "Start a new Hermes chat", textAliases: ["/new"], acceptsArgs: false },
        { name: "help", description: "Show available Hermes commands", textAliases: ["/help"], acceptsArgs: false }
      ]
    };
  }

  async effectiveTools(): Promise<unknown> {
    const capabilities = await this.api.capabilities().catch(() => undefined);
    return { tools: [], capabilities };
  }

  async health(): Promise<unknown> {
    return await this.api.health();
  }

  close(): void {
    for (const active of this.activeRuns.values()) {
      active.controller.abort();
    }
    this.activeRuns.clear();
  }

  private ensureSession(sessionKey: string, sessionId?: string): StoredSession {
    const key = sessionKey.trim() || `hermes:${randomUUID()}`;
    const existing = this.sessions.get(key);
    if (existing) {
      return existing;
    }
    const cleanSessionId = sanitizeSessionId(sessionId ?? key.replace(/^hermes:/, ""));
    const created: StoredSession = {
      key,
      sessionId: cleanSessionId,
      label: cleanSessionId,
      model: this.config.hermesModel,
      messages: [],
      updatedAt: Date.now(),
      activeRunId: null
    };
    this.sessions.set(key, created);
    return created;
  }

  private async processRun(sessionKey: string, runId: string, controller: AbortController): Promise<void> {
    const session = this.ensureSession(sessionKey);
    let latestText = "";
    try {
      await this.api.streamRunEvents(runId, (event) => {
        latestText = this.handleRunEvent(session, runId, event, latestText);
      }, controller.signal);
      const final = await this.api.getRun(runId);
      const error = outputText(final.error);
      if (error) {
        throw new Error(error);
      }
      const finalText = outputText(final.output) ?? outputText(final.raw) ?? latestText;
      if (finalText.trim()) {
        this.upsertAssistantMessage(session, runId, finalText);
      }
      session.activeRunId = null;
      session.usage = asRecord(final.raw)?.usage as Record<string, unknown> | undefined;
      session.updatedAt = Date.now();
      this.emit("chat", {
        sessionKey,
        runId,
        state: "final",
        message: finalText
      });
    } catch (error) {
      if (!controller.signal.aborted) {
        this.emit("chat", {
          sessionKey,
          runId,
          state: "error",
          error: error instanceof Error ? error.message : String(error)
        });
      }
    } finally {
      this.activeRuns.delete(runId);
      if (session.activeRunId === runId) {
        session.activeRunId = null;
      }
    }
  }

  private handleRunEvent(session: StoredSession, runId: string, event: HermesSseEvent, latestText: string): string {
    const toolName = eventToolName(event.data);
    if (toolName || event.event.toLowerCase().includes("tool")) {
      this.emit("agent", {
        sessionKey: session.key,
        runId,
        type: "tool",
        toolName: toolName ?? "hermes_tool",
        status: eventStatus(event.data) ?? "running",
        data: event.data
      });
    }
    const delta = firstStringField(event.data, ["delta", "text_delta", "output_text", "text"]);
    if (!delta) {
      return latestText;
    }
    const next = latestText + delta;
    this.upsertAssistantMessage(session, runId, next);
    this.emit("chat", {
      sessionKey: session.key,
      runId,
      state: "delta",
      delta
    });
    return next;
  }

  private upsertAssistantMessage(session: StoredSession, runId: string, text: string): void {
    const id = `assistant_${runId}`;
    const existing = session.messages.find((message) => message.id === id);
    if (existing) {
      existing.text = text;
      existing.timestamp = Date.now();
    } else {
      session.messages.push({
        id,
        role: "assistant",
        text,
        timestamp: Date.now()
      });
    }
    session.updatedAt = Date.now();
  }

  private emit(event: string, payload: unknown): void {
    const gatewayEvent: GatewayEvent = { event, payload };
    for (const handler of this.handlers) {
      handler(gatewayEvent);
    }
  }
}
