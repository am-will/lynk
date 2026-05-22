import { randomUUID } from "node:crypto";
import type { AuditLog } from "./AuditLog.js";
import type { AgentRunResult, AgentStatusSink } from "../dispatcher/AgentClient.js";
import { CodexAppServerClient } from "../dispatcher/CodexAppServerClient.js";
import { InMemoryHarnessSessionStore, type HarnessStoredSession } from "./harness/InMemoryHarnessSessionStore.js";
import type { GatewayChatSendResult, GatewayEvent, GatewayEventHandler } from "./OpenClawGatewayChatClient.js";

interface ActiveRun {
  sessionKey: string;
  runId: string;
}

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

export class CodexChatClient {
  private readonly client: CodexAppServerClient;
  private readonly sessions = new InMemoryHarnessSessionStore("codex", {
    defaultModel: "gpt-5.3-codex",
    modelProvider: "codex"
  });
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
    this.sessions.appendUserMessage(session, options.message, options.idempotencyKey);

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
      return {
        models: rawModels
          .map((item) => {
            const record = asRecord(item);
            const id = stringField(record, "id") ?? stringField(record, "model");
            if (!id || record?.hidden === true) {
              return undefined;
            }
            return {
              id,
              key: id,
              name: stringField(record, "displayName") ?? stringField(record, "name") ?? id,
              provider: "codex",
              available: true,
              reasoningOptions: arrayField(record, "supportedReasoningEfforts"),
              defaultReasoningEffort: stringField(record, "defaultReasoningEffort")
            };
          })
          .filter(Boolean),
        defaults: {
          thinkingLevels: ["low", "medium", "high", "xhigh"]
        }
      };
    }
    return {
      models: [
        { id: "gpt-5.5", key: "gpt-5.5", name: "GPT-5.5", provider: "codex", available: true, reasoningOptions: ["low", "medium", "high", "xhigh"], defaultReasoningEffort: "medium" },
        { id: "gpt-5.4", key: "gpt-5.4", name: "gpt-5.4", provider: "codex", available: true, reasoningOptions: ["low", "medium", "high", "xhigh"], defaultReasoningEffort: "medium" },
        { id: "gpt-5.4-mini", key: "gpt-5.4-mini", name: "GPT-5.4-Mini", provider: "codex", available: true, reasoningOptions: ["low", "medium", "high", "xhigh"], defaultReasoningEffort: "medium" },
        { id: "gpt-5.3-codex", key: "gpt-5.3-codex", name: "gpt-5.3-codex", provider: "codex", available: true, reasoningOptions: ["low", "medium", "high", "xhigh"], defaultReasoningEffort: "medium" },
        { id: "gpt-5.3-codex-spark", key: "gpt-5.3-codex-spark", name: "GPT-5.3-Codex-Spark", provider: "codex", available: true, reasoningOptions: ["low", "medium", "high", "xhigh"], defaultReasoningEffort: "high" }
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
    return this.sessions.createSession(options);
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
        model,
        reasoningEffort,
        taskKind: "general"
      });
      const finalText = finalTextFromResult(result);
      this.sessions.upsertAssistantMessage(session, runId, finalText);
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
}

function finalTextFromResult(result: AgentRunResult): string {
  return result.finalMessage || result.error || "";
}

