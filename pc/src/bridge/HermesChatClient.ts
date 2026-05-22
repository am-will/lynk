import { HermesApiClient } from "../dispatcher/HermesApiClient.js";
import { HermesRunDriver, type HermesActiveRun, type HermesRunDriverEvent } from "../dispatcher/HermesRunDriver.js";
import type { BridgeConfig } from "./config.js";
import { discoverHermesModels } from "./HermesModelDiscovery.js";
import { InMemoryHarnessSessionStore, type HarnessStoredSession } from "./harness/InMemoryHarnessSessionStore.js";
import type { GatewayChatSendResult, GatewayEvent, GatewayEventHandler } from "./OpenClawGatewayChatClient.js";

interface ActiveChatRun {
  sessionKey: string;
  active: HermesActiveRun;
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

export class HermesChatClient {
  private readonly api: HermesApiClient;
  private readonly driver: HermesRunDriver;
  private readonly sessions: InMemoryHarnessSessionStore;
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
    this.driver = new HermesRunDriver(this.api, config.hermesRunTimeoutMs);
    this.sessions = new InMemoryHarnessSessionStore("hermes", {
      defaultModel: config.hermesModel,
      modelProvider: "hermes"
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
    const session = this.sessions.ensureSession(options.sessionKey, options.sessionId);
    this.sessions.setThinkingLevel(session, options.thinking);
    this.sessions.appendUserMessage(session, options.message, options.idempotencyKey);

    const active = await this.driver.createRun({
      input: options.message,
      sessionId: session.sessionId,
      model: session.model,
      idempotencyKey: options.idempotencyKey
    });
    const runId = active.runId;
    this.sessions.setActiveRun(session, runId);
    this.activeRuns.set(runId, {
      sessionKey: session.key,
      active
    });
    void this.processRun(session.key, active);
    return { runId, sessionKey: session.key };
  }

  async abort(_sessionKey: string, runId?: string): Promise<unknown> {
    if (!runId) {
      return {};
    }
    const active = this.activeRuns.get(runId);
    if (active) {
      await this.driver.stopRun(active.active);
    } else {
      await this.api.stopRun(runId);
    }
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
    const discoveredModels = discoverHermesModels(this.config.hermesModel);
    const models = discoveredModels.length > 0 ? discoveredModels : apiModels;
    return { models };
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
    return this.sessions.createSession(options);
  }

  async patchSession(sessionKey: string, patch: Record<string, unknown>): Promise<unknown> {
    this.sessions.patchSession(sessionKey, patch);
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
      active.active.controller.abort();
    }
    this.activeRuns.clear();
  }

  private async processRun(sessionKey: string, active: HermesActiveRun): Promise<void> {
    const session = this.sessions.ensureSession(sessionKey);
    const runId = active.runId;
    try {
      const completed = await this.driver.streamRun(active, (event) => this.handleRunEvent(session, active.runId, event));
      const finalText = completed.finalText;
      if (finalText.trim()) {
        this.sessions.upsertAssistantMessage(session, runId, finalText);
      }
      this.sessions.clearActiveRun(session, runId);
      this.sessions.setUsage(session, asRecord(completed.status.raw)?.usage as Record<string, unknown> | undefined);
      this.emit("chat", {
        sessionKey,
        runId,
        state: "final",
        message: finalText
      });
    } catch (error) {
      if (!active.controller.signal.aborted) {
        this.emit("chat", {
          sessionKey,
          runId,
          state: "error",
          error: error instanceof Error ? error.message : String(error)
        });
      }
    } finally {
      this.activeRuns.delete(runId);
      this.sessions.clearActiveRun(session, runId);
    }
  }

  private handleRunEvent(session: HarnessStoredSession, runId: string, event: HermesRunDriverEvent): void {
    if (event.type === "tool") {
      this.emit("agent", {
        sessionKey: session.key,
        runId,
        type: "tool",
        toolName: event.toolName ?? "hermes_tool",
        status: event.status ?? "running",
        data: event.raw.data
      });
      return;
    }
    if (event.type !== "delta") {
      return;
    }
    this.sessions.upsertAssistantMessage(session, runId, event.accumulated);
    this.emit("chat", {
      sessionKey: session.key,
      runId,
      state: "delta",
      delta: event.delta
    });
  }

  private emit(event: string, payload: unknown): void {
    const gatewayEvent: GatewayEvent = { event, payload };
    for (const handler of this.handlers) {
      handler(gatewayEvent);
    }
  }
}
