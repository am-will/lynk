import { randomUUID } from "node:crypto";
import { WebSocket } from "ws";
import type { BridgeConfig } from "./config.js";
import type { GatewayChatSendResult, GatewayEvent, GatewayEventHandler } from "./chat/ChatTransportTypes.js";
import { asRecord, stringField } from "./chat/ChatNormalizers.js";
export type { GatewayChatSendResult, GatewayEvent, GatewayEventHandler } from "./chat/ChatTransportTypes.js";
export {
  chatMessagesFromHistory,
  enrichSessionsWithModelContext,
  extractGatewayText,
  mapGatewayChatEvent,
  normalizeCommands,
  normalizeGatewayReasoningEvent,
  normalizeGatewayToolEvent,
  normalizeHistoryMessage,
  normalizeModels,
  normalizeReasoningOptions,
  normalizeSessions,
  normalizeTools,
  requestKeyFromSessionKey,
  usageFromSession
} from "./chat/ChatNormalizers.js";

interface PendingRequest {
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
}

const DEFAULT_REQUEST_TIMEOUT_MS = 30_000;
const DEFAULT_CONNECT_TIMEOUT_MS = 10_000;

export class OpenClawGatewayChatClient {
  private socket?: WebSocket;
  private connectPromise?: Promise<void>;
  private connected = false;
  private readonly pending = new Map<string, PendingRequest>();
  private readonly handlers = new Set<GatewayEventHandler>();

  constructor(private readonly config: BridgeConfig) {}

  addEventListener(handler: GatewayEventHandler): () => void {
    this.handlers.add(handler);
    return () => this.handlers.delete(handler);
  }

  async history(sessionKey: string): Promise<unknown> {
    return await this.request("chat.history", { sessionKey, limit: 100, maxChars: 12_000 });
  }

  async sendChat(options: {
    sessionKey: string;
    sessionId?: string;
    message: string;
    thinking?: string;
    idempotencyKey?: string;
  }): Promise<GatewayChatSendResult> {
    const idempotencyKey = options.idempotencyKey ?? randomUUID();
    const payload = await this.request("chat.send", {
      sessionKey: options.sessionKey,
      ...(options.sessionId ? { sessionId: options.sessionId } : {}),
      message: options.message,
      ...(options.thinking ? { thinking: options.thinking } : {}),
      idempotencyKey
    });
    const record = asRecord(payload);
    return {
      runId: stringField(record, "runId") ?? idempotencyKey,
      sessionKey: stringField(record, "sessionKey") ?? options.sessionKey
    };
  }

  async steerChat(options: {
    sessionKey: string;
    sessionId?: string;
    runId?: string;
    message: string;
    thinking?: string;
    idempotencyKey?: string;
  }): Promise<GatewayChatSendResult> {
    return await this.sendChat({
      ...options,
      message: `/steer ${options.message}`
    });
  }

  async abort(sessionKey: string, runId?: string): Promise<unknown> {
    return await this.request("chat.abort", {
      sessionKey,
      ...(runId ? { runId } : {})
    });
  }

  async listModels(): Promise<unknown> {
    return await this.request("models.list", { view: "configured" });
  }

  async listSessions(limit = 50): Promise<unknown> {
    return await this.request("sessions.list", { limit });
  }

  async createSession(options: { key?: string; label?: string; model?: string; workspacePath?: string }): Promise<unknown> {
    return await this.request("sessions.create", {
      agentId: this.config.openClawChatAgentId,
      ...(options.key ? { key: options.key } : {}),
      ...(options.label ? { label: options.label } : {}),
      ...(options.model ? { model: options.model } : {})
    });
  }

  async patchSession(sessionKey: string, patch: Record<string, unknown>): Promise<unknown> {
    return await this.request("sessions.patch", {
      key: sessionKey,
      ...patch
    });
  }

  async listCommands(): Promise<unknown> {
    return await this.request("commands.list", { includeArgs: true });
  }

  async effectiveTools(sessionKey: string): Promise<unknown> {
    return await this.request("tools.effective", {
      agentId: this.config.openClawChatAgentId,
      sessionKey
    });
  }

  async health(): Promise<unknown> {
    return await this.request("health", {});
  }

  async request(method: string, params: Record<string, unknown>, timeoutMs = DEFAULT_REQUEST_TIMEOUT_MS): Promise<unknown> {
    await this.connect();
    const socket = this.socket;
    if (!socket || socket.readyState !== WebSocket.OPEN) {
      throw new Error("OpenClaw Gateway is not connected");
    }

    const id = `oc_${Date.now()}_${randomUUID()}`;
    return await new Promise<unknown>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`OpenClaw Gateway request ${method} timed out after ${timeoutMs}ms`));
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      socket.send(JSON.stringify({ type: "req", id, method, params }), (error) => {
        if (error) {
          clearTimeout(timer);
          this.pending.delete(id);
          reject(error);
        }
      });
    });
  }

  close(): void {
    this.socket?.close(1000, "bridge stopped");
    this.socket = undefined;
    this.connected = false;
    this.connectPromise = undefined;
  }

  private async connect(): Promise<void> {
    if (this.connected && this.socket?.readyState === WebSocket.OPEN) {
      return;
    }
    if (this.connectPromise) {
      return await this.connectPromise;
    }

    this.connectPromise = new Promise<void>((resolve, reject) => {
      const socket = new WebSocket(this.config.openClawGatewayUrl);
      this.socket = socket;
      const timer = setTimeout(() => {
        reject(new Error(`Timed out connecting to OpenClaw Gateway at ${this.config.openClawGatewayUrl}`));
        socket.close();
      }, DEFAULT_CONNECT_TIMEOUT_MS);

      socket.on("open", () => {
        const id = `connect_${randomUUID()}`;
        const auth: Record<string, string> = {};
        if (this.config.openClawGatewayToken) {
          auth.token = this.config.openClawGatewayToken;
        }
        if (this.config.openClawGatewayPassword) {
          auth.password = this.config.openClawGatewayPassword;
        }
        this.pending.set(id, {
          resolve: () => {
            clearTimeout(timer);
            this.connected = true;
            resolve();
          },
          reject: (error) => {
            clearTimeout(timer);
            reject(error);
          },
          timer
        });
        socket.send(JSON.stringify({
          type: "req",
          id,
          method: "connect",
          params: {
            minProtocol: 3,
            maxProtocol: 4,
            client: {
              id: "gateway-client",
              version: "open-claw-agent-pc",
              platform: process.platform,
              mode: "backend"
            },
            role: "operator",
            scopes: ["operator.read", "operator.write"],
            caps: ["tool-events"],
            commands: [],
            permissions: {},
            auth,
            locale: "en-US",
            userAgent: "open-claw-agent-pc"
          }
        }));
      });

      socket.on("message", (data) => this.handleFrame(data.toString()));
      socket.on("error", (error) => {
        clearTimeout(timer);
        if (!this.connected) {
          reject(error);
        }
      });
      socket.on("close", (_code, reason) => {
        clearTimeout(timer);
        this.connected = false;
        this.connectPromise = undefined;
        this.rejectPending(new Error(`OpenClaw Gateway connection closed${reason.length ? `: ${reason.toString()}` : ""}`));
      });
    });

    try {
      await this.connectPromise;
    } catch (error) {
      this.connectPromise = undefined;
      throw error;
    }
  }

  private handleFrame(text: string): void {
    let frame: unknown;
    try {
      frame = JSON.parse(text);
    } catch {
      return;
    }
    const record = asRecord(frame);
    if (!record) {
      return;
    }
    if (record.type === "res" && typeof record.id === "string") {
      const pending = this.pending.get(record.id);
      if (!pending) {
        return;
      }
      clearTimeout(pending.timer);
      this.pending.delete(record.id);
      if (record.ok === true) {
        pending.resolve(record.payload);
      } else {
        const error = asRecord(record.error);
        pending.reject(new Error(stringField(error, "message") ?? `OpenClaw Gateway request ${record.id} failed`));
      }
      return;
    }
    if (record.type === "event" && typeof record.event === "string") {
      const event: GatewayEvent = {
        event: record.event,
        payload: record.payload,
        seq: typeof record.seq === "number" ? record.seq : undefined
      };
      for (const handler of this.handlers) {
        handler(event);
      }
    }
  }

  private rejectPending(error: Error): void {
    for (const [id, pending] of this.pending) {
      clearTimeout(pending.timer);
      pending.reject(error);
      this.pending.delete(id);
    }
  }
}
