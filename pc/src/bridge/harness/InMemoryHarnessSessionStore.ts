import { randomUUID } from "node:crypto";
import { chatAttachmentSchema, type ChatAttachment, type ChatHistoryMessage, type ChatSessionSummary } from "../../protocol/messages.js";
import { DebouncedAtomicJsonWriter, enforcePrivateFileSync, migrateLegacyFileSync, readJsonWithRecoverySync } from "../../host/PrivatePersistence.js";
import type { HarnessId } from "../AgentHarness.js";
import type { HarnessChatHistory, HarnessCreatedSession } from "./HarnessChatAdapter.js";
import { attachmentMetadata } from "../../attachments/AttachmentCompatibility.js";

export interface HarnessStoredMessage {
  id: string;
  role: "user" | "assistant" | "system";
  text: string;
  attachments?: ChatAttachment[];
  timestamp: number;
}

export interface HarnessStoredSession {
  key: string;
  sessionId: string;
  label: string;
  displayName?: string;
  model?: string;
  thinkingLevel?: string;
  fastMode?: boolean | null;
  messages: HarnessStoredMessage[];
  updatedAt: number;
  activeRunId?: string | null;
  usage?: Record<string, unknown>;
  metadata?: Record<string, unknown>;
}

export interface InMemoryHarnessSessionStoreOptions {
  defaultModel: string;
  modelProvider: string;
  storagePath?: string;
  legacyStoragePaths?: string[];
  persistEmptySessions?: boolean;
}

export class InMemoryHarnessSessionStore {
  private readonly sessions: Map<string, HarnessStoredSession>;
  private readonly writer?: DebouncedAtomicJsonWriter<StoredSessionDocument>;

  constructor(
    private readonly harnessId: HarnessId,
    private readonly options: InMemoryHarnessSessionStoreOptions
  ) {
    this.sessions = this.loadSessions();
    if (options.storagePath) {
      this.writer = new DebouncedAtomicJsonWriter(options.storagePath, 25, MAX_STORAGE_BYTES);
    }
  }

  ensureSession(sessionKey: string, sessionId?: string): HarnessStoredSession {
    const { session, created } = this.getOrAdd(sessionKey, sessionId);
    if (created) {
      this.persist();
    }
    return session;
  }

  requireSession(sessionKey: string): HarnessStoredSession | undefined {
    return this.sessions.get(sessionKey.trim()) ?? undefined;
  }

  private getOrAdd(sessionKey: string, sessionId?: string): { session: HarnessStoredSession; created: boolean } {
    const key = sessionKey.trim() || `${this.harnessId}:${randomUUID()}`;
    const existing = this.sessions.get(key);
    if (existing) {
      return { session: existing, created: false };
    }
    const cleanSessionId = sanitizeSessionId(sessionId ?? key.replace(new RegExp(`^${this.harnessId}:`), ""));
    const created: HarnessStoredSession = {
      key,
      sessionId: cleanSessionId,
      label: cleanSessionId,
      model: this.options.defaultModel,
      messages: [],
      updatedAt: Date.now(),
      activeRunId: null,
      metadata: {}
    };
    this.sessions.set(key, created);
    return { session: created, created: true };
  }

  history(sessionKey: string): HarnessChatHistory {
    const session = this.ensureSession(sessionKey);
    return {
      sessionId: session.sessionId,
      thinkingLevel: session.thinkingLevel,
      fastMode: session.fastMode ?? null,
      messages: this.historyMessages(session)
    };
  }

  listSessions(limit = 50): ChatSessionSummary[] {
    return this.listStoredSessions(limit)
      .map((session) => this.toSummary(session));
  }

  listStoredSessions(limit = 50): HarnessStoredSession[] {
    return [...this.sessions.values()]
      .sort((left, right) => right.updatedAt - left.updatedAt)
      .slice(0, limit);
  }

  hasUserMessage(session: HarnessStoredSession): boolean {
    return session.messages.some((message) => message.role === "user");
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
    if (typeof patch.fastMode === "boolean") {
      session.fastMode = patch.fastMode;
    }
    if (typeof patch.displayName === "string" && patch.displayName.trim()) {
      session.displayName = patch.displayName.trim();
    }
    session.updatedAt = Date.now();
    this.persist();
  }

  appendUserMessage(
    session: HarnessStoredSession,
    text: string,
    idempotencyKey?: string,
    attachments?: ChatAttachment[]
  ): void {
    session.messages.push({
      id: `user_${idempotencyKey ?? randomUUID()}`,
      role: "user",
      text: boundedText(text),
      ...(attachments?.length ? { attachments: attachments.map(attachmentMetadata) } : {}),
      timestamp: Date.now()
    });
    this.trimSessionMessages(session);
    session.updatedAt = Date.now();
    this.persist();
  }

  appendSystemMessage(session: HarnessStoredSession, id: string, text: string): void {
    const existing = session.messages.find((message) => message.id === id);
    if (existing) {
      existing.text = boundedText(text);
      existing.timestamp = Date.now();
    } else {
      session.messages.push({
        id,
        role: "system",
        text: boundedText(text),
        timestamp: Date.now()
      });
    }
    this.trimSessionMessages(session);
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

  setMetadata(session: HarnessStoredSession, key: string, value: unknown): void {
    const metadata = session.metadata ?? {};
    if (metadata[key] === value) {
      return;
    }
    session.metadata = {
      ...metadata,
      [key]: value
    };
    session.updatedAt = Date.now();
    this.persist();
  }

  replaceHistory(sessionKey: string, messages: ChatHistoryMessage[]): void {
    const { session } = this.getOrAdd(sessionKey);
    session.messages = messages
      .filter((message) => isStoredMessageRole(message.role) && typeof message.text === "string" && message.text.length > 0)
      .slice(-MAX_MESSAGES_PER_SESSION)
      .map((message) => ({
        id: message.id?.trim() || randomUUID(),
        role: message.role as HarnessStoredMessage["role"],
        text: boundedText(message.text),
        ...(message.attachments?.length ? { attachments: message.attachments.map(attachmentMetadata) } : {}),
        timestamp: message.timestamp ?? Date.now()
      }));
    session.updatedAt = Date.now();
    this.persist();
  }

  upsertAssistantMessage(
    session: HarnessStoredSession,
    runId: string,
    text: string,
    options: { persist?: boolean } = {}
  ): void {
    const id = `assistant_${runId}`;
    const existing = session.messages.find((message) => message.id === id);
    if (existing) {
      existing.text = boundedText(text);
      existing.timestamp = Date.now();
    } else {
      session.messages.push({
        id,
        role: "assistant",
        text: boundedText(text),
        timestamp: Date.now()
      });
    }
    this.trimSessionMessages(session);
    session.updatedAt = Date.now();
    if (options.persist !== false) {
      this.persist();
    }
  }

  historyMessages(session: HarnessStoredSession): ChatHistoryMessage[] {
    return session.messages.map((message) => ({
      id: message.id,
      role: message.role,
      text: message.text,
      ...(message.attachments?.length ? { attachments: message.attachments } : {}),
      timestamp: message.timestamp
    }));
  }

  private loadSessions(): Map<string, HarnessStoredSession> {
    const path = this.options.storagePath;
    if (!path) return new Map();
    migrateLegacyFileSync(path, this.options.legacyStoragePaths ?? [], `${path}.migration-v1.json`, MAX_STORAGE_BYTES);
    enforcePrivateFileSync(path);
    const recovered = readJsonWithRecoverySync<StoredSessionDocument>(
      path,
      () => ({ version: 1, harnessId: this.harnessId, sessions: [] }),
      isStoredSessionDocument,
      MAX_STORAGE_BYTES
    );
    const sessions = new Map<string, HarnessStoredSession>();
    for (const record of recovered.value.sessions.slice(-MAX_SESSIONS)) {
      const session = parseStoredSession(record, this.options.defaultModel);
      if (session) {
        if (session.messages.length > MAX_MESSAGES_PER_SESSION) {
          session.messages.splice(0, session.messages.length - MAX_MESSAGES_PER_SESSION);
        }
        sessions.set(session.key, session);
      }
    }
    return sessions;
  }

  private persist(): void {
    if (!this.writer) return;
    this.enforceBounds();
    const sessions = [...this.sessions.values()].filter((session) =>
      this.options.persistEmptySessions !== false || this.hasUserMessage(session)
    );
    this.writer.schedule({
      version: 1,
      harnessId: this.harnessId,
      sessions
    });
  }

  async flushPersistence(): Promise<void> {
    await this.writer?.flush();
  }

  async close(): Promise<void> {
    await this.writer?.close();
  }

  private enforceBounds(): void {
    const ordered = [...this.sessions.values()].sort((left, right) => right.updatedAt - left.updatedAt);
    for (const session of ordered) this.trimSessionMessages(session);
    for (const session of ordered.slice(MAX_SESSIONS)) this.sessions.delete(session.key);
  }

  private trimSessionMessages(session: HarnessStoredSession): void {
    if (session.messages.length > MAX_MESSAGES_PER_SESSION) {
      session.messages.splice(0, session.messages.length - MAX_MESSAGES_PER_SESSION);
    }
  }

  private toSummary(session: HarnessStoredSession): ChatSessionSummary {
    return {
      key: session.key,
      sessionId: session.sessionId,
      label: session.label,
      displayName: session.displayName ?? session.label,
      model: session.model ?? this.options.defaultModel,
      modelProvider: this.options.modelProvider,
      updatedAt: session.updatedAt,
      hasActiveRun: Boolean(session.activeRunId),
      thinkingLevel: session.thinkingLevel ?? null,
      fastMode: session.fastMode ?? null,
      inputTokens: numberFromUsage(session.usage, "input_tokens", "inputTokens"),
      outputTokens: numberFromUsage(session.usage, "output_tokens", "outputTokens"),
      totalTokens: numberFromUsage(session.usage, "total_tokens", "totalTokens"),
      contextTokens: numberFromUsage(session.usage, "context_tokens", "contextTokens")
    };
  }
}

interface StoredSessionDocument {
  version: 1;
  harnessId: string;
  sessions: unknown[];
}

const MAX_SESSIONS = 200;
const MAX_MESSAGES_PER_SESSION = 500;
const MAX_MESSAGE_CHARS = 200_000;
const MAX_STORAGE_BYTES = 16 * 1024 * 1024;

function boundedText(text: string): string {
  return text.length <= MAX_MESSAGE_CHARS ? text : text.slice(0, MAX_MESSAGE_CHARS);
}

function isStoredSessionDocument(value: unknown): value is StoredSessionDocument {
  const record = asRecord(value);
  return record?.version === 1 && typeof record.harnessId === "string" && Array.isArray(record.sessions);
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
    fastMode: booleanField(record, "fastMode"),
    messages: parseStoredMessages(record?.messages),
    updatedAt: numberField(record, "updatedAt") ?? Date.now(),
    activeRunId: null,
    usage: asRecord(record?.usage),
    metadata: {
      ...asRecord(record?.metadata),
      ...(booleanField(record, "baseInstructionsBound") === true ? { codexBaseInstructionsBound: true } : {})
    }
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
  const attachments = parseStoredAttachments(record?.attachments);
  return {
    id,
    role,
    text,
    ...(attachments.length ? { attachments } : {}),
    timestamp: numberField(record, "timestamp") ?? Date.now()
  };
}

function parseStoredAttachments(value: unknown): ChatAttachment[] {
  if (!Array.isArray(value)) {
    return [];
  }
  const attachments: ChatAttachment[] = [];
  for (const item of value) {
    const parsed = chatAttachmentSchema.safeParse(item);
    if (parsed.success) {
      attachments.push(parsed.data);
    }
  }
  return attachments;
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

function booleanField(record: Record<string, unknown> | undefined, key: string): boolean | undefined {
  const value = record?.[key];
  return typeof value === "boolean" ? value : undefined;
}

function numberFromUsage(usage: Record<string, unknown> | undefined, snakeKey: string, camelKey: string): number | null {
  const value = usage?.[snakeKey] ?? usage?.[camelKey];
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}
