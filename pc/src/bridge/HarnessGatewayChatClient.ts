import type { AuditLog } from "./AuditLog.js";
import {
  DEFAULT_HARNESS_ID,
  defaultSessionKeyForHarness,
  encodeHarnessModel,
  harnessForSessionKey,
  harnessInfos,
  namespaceModelOption,
  namespaceSessionSummary,
  parseHarnessModel,
  type HarnessId
} from "./AgentHarness.js";
import { CodexChatClient } from "./CodexChatClient.js";
import type { BridgeConfig } from "./config.js";
import { HermesChatClient } from "./HermesChatClient.js";
import type { GatewayChatClient } from "./OpenClawChatTypes.js";
import {
  normalizeModels,
  normalizeReasoningOptions,
  normalizeSessions,
  OpenClawGatewayChatClient,
  type GatewayChatSendResult,
  type GatewayEventHandler
} from "./OpenClawGatewayChatClient.js";

export class HarnessGatewayChatClient implements GatewayChatClient {
  private readonly clients = new Map<HarnessId, GatewayChatClient>();

  constructor(
    private readonly config: BridgeConfig,
    audit?: AuditLog
  ) {
    this.clients.set("openclaw", new OpenClawGatewayChatClient(config));
    if (config.hermesApiKey) {
      this.clients.set("hermes", new HermesChatClient(config));
    }
    this.clients.set("codex", new CodexChatClient(audit) as unknown as GatewayChatClient);
  }

  addEventListener(handler: GatewayEventHandler): () => void {
    const unsubscribers = [...this.clients.values()].map((client) => client.addEventListener(handler));
    return () => {
      for (const unsubscribe of unsubscribers) {
        unsubscribe();
      }
    };
  }

  async history(sessionKey: string): Promise<unknown> {
    return await this.clientForSession(sessionKey).history(sessionKey);
  }

  async sendChat(options: {
    sessionKey: string;
    sessionId?: string;
    message: string;
    thinking?: string;
    idempotencyKey?: string;
  }): Promise<GatewayChatSendResult> {
    return await this.clientForSession(options.sessionKey).sendChat(options);
  }

  async abort(sessionKey: string, runId?: string): Promise<unknown> {
    return await this.clientForSession(sessionKey).abort(sessionKey, runId);
  }

  async listModels(): Promise<unknown> {
    const models = [];
    for (const info of harnessInfos(this.config)) {
      if (!info.enabled) {
        continue;
      }
      const client = this.clients.get(info.id);
      if (!client) {
        continue;
      }
      const payload = await client.listModels().catch(() => undefined);
      for (const model of normalizeModels(payload)) {
        models.push(namespaceModelOption(model, info.id));
      }
    }
    return { models };
  }

  async listSessions(limit = 50, harnessId: HarnessId = DEFAULT_HARNESS_ID): Promise<unknown> {
    const client = this.clientForHarness(harnessId);
    const payload = await client.listSessions(limit, harnessId);
    const sessions = normalizeSessions(payload).map((session) => namespaceSessionSummary(session, harnessId));
    return {
      sessions,
      defaults: {
        thinkingLevels: normalizeReasoningOptions(payload).map((option) => option.id)
      }
    };
  }

  async createSession(options: { key?: string; label?: string; model?: string }): Promise<unknown> {
    const selection = parseHarnessModel(options.model);
    const harnessId = selection?.harnessId ?? harnessForSessionKey(options.key);
    const client = this.clientForHarness(harnessId);
    const key = this.keyForHarness(harnessId, options.key);
    const created = await client.createSession({
      ...options,
      key: harnessId === "openclaw" ? key : key,
      model: selection?.modelId ?? options.model
    });
    return this.namespaceCreatedSession(created, harnessId, key);
  }

  async patchSession(sessionKey: string, patch: Record<string, unknown>): Promise<unknown> {
    const harnessId = harnessForSessionKey(sessionKey);
    const modelSelection = typeof patch.model === "string" ? parseHarnessModel(patch.model) : undefined;
    const nextPatch = {
      ...patch,
      ...(modelSelection ? { model: modelSelection.modelId } : {})
    };
    return await this.clientForHarness(harnessId).patchSession(sessionKey, nextPatch);
  }

  async listCommands(sessionKey?: string): Promise<unknown> {
    return await this.clientForSession(sessionKey).listCommands(sessionKey);
  }

  async effectiveTools(sessionKey: string): Promise<unknown> {
    return await this.clientForSession(sessionKey).effectiveTools(sessionKey);
  }

  async health(): Promise<unknown> {
    const health: Record<string, unknown> = {};
    for (const [harnessId, client] of this.clients.entries()) {
      health[harnessId] = await client.health().catch((error) => ({
        ok: false,
        error: error instanceof Error ? error.message : String(error)
      }));
    }
    return { harnesses: health };
  }

  close(): void {
    for (const client of this.clients.values()) {
      client.close();
    }
  }

  defaultSessionKey(harnessId: HarnessId, deviceId: string): string {
    return defaultSessionKeyForHarness(harnessId, this.config, deviceId);
  }

  encodeModel(harnessId: HarnessId, modelId: string): string {
    return encodeHarnessModel(harnessId, modelId);
  }

  private clientForSession(sessionKey: string | undefined): GatewayChatClient {
    return this.clientForHarness(harnessForSessionKey(sessionKey));
  }

  private clientForHarness(harnessId: HarnessId): GatewayChatClient {
    const client = this.clients.get(harnessId);
    if (!client) {
      throw new Error(`${harnessId} harness is not configured.`);
    }
    return client;
  }

  private keyForHarness(harnessId: HarnessId, key: string | undefined): string | undefined {
    if (!key || harnessId === "openclaw" || key.startsWith(`${harnessId}:`)) {
      return key;
    }
    return `${harnessId}:${key}`;
  }

  private namespaceCreatedSession(created: unknown, harnessId: HarnessId, fallbackKey?: string): unknown {
    const record = created && typeof created === "object" ? created as Record<string, unknown> : {};
    const rawKey = typeof record.key === "string" && record.key.trim() ? record.key.trim() : fallbackKey;
    const key = harnessId === "openclaw" || !rawKey || rawKey.startsWith(`${harnessId}:`)
      ? rawKey
      : `${harnessId}:${rawKey}`;
    return {
      ...record,
      ...(key ? { key } : {}),
      harnessId,
      harnessLabel: harnessInfos(this.config).find((info) => info.id === harnessId)?.label
    };
  }
}
