import { randomUUID } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
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
  storagePath?: string;
}

export class InMemoryHarnessSessionStore {
  private readonly sessions: Map<string, HarnessStoredSession>;

  constructor(
    private readonly harnessId: HarnessId,
    private readonly options: InMemoryHarnessSessionStoreOptions
  ) {
    this.sessions = this.loadSessions();
  }

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
    this.persist();
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
        totalTokens: numberFromUsage(session.usage, "total_tokens", "totalTokens"),
        contextTokens: numberFromUsage(session.usage, "context_tokens", "contextTokens")
      }));
  }

  createSession(options: { key?: string; label?: string; model?: string }): HarnessCreatedSession {
    const key = options.key?.trim() || `${this.harnessId}:${randomUUID()}`;
    const session = this.ensureSession(key);
    session.label = options.label?.trim() || session.label;
    session.displayName = options.label?.trim() || session.displayName;
    session.model = options.model?.trim() || session.model;
    session.updatedAt = Date.now();
    this.persist();
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
    this.persist();
  }

  appendUserMessage(session: HarnessStoredSession, text: string, idempotencyKey?: string): void {
    session.messages.push({
      id: `user_${idempotencyKey ?? randomUUID()}`,
      role: "user",
      text,
      timestamp: Date.now()
    });
    session.updatedAt = Date.now();
    this.persist();
  }

  setThinkingLevel(session: HarnessStoredSession, thinking?: string): void {
    session.thinkingLevel = thinking ?? session.thinkingLevel;
    this.persist();
  }

  setActiveRun(session: HarnessStoredSession, runId: string): void {
    session.activeRunId = runId;
    session.updatedAt = Date.now();
    this.persist();
  }

  clearActiveRun(session: HarnessStoredSession, runId: string): void {
    if (session.activeRunId === runId) {
      session.activeRunId = null;
    }
    session.updatedAt = Date.now();
    this.persist();
  }

  setUsage(session: HarnessStoredSession, usage?: Record<string, unknown>): void {
    session.usage = usage;
    session.updatedAt = Date.now();
    this.persist();
  }

  setSessionId(session: HarnessStoredSession, sessionId: string): void {
    const cleanSessionId = sanitizeSessionId(sessionId);
    if (session.sessionId === cleanSessionId) {
      return;
    }
    session.sessionId = cleanSessionId;
    session.updatedAt = Date.now();
    this.persist();
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
    this.persist();
  }

  historyMessages(session: HarnessStoredSession): ChatHistoryMessage[] {
    return session.messages.map((message) => ({
      id: message.id,
      role: message.role,
      text: message.text,
      timestamp: message.timestamp
    }));
  }

  private loadSessions(): Map<string, HarnessStoredSession> {
    const path = this.options.storagePath;
    if (!path || !existsSync(path)) {
      return new Map();
    }
    try {
      const parsed = JSON.parse(readFileSync(path, "utf8")) as unknown;
      const records = Array.isArray(asRecord(parsed)?.sessions) ? asRecord(parsed)?.sessions as unknown[] : [];
      const sessions = new Map<string, HarnessStoredSession>();
      for (const record of records) {
        const session = parseStoredSession(record, this.options.defaultModel);
        if (session) {
          sessions.set(session.key, session);
        }
      }
      return sessions;
    } catch {
      return new Map();
    }
  }

  private persist(): void {
    const path = this.options.storagePath;
    if (!path) {
      return;
    }
    mkdirSync(dirname(path), { recursive: true });
    writeFileSync(path, JSON.stringify({
      version: 1,
      harnessId: this.harnessId,
      sessions: [...this.sessions.values()]
    }, null, 2));
  }
}

function sanitizeSessionId(value: string): string {
  return value.replace(/[^a-zA-Z0-9_.:-]+/g, "-").replace(/^-+|-+$/g, "") || "default";
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" ? value as Record<string, unknown> : undefined;
}

function parseStoredSession(value: unknown, defaultModel: string): HarnessStoredSession | undefined {
  const record = asRecord(value);
  const key = stringField(record, "key");
  const sessionId = stringField(record, "sessionId");
  if (!key || !sessionId) {
    return undefined;
  }
  return {
    key,
    sessionId,
    label: stringField(record, "label") ?? sessionId,
    displayName: stringField(record, "displayName"),
    model: stringField(record, "model") ?? defaultModel,
    thinkingLevel: stringField(record, "thinkingLevel"),
    messages: parseStoredMessages(record?.messages),
    updatedAt: numberField(record, "updatedAt") ?? Date.now(),
    activeRunId: null,
    usage: asRecord(record?.usage)
  };
}

function parseStoredMessages(value: unknown): HarnessStoredMessage[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.map(parseStoredMessage).filter((message): message is HarnessStoredMessage => Boolean(message));
}

function parseStoredMessage(value: unknown): HarnessStoredMessage | undefined {
  const record = asRecord(value);
  const id = stringField(record, "id");
  const role = stringField(record, "role");
  const text = stringField(record, "text");
  if (!id || !isStoredMessageRole(role) || !text) {
    return undefined;
  }
  return {
    id,
    role,
    text,
    timestamp: numberField(record, "timestamp") ?? Date.now()
  };
}

function isStoredMessageRole(value: string | undefined): value is HarnessStoredMessage["role"] {
  return value === "user" || value === "assistant" || value === "system";
}

function stringField(record: Record<string, unknown> | undefined, key: string): string | undefined {
  const value = record?.[key];
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function numberField(record: Record<string, unknown> | undefined, key: string): number | undefined {
  const value = record?.[key];
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function numberFromUsage(usage: Record<string, unknown> | undefined, snakeKey: string, camelKey: string): number | null {
  const value = usage?.[snakeKey] ?? usage?.[camelKey];
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}
