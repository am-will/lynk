import assert from "node:assert/strict";
import { OpenClawChatBridge } from "./OpenClawChatBridge.js";
import type { BridgeConfig } from "./config.js";
import type { PhoneHub } from "./PhoneHub.js";
import type { ChatOutboundMessage } from "../protocol/messages.js";
import type { ResolvedChatAttachment } from "../attachments/AttachmentTypes.js";
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
  readonly sent: Array<{ sessionKey: string; message: string; attachments?: ResolvedChatAttachment[]; thinking?: string; idempotencyKey?: string }> = [];
  readonly steered: Array<{ sessionKey: string; runId?: string; message: string; attachments?: ResolvedChatAttachment[]; thinking?: string; idempotencyKey?: string }> = [];
  readonly created: Array<{ key?: string; label?: string; model?: string; workspacePath?: string; createWorkspaceIfMissing?: boolean }> = [];
  readonly patched: Array<{ sessionKey: string; patch: Record<string, unknown> }> = [];
  readonly aborted: Array<{ sessionKey: string; runId?: string }> = [];
  readonly permissionReplies: Array<{ sessionKey: string; permissionId: string; response: "once" | "always" | "reject" }> = [];
  sessions: Array<Record<string, unknown>> = [];
  models: Array<Record<string, unknown>> = [];
  commands: Array<Record<string, unknown>> = [];
  readonly duplicateLabels = new Set<string>();
  sendError?: Error;
  patchError?: Error;
  historyError?: Error;
  createGate?: Promise<void>;
  sendGate?: Promise<void>;
  beforeSendResolve?: (
    result: { runId: string; sessionKey: string },
    options: { sessionKey: string; message: string; attachments?: ResolvedChatAttachment[]; thinking?: string; idempotencyKey?: string }
  ) => void;
  healthResponse: unknown = { ok: true, eventLoop: { degraded: false } };
  healthGate?: Promise<void>;
  healthCalls = 0;
  private runCount = 0;

  addEventListener(handler: GatewayEventHandler): () => void {
    this.handlers.add(handler);
    return () => this.handlers.delete(handler);
  }

  async history(sessionKey: string): Promise<unknown> {
    if (this.historyError) throw this.historyError;
    return { sessionId: `${sessionKey}:id`, messages: [] };
  }

  async sendChat(options: { sessionKey: string; message: string; attachments?: ResolvedChatAttachment[]; thinking?: string; idempotencyKey?: string }): Promise<{ runId: string; sessionKey: string }> {
    if (this.sendError) {
      throw this.sendError;
    }
    this.runCount += 1;
    this.sent.push(options);
    const result = { runId: `run_${this.runCount}`, sessionKey: options.sessionKey };
    await this.sendGate;
    this.beforeSendResolve?.(result, options);
    return result;
  }

  async steerChat(options: { sessionKey: string; runId?: string; message: string; attachments?: ResolvedChatAttachment[]; thinking?: string; idempotencyKey?: string }): Promise<{ runId: string; sessionKey: string }> {
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
    await this.createGate;
    if (options.label && this.duplicateLabels.has(options.label)) {
      throw new Error(`Session label "${options.label}" is already used`);
    }
    return { key: `agent:main:explicit:${options.key ?? "created"}`, sessionId: `session_${this.created.length}` };
  }

  async patchSession(sessionKey: string, patch: Record<string, unknown>): Promise<unknown> {
    this.patched.push({ sessionKey, patch });
    if (this.patchError) throw this.patchError;
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
    this.healthCalls += 1;
    await this.healthGate;
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
  const fallbackControl: { gate?: Promise<void>; stops: string[] } = { stops: [] };
  const hub = {
    sendChat(_deviceId: string, message: ChatOutboundMessage) {
      chatMessages.push(message);
    }
  } as unknown as PhoneHub;
  const dispatcher = {
    async handleUserRequest(...args: unknown[]) {
      fallbackCalls.push(args);
      await fallbackControl.gate;
      return { finalMessage: "fallback" };
    },
    async stopActiveTurn(_deviceId: string, reason: string) {
      fallbackControl.stops.push(reason);
    }
  };
  const client = new FakeGatewayClient();
  const blobs = {
    resolve(id: string, owner: { deviceId: string; sessionKey: string }, sha256?: string) {
      return {
        version: 1 as const,
        id,
        ...owner,
        displayName: "photo.png",
        mimeType: "image/png",
        kind: "image" as const,
        sizeBytes: 12,
        sha256: sha256 ?? "a".repeat(64),
        createdAt: 1,
        path: "/private/blob"
      };
    }
  };
  const bridge = new OpenClawChatBridge({ ...config, ...overrides }, hub, dispatcher, undefined, client, blobs);
  return { bridge, chatMessages, client, fallbackCalls, fallbackControl };
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

export function deferred<T = void>(): {
  promise: Promise<T>;
  resolve(value: T): void;
  reject(error: unknown): void;
} {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return { promise, resolve, reject };
}
