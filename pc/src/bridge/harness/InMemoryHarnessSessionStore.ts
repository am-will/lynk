import { randomUUID } from "node:crypto";
import type { ChatHistoryMessage, ChatSessionSummary } from "../../protocol/messages.js";
import type { HarnessId } from "../AgentHarness.js";
import type { HarnessChatHistory, HarnessCreatedSession } from "./HarnessChatAdapter.js";

export interface HarnessStoredMessage {
  id: string;
  role: "user" | "assistant" | "system";
  text: string;
  timestamp: number;
}

export interface HarnessStoredSession {
  key: string;
  sessionId: string;
  label: string;
  displayName?: string;
  model?: string;
  thinkingLevel?: string;
  messages: HarnessStoredMessage[];
  updatedAt: number;
  activeRunId?: string | null;
  usage?: Record<string, unknown>;
}

export interface InMemoryHarnessSessionStoreOptions {
  defaultModel: string;
  modelProvider: string;
}

export class InMemoryHarnessSessionStore {
  private readonly sessions = new Map<string, HarnessStoredSession>();

  constructor(
    private readonly harnessId: HarnessId,
    private readonly options: InMemoryHarnessSessionStoreOptions
  ) {}

  ensureSession(sessionKey: string, sessionId?: string): HarnessStoredSession {
    const key = sessionKey.trim() || `${this.harnessId}:${randomUUID()}`;
    const existing = this.sessions.get(key);
    if (existing) {
      return existing;
    }
    const cleanSessionId = sanitizeSessionId(sessionId ?? key.replace(new RegExp(`^${this.harnessId}:`), ""));
    const created: HarnessStoredSession = {
      key,
      sessionId: cleanSessionId,
      label: cleanSessionId,
      model: this.options.defaultModel,
      messages: [],
      updatedAt: Date.now(),
      activeRunId: null
    };
    this.sessions.set(key, created);
    return created;
  }

  history(sessionKey: string): HarnessChatHistory {
    const session = this.ensureSession(sessionKey);
    return {
      sessionId: session.sessionId,
      thinkingLevel: session.thinkingLevel,
      messages: this.historyMessages(session)
    };
  }

  listSessions(limit = 50): ChatSessionSummary[] {
    return [...this.sessions.values()]
      .sort((left, right) => right.updatedAt - left.updatedAt)
      .slice(0, limit)
      .map((session) => ({
        key: session.key,
        sessionId: session.sessionId,
        label: session.label,
        displayName: session.displayName ?? session.label,
        model: session.model ?? this.options.defaultModel,
        modelProvider: this.options.modelProvider,
        updatedAt: session.updatedAt,
        hasActiveRun: Boolean(session.activeRunId),
        thinkingLevel: session.thinkingLevel ?? null,
        inputTokens: numberFromUsage(session.usage, "input_tokens", "inputTokens"),
        outputTokens: numberFromUsage(session.usage, "output_tokens", "outputTokens"),
        totalTokens: numberFromUsage(session.usage, "total_tokens", "totalTokens")
      }));
  }

  createSession(options: { key?: string; label?: string; model?: string }): HarnessCreatedSession {
    const key = options.key?.trim() || `${this.harnessId}:${randomUUID()}`;
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

  patchSession(sessionKey: string, patch: Record<string, unknown>): void {
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
  }

  appendUserMessage(session: HarnessStoredSession, text: string, idempotencyKey?: string): void {
    session.messages.push({
      id: `user_${idempotencyKey ?? randomUUID()}`,
      role: "user",
      text,
      timestamp: Date.now()
    });
    session.updatedAt = Date.now();
  }

  setThinkingLevel(session: HarnessStoredSession, thinking?: string): void {
    session.thinkingLevel = thinking ?? session.thinkingLevel;
  }

  setActiveRun(session: HarnessStoredSession, runId: string): void {
    session.activeRunId = runId;
    session.updatedAt = Date.now();
  }

  clearActiveRun(session: HarnessStoredSession, runId: string): void {
    if (session.activeRunId === runId) {
      session.activeRunId = null;
    }
    session.updatedAt = Date.now();
  }

  setUsage(session: HarnessStoredSession, usage?: Record<string, unknown>): void {
    session.usage = usage;
    session.updatedAt = Date.now();
  }

  upsertAssistantMessage(session: HarnessStoredSession, runId: string, text: string): void {
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

  historyMessages(session: HarnessStoredSession): ChatHistoryMessage[] {
    return session.messages.map((message) => ({
      id: message.id,
      role: message.role,
      text: message.text,
      timestamp: message.timestamp
    }));
  }
}

function sanitizeSessionId(value: string): string {
  return value.replace(/[^a-zA-Z0-9_.:-]+/g, "-").replace(/^-+|-+$/g, "") || "default";
}

function numberFromUsage(usage: Record<string, unknown> | undefined, snakeKey: string, camelKey: string): number | null {
  const value = usage?.[snakeKey] ?? usage?.[camelKey];
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}
