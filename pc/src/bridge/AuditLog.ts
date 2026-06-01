import { mkdirSync } from "node:fs";
import { appendFile } from "node:fs/promises";
import { join } from "node:path";
import { defaultHostBridgeConfigDir } from "../host/HostConfigStore.js";

interface AuditEvent {
  id: string;
  timestamp: string;
  turnId?: string;
  deviceId?: string;
  type: string;
  data?: unknown;
}

function sanitize(value: unknown): unknown {
  if (!value || typeof value !== "object") {
    return value;
  }
  if (Array.isArray(value)) {
    return value.map(sanitize);
  }

  const input = value as Record<string, unknown>;
  const output: Record<string, unknown> = {};
  for (const [key, item] of Object.entries(input)) {
    if (key === "screenshotBase64" && typeof item === "string") {
      output[key] = `<base64:${item.length} chars>`;
    } else if (key === "nodes" && Array.isArray(item)) {
      output.nodesCount = item.length;
      output.nodesSample = item.slice(0, 50).map(sanitize);
    } else {
      output[key] = sanitize(item);
    }
  }
  return output;
}

export class AuditLog {
  private readonly events: AuditEvent[] = [];
  private readonly activeTurns = new Map<string, string>();
  private readonly filePath: string;
  private writeChain: Promise<void> = Promise.resolve();

  constructor(
    private readonly maxEvents = 1_000,
    auditDir = defaultAuditDir()
  ) {
    mkdirSync(auditDir, { recursive: true });
    this.filePath = join(auditDir, "phone-agent-audit.jsonl");
  }

  startTurn(deviceId: string, text: string): string {
    const turnId = `turn_${Date.now()}_${Math.random().toString(16).slice(2)}`;
    this.activeTurns.set(deviceId, turnId);
    this.record("turn_started", deviceId, { text }, turnId);
    return turnId;
  }

  endTurn(deviceId: string, data: unknown): void {
    const turnId = this.activeTurns.get(deviceId);
    this.record("turn_ended", deviceId, data, turnId);
    this.activeTurns.delete(deviceId);
  }

  record(type: string, deviceId?: string, data?: unknown, explicitTurnId?: string): void {
    const event: AuditEvent = {
      id: `evt_${Date.now()}_${Math.random().toString(16).slice(2)}`,
      timestamp: new Date().toISOString(),
      turnId: explicitTurnId ?? (deviceId ? this.activeTurns.get(deviceId) : undefined),
      deviceId,
      type,
      data: sanitize(data)
    };
    this.events.push(event);
    if (this.events.length > this.maxEvents) {
      this.events.splice(0, this.events.length - this.maxEvents);
    }
    const line = `${JSON.stringify(event)}\n`;
    this.writeChain = this.writeChain
      .then(() => appendFile(this.filePath, line))
      .catch((error) => {
        console.warn(`[audit] failed to write event ${event.id}: ${error instanceof Error ? error.message : String(error)}`);
      });
  }

  recent(limit = 100): AuditEvent[] {
    return this.events.slice(-limit);
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
}

export function defaultAuditDir(): string {
  return process.env.PHONE_AGENT_AUDIT_DIR?.trim() || join(defaultHostBridgeConfigDir(), "audit");
}
