import assert from "node:assert/strict";
import { OpenClawChatBridge } from "./OpenClawChatBridge.js";
import type { BridgeConfig } from "./config.js";
import type { PhoneHub } from "./PhoneHub.js";
import type { ChatAttachment, ChatOutboundMessage } from "../protocol/messages.js";
import type { GatewayEvent, GatewayEventHandler } from "./OpenClawGatewayChatClient.js";

export const config: BridgeConfig = {
  host: "127.0.0.1",
  port: 8788,
  token: "token",
  defaultDeviceId: "pixel",
  bridgeUrl: "http://127.0.0.1:8788",
  openClawGatewayUrl: "ws://127.0.0.1:18789",
  openClawChatAgentId: "main",
  openClawChatSessionKey: "agent:main:explicit:open-claw-agent",
  hermesApiBaseUrl: "http://127.0.0.1:8642/v1",
  hermesModel: "hermes-agent",
  hermesDefaultSessionId: "hermes-agent",
  hermesRunTimeoutMs: 600_000,
  openAiRealtimeModel: "gpt-realtime-2",
  openAiRealtimeVoice: "marin",
  openAiWebSearchModel: "gpt-5.5",
  configPath: "/tmp/android-agent-bridge/config.json",
  codexAppServerCommand: "codex app-server --listen stdio://",
  codexAgentCwd: "/tmp",
  codexAppServerApprovalPolicy: "never",
  codexAppServerSandbox: "workspace-write",
  codexConfigured: true,
  piAgentCwd: "/tmp",
  piRunTimeoutMs: 600_000,
  piConfigured: true,
  devinAcpCommand: "devin acp",
  devinAgentCwd: "/tmp",
  devinRunTimeoutMs: 600_000,
  devinConfigured: false
};

export class FakeGatewayClient {
  readonly handlers = new Set<GatewayEventHandler>();
  readonly sent: Array<{ sessionKey: string; message: string; attachments?: ChatAttachment[]; thinking?: string; idempotencyKey?: string }> = [];
  readonly steered: Array<{ sessionKey: string; runId?: string; message: string; attachments?: ChatAttachment[]; thinking?: string; idempotencyKey?: string }> = [];
  readonly created: Array<{ key?: string; label?: string; model?: string; workspacePath?: string; createWorkspaceIfMissing?: boolean }> = [];
  readonly patched: Array<{ sessionKey: string; patch: Record<string, unknown> }> = [];
  readonly aborted: Array<{ sessionKey: string; runId?: string }> = [];
  readonly permissionReplies: Array<{ sessionKey: string; permissionId: string; response: "once" | "always" | "reject" }> = [];
  sessions: Array<Record<string, unknown>> = [];
  models: Array<Record<string, unknown>> = [];
  commands: Array<Record<string, unknown>> = [];
  readonly duplicateLabels = new Set<string>();
  sendError?: Error;
  healthResponse: unknown = { ok: true, eventLoop: { degraded: false } };
  private runCount = 0;

  addEventListener(handler: GatewayEventHandler): () => void {
    this.handlers.add(handler);
    return () => this.handlers.delete(handler);
  }

  async history(sessionKey: string): Promise<unknown> {
    return { sessionId: `${sessionKey}:id`, messages: [] };
  }

  async sendChat(options: { sessionKey: string; message: string; attachments?: ChatAttachment[]; thinking?: string; idempotencyKey?: string }): Promise<{ runId: string; sessionKey: string }> {
    if (this.sendError) {
      throw this.sendError;
    }
    this.runCount += 1;
    this.sent.push(options);
    return { runId: `run_${this.runCount}`, sessionKey: options.sessionKey };
  }

  async steerChat(options: { sessionKey: string; runId?: string; message: string; attachments?: ChatAttachment[]; thinking?: string; idempotencyKey?: string }): Promise<{ runId: string; sessionKey: string }> {
    this.steered.push(options);
    return { runId: options.runId ?? `run_${this.runCount}`, sessionKey: options.sessionKey };
  }

  async abort(sessionKey: string, runId?: string): Promise<unknown> {
    this.aborted.push({ sessionKey, runId });
    return { ok: true };
  }

  async listModels(): Promise<unknown> {
    return { models: this.models };
  }

  async listSessions(): Promise<unknown> {
    return { sessions: this.sessions };
  }

  async createSession(options: { key?: string; label?: string; model?: string; workspacePath?: string; createWorkspaceIfMissing?: boolean }): Promise<unknown> {
    this.created.push(options);
    if (options.label && this.duplicateLabels.has(options.label)) {
      throw new Error(`Session label "${options.label}" is already used`);
    }
    return { key: `agent:main:explicit:${options.key ?? "created"}`, sessionId: `session_${this.created.length}` };
  }

  async patchSession(sessionKey: string, patch: Record<string, unknown>): Promise<unknown> {
    this.patched.push({ sessionKey, patch });
    return { ok: true };
  }

  async listCommands(): Promise<unknown> {
    return { commands: this.commands };
  }

  async effectiveTools(): Promise<unknown> {
    return { groups: [] };
  }

  async respondToPermission(options: { sessionKey: string; permissionId: string; response: "once" | "always" | "reject" }): Promise<unknown> {
    this.permissionReplies.push(options);
    return { ok: true };
  }

  async health(): Promise<unknown> {
    return this.healthResponse;
  }

  close(): void {}

  emit(event: GatewayEvent): void {
    for (const handler of this.handlers) {
      handler(event);
    }
  }
}

export function createHarness(overrides: Partial<BridgeConfig> = {}) {
  const chatMessages: ChatOutboundMessage[] = [];
  const fallbackCalls: unknown[] = [];
  const hub = {
    sendChat(_deviceId: string, message: ChatOutboundMessage) {
      chatMessages.push(message);
    }
  } as unknown as PhoneHub;
  const dispatcher = {
    async handleUserRequest(...args: unknown[]) {
      fallbackCalls.push(args);
      return { finalMessage: "fallback" };
    },
    async stopActiveTurn() {}
  };
  const client = new FakeGatewayClient();
  const bridge = new OpenClawChatBridge({ ...config, ...overrides }, hub, dispatcher, undefined, client);
  return { bridge, chatMessages, client, fallbackCalls };
}

export function defaultSessionKey(deviceId: string): string {
  return `agent:main:explicit:open-claw-agent-${deviceId}`;
}

export async function waitFor(predicate: () => boolean): Promise<void> {
  for (let attempt = 0; attempt < 50; attempt += 1) {
    if (predicate()) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 1));
  }
  assert.ok(predicate());
}
