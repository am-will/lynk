import { randomUUID } from "node:crypto";
import type { AuditLog } from "./AuditLog.js";
import type { AgentRunResult, AgentStatusSink } from "../dispatcher/AgentClient.js";
import { CodexAppServerClient } from "../dispatcher/CodexAppServerClient.js";
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
}

interface ActiveRun {
  sessionKey: string;
  runId: string;
}

export class CodexChatClient {
  private readonly client: CodexAppServerClient;
  private readonly sessions = new Map<string, StoredSession>();
  private readonly handlers = new Set<GatewayEventHandler>();
  private active?: ActiveRun;

  constructor(audit?: AuditLog, client?: CodexAppServerClient) {
    this.client = client ?? new CodexAppServerClient(audit);
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
    if (this.active) {
      throw new Error("A Codex task is already running");
    }
    const session = this.ensureSession(options.sessionKey, options.sessionId);
    session.thinkingLevel = options.thinking ?? session.thinkingLevel;
    session.messages.push({
      id: `user_${options.idempotencyKey ?? randomUUID()}`,
      role: "user",
      text: options.message,
      timestamp: Date.now()
    });
    session.updatedAt = Date.now();

    const runId = options.idempotencyKey ?? `codex_${randomUUID()}`;
    session.activeRunId = runId;
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
    return {
      models: [
        { id: "gpt-5.3-codex", key: "gpt-5.3-codex", name: "gpt-5.3-codex", provider: "codex", available: true },
        { id: "gpt-5.3-codex-spark", key: "gpt-5.3-codex-spark", name: "gpt-5.3-codex-spark", provider: "codex", available: true }
      ],
      defaults: {
        thinkingLevels: ["low", "medium", "high", "xhigh"]
      }
    };
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
        model: session.model ?? "gpt-5.3-codex",
        modelProvider: "codex",
        updatedAt: session.updatedAt,
        hasActiveRun: Boolean(session.activeRunId),
        thinkingLevel: session.thinkingLevel ?? null
      }));
    return {
      sessions,
      defaults: {
        thinkingLevels: ["low", "medium", "high", "xhigh"]
      }
    };
  }

  async createSession(options: { key?: string; label?: string; model?: string }): Promise<unknown> {
    const key = options.key?.trim() || `codex:${randomUUID()}`;
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
    session: StoredSession,
    runId: string,
    text: string,
    model: string | undefined,
    reasoningEffort: string | undefined
  ): Promise<void> {
    try {
      const result = await this.client.submitUserRequest(text, this.statusSink(session.key, runId), {
        model,
        reasoningEffort,
        taskKind: "general"
      });
      const finalText = finalTextFromResult(result);
      this.upsertAssistantMessage(session, runId, finalText);
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
      if (session.activeRunId === runId) {
        session.activeRunId = null;
      }
      session.updatedAt = Date.now();
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

  private ensureSession(sessionKey: string, sessionId?: string): StoredSession {
    const key = sessionKey.trim() || `codex:${randomUUID()}`;
    const existing = this.sessions.get(key);
    if (existing) {
      return existing;
    }
    const cleanSessionId = sanitizeSessionId(sessionId ?? key.replace(/^codex:/, ""));
    const created: StoredSession = {
      key,
      sessionId: cleanSessionId,
      label: cleanSessionId,
      model: "gpt-5.3-codex",
      messages: [],
      updatedAt: Date.now(),
      activeRunId: null
    };
    this.sessions.set(key, created);
    return created;
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

function finalTextFromResult(result: AgentRunResult): string {
  return result.finalMessage || result.error || "";
}

function sanitizeSessionId(value: string): string {
  return value.replace(/[^a-zA-Z0-9_.:-]+/g, "-").replace(/^-+|-+$/g, "") || "default";
}
