import { basename, normalize } from "node:path";

export const CODEX_THREAD_SESSION_KEY_PREFIX = "codex:";
export const CODEX_THREAD_PAGE_SIZE = 100;
export const CODEX_THREAD_MAX_LIST = 500;

const CODEX_THREAD_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const CODEX_QUICK_CHAT_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

export interface CodexThreadRecord {
  id: string;
  sessionId?: string | null;
  name?: string | null;
  preview?: string | null;
  cwd?: string | null;
  path?: string | null;
  source?: string | null;
  modelProvider?: string | null;
  hasWorkspace?: boolean;
  updatedAt?: number | null;
  createdAt?: number | null;
}

export function codexThreadIdFromSessionKey(sessionKey: string | undefined | null): string | undefined {
  const raw = sessionKey?.startsWith(CODEX_THREAD_SESSION_KEY_PREFIX)
    ? sessionKey.slice(CODEX_THREAD_SESSION_KEY_PREFIX.length)
    : undefined;
  return raw && CODEX_THREAD_ID_PATTERN.test(raw) ? raw : undefined;
}

export function normalizeCodexThreadList(payload: unknown): CodexThreadRecord[] {
  return arrayField(asRecord(payload), "data")
    .map(normalizeCodexThread)
    .filter((thread): thread is CodexThreadRecord => Boolean(thread));
}

export function normalizeCodexThread(value: unknown): CodexThreadRecord | undefined {
  const record = asRecord(value);
  const id = stringField(record, "id");
  if (!id) {
    return undefined;
  }
  return {
    id,
    sessionId: stringField(record, "sessionId") ?? null,
    name: stringField(record, "name") ?? null,
    preview: stringField(record, "preview") ?? null,
    cwd: stringField(record, "cwd") ?? null,
    path: stringField(record, "path") ?? null,
    source: stringField(record, "source") ?? null,
    modelProvider: stringField(record, "modelProvider") ?? null,
    hasWorkspace: hasWorkspace(record),
    createdAt: numberField(record, "createdAt") ?? null,
    updatedAt: numberField(record, "updatedAt") ?? null
  };
}

export function codexThreadDisplayName(thread: CodexThreadRecord): string {
  return thread.name
    ?? firstPreviewLine(thread.preview)
    ?? thread.id;
}

export function workspaceNameFromPath(path: string | null | undefined): string | null {
  if (!path) {
    return null;
  }
  return basename(path) || path;
}

export function secondsToMillis(value: number | null | undefined): number | null {
  if (value === null || value === undefined) {
    return null;
  }
  return value < 10_000_000_000 ? value * 1000 : value;
}

export function chatMessagesFromCodexThreadRead(payload: unknown): Array<Record<string, unknown>> {
  const thread = asRecord(asRecord(payload)?.thread);
  const turns = arrayField(thread, "turns");
  return turns.flatMap((turn) => {
    const turnRecord = asRecord(turn);
    const turnTimestamp = secondsToMillis(numberField(turnRecord, "createdAt") ?? numberField(turnRecord, "startedAt"));
    return arrayField(turnRecord, "items")
      .map((item, index) => chatMessageFromCodexItem(item, index, turnTimestamp))
      .filter((message): message is Record<string, unknown> => Boolean(message));
  });
}

function hasWorkspace(record: Record<string, unknown> | undefined): boolean {
  const cwd = stringField(record, "cwd");
  return Boolean(cwd && !isCodexQuickChatCwd(cwd));
}

function isCodexQuickChatCwd(path: string): boolean {
  const segments = normalize(path).split(/[\\/]+/);
  return segments.some((segment, index) =>
    segment === "Documents"
    && segments[index + 1] === "Codex"
    && CODEX_QUICK_CHAT_DATE_PATTERN.test(segments[index + 2] ?? "")
  );
}

function firstPreviewLine(preview: string | null | undefined): string | undefined {
  return preview?.split(/\r?\n/).map((line) => line.trim()).find(Boolean);
}

function chatMessageFromCodexItem(item: unknown, index: number, timestamp: number | null): Record<string, unknown> | undefined {
  const record = asRecord(item);
  const type = stringField(record, "type");
  const id = stringField(record, "id") ?? `codex_item_${index}`;
  if (type === "userMessage") {
    const text = codexContentText(record?.content);
    return text.trim() ? { id, role: "user", text, timestamp } : undefined;
  }
  if (type === "agentMessage") {
    const text = stringField(record, "text") ?? codexContentText(record?.content);
    return text.trim() ? { id, role: "assistant", text, timestamp } : undefined;
  }
  return undefined;
}

function codexContentText(value: unknown): string {
  if (typeof value === "string") {
    return value;
  }
  if (Array.isArray(value)) {
    return value.map(codexContentText).filter(Boolean).join("");
  }
  const record = asRecord(value);
  if (!record) {
    return "";
  }
  return stringField(record, "text")
    ?? stringField(record, "content")
    ?? "";
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" ? value as Record<string, unknown> : undefined;
}

function stringField(record: Record<string, unknown> | undefined, key: string): string | undefined {
  const value = record?.[key];
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function numberField(record: Record<string, unknown> | undefined, key: string): number | undefined {
  const value = record?.[key];
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function arrayField(record: Record<string, unknown> | undefined, key: string): unknown[] {
  const value = record?.[key];
  return Array.isArray(value) ? value : [];
}
