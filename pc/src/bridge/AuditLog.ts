import { appendFile, chmod, readdir, rm, stat } from "node:fs/promises";
import { join } from "node:path";
import { createHostPaths } from "../host/HostPaths.js";
import { ensurePrivateDirectory } from "../host/PrivatePersistence.js";

export interface AuditEvent {
  id: string;
  timestamp: string;
  turnId?: string;
  deviceId?: string;
  type: string;
  data?: AuditMetadata;
}

export interface AuditLogOptions {
  maxFileBytes?: number;
  maxFileAgeMs?: number;
  maxTotalBytes?: number;
  maxRetentionAgeMs?: number;
  now?: () => number;
}

type AuditScalar = string | number | boolean | null;
type AuditMetadata = Record<string, AuditScalar | Record<string, AuditScalar>>;

const SAFE_SCALAR_KEYS = new Set([
  "id", "callId", "runId", "sessionId", "sessionKey", "threadId", "turnId", "taskId", "itemId",
  "command", "method", "eventType", "harness", "model", "reasoningEffort", "status", "state", "code", "signal",
  "ok", "active", "queued", "position", "count", "chars", "estimatedTokens", "timeoutMs", "durationMs",
  "inputTokens", "outputTokens", "totalTokens", "contextTokens", "attachmentsCount", "nodesCount", "resultCount"
]);
const SAFE_CONTAINERS = new Set(["metrics", "message", "usage"]);
const SENSITIVE_KEYS = /(?:token|password|secret|authorization|prompt|text|guidance|reason|params|result|content|capabilit|attachment|screenshot|nodes|path|cwd|toolOutput|apiKey)/iu;

export class AuditLog {
  private readonly events: AuditEvent[] = [];
  private readonly activeTurns = new Map<string, string>();
  private readonly auditDir: string;
  private readonly options: Required<AuditLogOptions>;
  private currentFilePath: string;
  private currentFileSequence = 0;
  private writeChain: Promise<void>;
  private closed = false;

  constructor(
    private readonly maxEvents = 1_000,
    auditDir = defaultAuditDir(),
    options: AuditLogOptions = {}
  ) {
    this.auditDir = auditDir;
    this.options = {
      maxFileBytes: options.maxFileBytes ?? 2 * 1024 * 1024,
      maxFileAgeMs: options.maxFileAgeMs ?? 24 * 60 * 60 * 1_000,
      maxTotalBytes: options.maxTotalBytes ?? 20 * 1024 * 1024,
      maxRetentionAgeMs: options.maxRetentionAgeMs ?? 14 * 24 * 60 * 60 * 1_000,
      now: options.now ?? Date.now
    };
    this.currentFilePath = join(auditDir, "phone-agent-audit.jsonl");
    this.writeChain = ensurePrivateDirectory(auditDir).then(() => this.cleanupRetention(false));
  }

  startTurn(deviceId: string, _text: string): string {
    const turnId = `turn_${this.options.now()}_${Math.random().toString(16).slice(2)}`;
    this.activeTurns.set(deviceId, turnId);
    this.record("turn_started", deviceId, undefined, turnId);
    return turnId;
  }

  endTurn(deviceId: string, data: unknown): void {
    const turnId = this.activeTurns.get(deviceId);
    this.record("turn_ended", deviceId, data, turnId);
    this.activeTurns.delete(deviceId);
  }

  record(type: string, deviceId?: string, data?: unknown, explicitTurnId?: string): void {
    if (this.closed) return;
    const event: AuditEvent = {
      id: `evt_${this.options.now()}_${Math.random().toString(16).slice(2)}`,
      timestamp: new Date(this.options.now()).toISOString(),
      turnId: explicitTurnId ?? (deviceId ? this.activeTurns.get(deviceId) : undefined),
      ...(deviceId ? { deviceId: deviceId.slice(0, 128) } : {}),
      type: type.slice(0, 128),
      ...metadataField(data)
    };
    this.events.push(event);
    if (this.events.length > this.maxEvents) this.events.splice(0, this.events.length - this.maxEvents);
    const line = `${JSON.stringify(event)}\n`;
    this.writeChain = this.writeChain
      .then(() => this.appendRotated(line))
      .catch((error) => {
        console.warn(`[audit] failed to write event ${event.id}: ${error instanceof Error ? error.message : String(error)}`);
      });
  }

  recent(limit = 100): AuditEvent[] {
    return this.events.slice(-Math.max(0, Math.min(limit, this.maxEvents)));
  }

  active(): Array<{ deviceId: string; turnId: string; events: AuditEvent[] }> {
    return [...this.activeTurns.entries()].map(([deviceId, turnId]) => ({
      deviceId,
      turnId,
      events: this.events.filter((event) => event.turnId === turnId)
    }));
  }

  async flush(): Promise<void> {
    await this.writeChain;
  }

  async close(): Promise<void> {
    this.closed = true;
    await this.flush();
  }

  private async appendRotated(line: string): Promise<void> {
    if (Buffer.byteLength(line) > this.options.maxFileBytes) return;
    const info = await stat(this.currentFilePath).catch(() => undefined);
    if (info && (info.size + Buffer.byteLength(line) > this.options.maxFileBytes
      || this.options.now() - info.mtimeMs >= this.options.maxFileAgeMs)) {
      const rotated = join(this.auditDir, `phone-agent-audit-${this.options.now()}-${this.currentFileSequence++}.jsonl`);
      await import("node:fs/promises").then(({ rename }) => rename(this.currentFilePath, rotated));
      this.currentFilePath = join(this.auditDir, "phone-agent-audit.jsonl");
    }
    await appendFile(this.currentFilePath, line, { encoding: "utf8", mode: 0o600, flag: "a" });
    await chmod(this.currentFilePath, 0o600).catch((error) => {
      if (process.platform !== "win32") throw error;
    });
    await this.cleanupRetention(true);
  }

  private async cleanupRetention(preserveCurrent: boolean): Promise<void> {
    const now = this.options.now();
    const entries = (await readdir(this.auditDir, { withFileTypes: true })).filter((entry) =>
      entry.isFile() && entry.name.startsWith("phone-agent-audit") && entry.name.endsWith(".jsonl")
    );
    const files = (await Promise.all(entries.map(async (entry) => {
      const path = join(this.auditDir, entry.name);
      return { path, info: await stat(path) };
    }))).sort((left, right) => right.info.mtimeMs - left.info.mtimeMs);
    let retainedBytes = 0;
    for (const file of files) {
      const expired = now - file.info.mtimeMs > this.options.maxRetentionAgeMs;
      const overCap = retainedBytes + file.info.size > this.options.maxTotalBytes;
      if ((expired || overCap) && (!preserveCurrent || file.path !== this.currentFilePath)) {
        await rm(file.path, { force: true });
      } else {
        await chmod(file.path, 0o600).catch((error) => {
          if (process.platform !== "win32") throw error;
        });
        retainedBytes += file.info.size;
      }
    }
  }
}

export function defaultAuditDir(): string {
  return process.env.PHONE_AGENT_AUDIT_DIR?.trim() || createHostPaths().auditRoot;
}

export function allowlistedMetadata(value: unknown): AuditMetadata | undefined {
  if (!value || typeof value !== "object" || Array.isArray(value)) return undefined;
  const output: AuditMetadata = {};
  for (const [key, item] of Object.entries(value as Record<string, unknown>)) {
    if (SAFE_SCALAR_KEYS.has(key) && isAuditScalar(item)) {
      output[key] = typeof item === "string" ? item.slice(0, 256) : item;
      continue;
    }
    if (SENSITIVE_KEYS.test(key)) {
      if (Array.isArray(item) && (key === "attachments" || key === "nodes")) output[`${key}Count`] = item.length;
      continue;
    }
    if (SAFE_CONTAINERS.has(key) && item && typeof item === "object" && !Array.isArray(item)) {
      const nested = allowlistedMetadata(item);
      if (nested) {
        const scalars = Object.fromEntries(Object.entries(nested).filter((entry): entry is [string, AuditScalar] => isAuditScalar(entry[1])));
        if (Object.keys(scalars).length > 0) output[key] = scalars;
      }
    }
  }
  return Object.keys(output).length > 0 ? output : undefined;
}

function metadataField(value: unknown): { data?: AuditMetadata } {
  const data = allowlistedMetadata(value);
  return data ? { data } : {};
}

function isAuditScalar(value: unknown): value is AuditScalar {
  return value === null || typeof value === "string" || typeof value === "number" || typeof value === "boolean";
}
