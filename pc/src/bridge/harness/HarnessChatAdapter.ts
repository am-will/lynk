import type {
  ChatCommandOption,
  ChatHistoryMessage,
  ChatModelOption,
  ChatReasoningOption,
  ChatSessionSummary,
  ChatToolSummary
} from "../../protocol/messages.js";
import type { HarnessId } from "../AgentHarness.js";
import type { GatewayChatSendResult, GatewayEventHandler } from "../chat/ChatTransportTypes.js";
import {
  chatMessagesFromHistory,
  normalizeCommands,
  normalizeModels,
  normalizeReasoningOptions,
  normalizeSessions,
  normalizeTools
} from "../chat/ChatNormalizers.js";

export interface HarnessChatHistory {
  sessionId?: string | null;
  thinkingLevel?: string | null;
  messages: ChatHistoryMessage[];
}

export interface HarnessSessionList {
  sessions: ChatSessionSummary[];
  reasoningOptions: ChatReasoningOption[];
}

export interface HarnessCreatedSession {
  key?: string;
  sessionId?: string;
  label?: string | null;
  displayName?: string | null;
  harnessId?: string | null;
  harnessLabel?: string | null;
  workspacePath?: string | null;
  workspaceName?: string | null;
}

export interface HarnessChatAdapter {
  readonly harnessId: HarnessId;
  addEventListener(handler: GatewayEventHandler): () => void;
  history(sessionKey: string): Promise<HarnessChatHistory>;
  sendChat(options: {
    sessionKey: string;
    sessionId?: string;
    message: string;
    thinking?: string;
    idempotencyKey?: string;
  }): Promise<GatewayChatSendResult>;
  steerChat?(options: {
    sessionKey: string;
    sessionId?: string;
    runId?: string;
    message: string;
    thinking?: string;
    idempotencyKey?: string;
  }): Promise<GatewayChatSendResult>;
  abort(sessionKey: string, runId?: string): Promise<unknown>;
  listModels(): Promise<ChatModelOption[]>;
  listSessions(limit?: number): Promise<HarnessSessionList>;
  syncRemoteReplies?(limit?: number): Promise<void>;
  createSession(options: { key?: string; label?: string; model?: string; workspacePath?: string }): Promise<HarnessCreatedSession>;
  patchSession(sessionKey: string, patch: Record<string, unknown>): Promise<unknown>;
  listCommands(sessionKey?: string): Promise<ChatCommandOption[]>;
  effectiveTools(sessionKey: string): Promise<ChatToolSummary[]>;
  health(): Promise<unknown>;
  close(): void;
}

interface RawHarnessClient {
  addEventListener(handler: GatewayEventHandler): () => void;
  history(sessionKey: string): Promise<unknown>;
  sendChat(options: {
    sessionKey: string;
    sessionId?: string;
    message: string;
    thinking?: string;
    idempotencyKey?: string;
  }): Promise<GatewayChatSendResult>;
  steerChat?(options: {
    sessionKey: string;
    sessionId?: string;
    runId?: string;
    message: string;
    thinking?: string;
    idempotencyKey?: string;
  }): Promise<GatewayChatSendResult>;
  abort(sessionKey: string, runId?: string): Promise<unknown>;
  listModels(): Promise<unknown>;
  listSessions(limit?: number): Promise<unknown>;
  syncRemoteReplies?(limit?: number): Promise<void>;
  createSession(options: { key?: string; label?: string; model?: string; workspacePath?: string }): Promise<unknown>;
  patchSession(sessionKey: string, patch: Record<string, unknown>): Promise<unknown>;
  listCommands(sessionKey?: string): Promise<unknown>;
  effectiveTools(sessionKey: string): Promise<unknown>;
  health(): Promise<unknown>;
  close(): void;
}

export class NormalizedHarnessAdapter implements HarnessChatAdapter {
  constructor(
    readonly harnessId: HarnessId,
    private readonly client: RawHarnessClient
  ) {}

  addEventListener(handler: GatewayEventHandler): () => void {
    return this.client.addEventListener(handler);
  }

  async history(sessionKey: string): Promise<HarnessChatHistory> {
    const payload = await this.client.history(sessionKey);
    const record = asRecord(payload);
    return {
      sessionId: stringField(record, "sessionId") ?? null,
      thinkingLevel: stringField(record, "thinkingLevel") ?? null,
      messages: chatMessagesFromHistory(payload)
    };
  }

  async sendChat(options: {
    sessionKey: string;
    sessionId?: string;
    message: string;
    thinking?: string;
    idempotencyKey?: string;
  }): Promise<GatewayChatSendResult> {
    return await this.client.sendChat(options);
  }

  async steerChat(options: {
    sessionKey: string;
    sessionId?: string;
    runId?: string;
    message: string;
    thinking?: string;
    idempotencyKey?: string;
  }): Promise<GatewayChatSendResult> {
    if (!this.client.steerChat) {
      throw new Error(`${this.harnessId} harness does not support active-turn steering.`);
    }
    return await this.client.steerChat(options);
  }

  async abort(sessionKey: string, runId?: string): Promise<unknown> {
    return await this.client.abort(sessionKey, runId);
  }

  async listModels(): Promise<ChatModelOption[]> {
    return normalizeModels(await this.client.listModels());
  }

  async listSessions(limit?: number): Promise<HarnessSessionList> {
    const payload = await this.client.listSessions(limit);
    return {
      sessions: normalizeSessions(payload),
      reasoningOptions: normalizeReasoningOptions(payload)
    };
  }

  async syncRemoteReplies(limit?: number): Promise<void> {
    await this.client.syncRemoteReplies?.(limit);
  }

  async createSession(options: { key?: string; label?: string; model?: string; workspacePath?: string }): Promise<HarnessCreatedSession> {
    const created = await this.client.createSession(options);
    const record = asRecord(created) ?? {};
    return {
      key: stringField(record, "key"),
      sessionId: stringField(record, "sessionId"),
      label: stringField(record, "label") ?? null,
      displayName: stringField(record, "displayName") ?? null,
      workspacePath: stringField(record, "workspacePath") ?? null,
      workspaceName: stringField(record, "workspaceName") ?? null
    };
  }

  async patchSession(sessionKey: string, patch: Record<string, unknown>): Promise<unknown> {
    return await this.client.patchSession(sessionKey, patch);
  }

  async listCommands(sessionKey?: string): Promise<ChatCommandOption[]> {
    return normalizeCommands(await this.client.listCommands(sessionKey));
  }

  async effectiveTools(sessionKey: string): Promise<ChatToolSummary[]> {
    return normalizeTools(await this.client.effectiveTools(sessionKey));
  }

  async health(): Promise<unknown> {
    return await this.client.health();
  }

  close(): void {
    this.client.close();
  }
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" ? value as Record<string, unknown> : undefined;
}

function stringField(record: Record<string, unknown> | undefined, key: string): string | undefined {
  const value = record?.[key];
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}
