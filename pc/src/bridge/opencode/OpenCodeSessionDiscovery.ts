import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";

export interface OpenCodeStoredSession {
  id: string;
  title?: string;
  directory?: string;
  time?: {
    created?: number;
    updated?: number;
  };
  model?: string;
  inputTokens?: number;
  outputTokens?: number;
  reasoningTokens?: number;
  totalTokens?: number;
  estimatedCostUsd?: number;
  userMessageCount: number;
  source: "opencode-storage";
}

export interface OpenCodeSessionDiscoveryOptions {
  dataDir?: string;
  homeDir?: string;
}

interface PartialSession {
  id: string;
  title?: string;
  directory?: string;
  created?: number;
  updated?: number;
  model?: string;
  inputTokens?: number;
  outputTokens?: number;
  reasoningTokens?: number;
  cacheReadTokens?: number;
  cacheWriteTokens?: number;
  estimatedCostUsd?: number;
  userMessageCount: number;
}

export function defaultOpenCodeDataDir(homeDir = homedir()): string {
  const configured = process.env.OPENCODE_DATA_DIR?.trim();
  if (configured) {
    return expandHome(configured, homeDir);
  }
  const xdgDataHome = process.env.XDG_DATA_HOME?.trim();
  return xdgDataHome ? join(expandHome(xdgDataHome, homeDir), "opencode") : join(homeDir, ".local", "share", "opencode");
}

export function listOpenCodeStoredSessions(options: OpenCodeSessionDiscoveryOptions = {}): OpenCodeStoredSession[] {
  const dataDir = options.dataDir?.trim() || defaultOpenCodeDataDir(options.homeDir);
  const byId = new Map<string, PartialSession>();

  for (const session of discoverSqliteSessions(dataDir)) {
    mergeSession(byId, session);
  }
  for (const session of discoverProjectStorageSessions(dataDir)) {
    mergeSession(byId, session);
  }

  return [...byId.values()]
    .filter((session) => session.userMessageCount > 0)
    .sort((left, right) => (right.updated ?? right.created ?? 0) - (left.updated ?? left.created ?? 0))
    .map((session) => ({
      id: session.id,
      title: session.title,
      directory: session.directory,
      time: {
        created: session.created,
        updated: session.updated
      },
      model: session.model,
      inputTokens: session.inputTokens,
      outputTokens: session.outputTokens,
      reasoningTokens: session.reasoningTokens,
      totalTokens: [
        session.inputTokens,
        session.outputTokens,
        session.reasoningTokens,
        session.cacheReadTokens,
        session.cacheWriteTokens
      ].filter((value): value is number => value !== undefined)
        .reduce((sum, value) => sum + value, 0) || undefined,
      estimatedCostUsd: session.estimatedCostUsd,
      userMessageCount: session.userMessageCount,
      source: "opencode-storage"
    }));
}

function discoverSqliteSessions(dataDir: string): PartialSession[] {
  const dbPath = join(dataDir, "opencode.db");
  if (!existsSync(dbPath)) {
    return [];
  }
  let db: DatabaseSync | undefined;
  try {
    db = new DatabaseSync(dbPath, { readOnly: true });
    const rows = db.prepare(`
      SELECT
        s.id,
        s.title,
        s.directory,
        s.time_created AS timeCreated,
        s.time_updated AS timeUpdated,
        s.model,
        s.cost,
        s.tokens_input AS tokensInput,
        s.tokens_output AS tokensOutput,
        s.tokens_reasoning AS tokensReasoning,
        s.tokens_cache_read AS tokensCacheRead,
        s.tokens_cache_write AS tokensCacheWrite,
        (
          SELECT COUNT(*)
          FROM message m
          WHERE m.session_id = s.id
            AND json_extract(m.data, '$.role') = 'user'
        ) AS userMessageCount
      FROM session s
      WHERE s.time_archived IS NULL
      ORDER BY s.time_updated DESC
    `).all() as Array<Record<string, unknown>>;
    return rows
      .map((row) => ({
        id: stringField(row, "id") ?? "",
        title: stringField(row, "title"),
        directory: stringField(row, "directory"),
        created: numberField(row, "timeCreated"),
        updated: numberField(row, "timeUpdated"),
        model: modelIdFromStoredModel(row.model),
        inputTokens: numberField(row, "tokensInput"),
        outputTokens: numberField(row, "tokensOutput"),
        reasoningTokens: numberField(row, "tokensReasoning"),
        cacheReadTokens: numberField(row, "tokensCacheRead"),
        cacheWriteTokens: numberField(row, "tokensCacheWrite"),
        estimatedCostUsd: numberField(row, "cost"),
        userMessageCount: numberField(row, "userMessageCount") ?? 0
      }))
      .filter((session) => session.id);
  } catch {
    return [];
  } finally {
    db?.close();
  }
}

function discoverProjectStorageSessions(dataDir: string): PartialSession[] {
  const projectRoot = join(dataDir, "project");
  if (!isDirectory(projectRoot)) {
    return [];
  }
  const byId = new Map<string, PartialSession>();
  for (const projectSlug of readdirSafe(projectRoot)) {
    const storageRoot = join(projectRoot, projectSlug, "storage");
    if (!isDirectory(storageRoot)) {
      continue;
    }
    for (const file of jsonFiles(storageRoot)) {
      const parsed = parseJson(readFileSync(file, "utf8"));
      collectLegacyStorageRecords(parsed, byId);
    }
  }
  return [...byId.values()];
}

function collectLegacyStorageRecords(value: unknown, byId: Map<string, PartialSession>): void {
  if (Array.isArray(value)) {
    value.forEach((item) => collectLegacyStorageRecords(item, byId));
    return;
  }
  const record = asRecord(value);
  if (!record) {
    return;
  }

  const sessionId = sessionIdFromRecord(record);
  if (sessionId) {
    const existing = byId.get(sessionId) ?? { id: sessionId, userMessageCount: 0 };
    const userMessages = countUserMessages(record);
    byId.set(sessionId, {
      ...existing,
      title: stringField(record, "title") ?? stringField(record, "name") ?? existing.title,
      directory: directoryFromRecord(record) ?? existing.directory,
      created: timeFromRecord(record, "created") ?? existing.created,
      updated: timeFromRecord(record, "updated") ?? existing.updated,
      model: modelIdFromStoredModel(record.model) ?? existing.model,
      userMessageCount: Math.max(existing.userMessageCount, userMessages)
    });
  }

  for (const child of Object.values(record)) {
    if (child && typeof child === "object") {
      collectLegacyStorageRecords(child, byId);
    }
  }
}

function mergeSession(byId: Map<string, PartialSession>, session: PartialSession): void {
  const existing = byId.get(session.id);
  if (!existing) {
    byId.set(session.id, session);
    return;
  }
  byId.set(session.id, {
    ...existing,
    ...session,
    userMessageCount: Math.max(existing.userMessageCount, session.userMessageCount)
  });
}

function jsonFiles(root: string): string[] {
  const files: string[] = [];
  for (const entry of readdirSafe(root)) {
    const path = join(root, entry);
    if (isDirectory(path)) {
      files.push(...jsonFiles(path));
    } else if (path.endsWith(".json")) {
      files.push(path);
    }
  }
  return files;
}

function countUserMessages(value: unknown): number {
  if (Array.isArray(value)) {
    return value.reduce((count, item) => count + countUserMessages(item), 0);
  }
  const record = asRecord(value);
  if (!record) {
    return 0;
  }
  const data = typeof record.data === "string" ? asRecord(parseJson(record.data)) : asRecord(record.data);
  const role = stringField(record, "role") ?? stringField(asRecord(record.info), "role") ?? stringField(data, "role");
  const self = role === "user" ? 1 : 0;
  return self + Object.values(record)
    .filter((child) => child && typeof child === "object")
    .reduce<number>((count, child) => count + countUserMessages(child), 0);
}

function sessionIdFromRecord(record: Record<string, unknown>): string | undefined {
  const id = stringField(record, "id") ?? stringField(record, "sessionID") ?? stringField(record, "sessionId") ?? stringField(record, "session_id");
  if (!id) {
    return undefined;
  }
  return id.startsWith("ses_") || directoryFromRecord(record) || Array.isArray(record.messages) ? id : undefined;
}

function directoryFromRecord(record: Record<string, unknown>): string | undefined {
  return stringField(record, "directory")
    ?? stringField(record, "cwd")
    ?? stringField(asRecord(record.path), "cwd")
    ?? stringField(asRecord(asRecord(record.info)?.path), "cwd");
}

function timeFromRecord(record: Record<string, unknown>, key: "created" | "updated"): number | undefined {
  return numberField(record, `time_${key}`)
    ?? numberField(record, key === "created" ? "timeCreated" : "timeUpdated")
    ?? numberField(asRecord(record.time), key);
}

function modelIdFromStoredModel(value: unknown): string | undefined {
  const model = typeof value === "string" ? parseJson(value) ?? value : value;
  if (typeof model === "string") {
    return model.trim() || undefined;
  }
  const record = asRecord(model);
  const provider = stringField(record, "providerID") ?? stringField(record, "providerId") ?? stringField(record, "provider");
  const id = stringField(record, "modelID") ?? stringField(record, "modelId") ?? stringField(record, "id");
  return provider && id ? `${provider}/${id}` : id;
}

function expandHome(path: string, homeDir: string): string {
  if (path === "~") {
    return homeDir;
  }
  return path.startsWith("~/") ? join(homeDir, path.slice(2)) : path;
}

function isDirectory(path: string): boolean {
  try {
    return statSync(path).isDirectory();
  } catch {
    return false;
  }
}

function readdirSafe(path: string): string[] {
  try {
    return readdirSync(path);
  } catch {
    return [];
  }
}

function parseJson(value: string): unknown {
  try {
    return JSON.parse(value);
  } catch {
    return undefined;
  }
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
