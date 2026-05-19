import { randomUUID } from "node:crypto";
import { WebSocket } from "ws";
import type { BridgeConfig } from "./config.js";
import type {
  ChatCommandOption,
  ChatHistoryMessage,
  ChatModelOption,
  ChatOutboundMessage,
  ChatReasoningDeltaMessage,
  ChatReasoningOption,
  ChatSessionSummary,
  ChatToolEventMessage,
  ChatToolSummary,
  ChatUsageSummary
} from "../protocol/messages.js";

interface PendingRequest {
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
}

export interface GatewayEvent {
  event: string;
  payload: unknown;
  seq?: number;
}

export interface GatewayChatSendResult {
  runId: string;
  sessionKey: string;
}

export type GatewayEventHandler = (event: GatewayEvent) => void;

const DEFAULT_REQUEST_TIMEOUT_MS = 30_000;
const DEFAULT_CONNECT_TIMEOUT_MS = 10_000;
const FALLBACK_REASONING_OPTIONS: ChatReasoningOption[] = [
  { id: "low", label: "low" },
  { id: "medium", label: "medium" },
  { id: "high", label: "high" },
  { id: "xhigh", label: "xhigh" }
];
const ALLOWED_REASONING_OPTION_IDS = new Set(FALLBACK_REASONING_OPTIONS.map((option) => option.id));

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" ? value as Record<string, unknown> : undefined;
}

function stringField(record: Record<string, unknown> | undefined, key: string): string | undefined {
  const value = record?.[key];
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function numberField(record: Record<string, unknown> | undefined, key: string): number | null {
  const value = record?.[key];
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function booleanField(record: Record<string, unknown> | undefined, key: string): boolean | null {
  const value = record?.[key];
  return typeof value === "boolean" ? value : null;
}

function roleField(record: Record<string, unknown> | undefined): string | undefined {
  const role = stringField(record, "role") ?? stringField(record, "speaker") ?? stringField(record, "author");
  if (role) {
    return role.toLowerCase();
  }
  const type = stringField(record, "type")?.toLowerCase();
  return type === "user" || type === "assistant" || type === "system" ? type : undefined;
}

export function extractGatewayText(value: unknown): string {
  if (typeof value === "string") {
    return value;
  }
  if (Array.isArray(value)) {
    return value.map(extractGatewayText).filter(Boolean).join("");
  }
  const record = asRecord(value);
  if (!record) {
    return "";
  }

  for (const key of ["text", "delta", "deltaText", "message", "content", "output", "result"]) {
    const text = extractGatewayText(record[key]);
    if (text) {
      return text;
    }
  }
  return "";
}

export function normalizeHistoryMessage(value: unknown): ChatHistoryMessage | undefined {
  const record = asRecord(value);
  if (!record) {
    return undefined;
  }
  const role = stringField(record, "role") ?? "assistant";
  const rawText = extractGatewayHistoryText(record.content ?? record.text ?? record.message, role);
  const text = sanitizeHistoryText(role, rawText);
  if (!text.trim()) {
    return undefined;
  }
  const openClawMeta = asRecord(record.__openclaw);
  return {
    id: stringField(openClawMeta, "id") ?? stringField(record, "id") ?? null,
    role,
    text,
    timestamp: numberField(record, "timestamp")
  };
}

function extractGatewayHistoryText(value: unknown, role: string): string {
  const roleText = extractRoleTaggedGatewayText(value, role.toLowerCase());
  return roleText.trim() ? roleText : extractGatewayText(value);
}

function extractRoleTaggedGatewayText(value: unknown, role: string): string {
  if (Array.isArray(value)) {
    return value
      .map((item) => extractRoleTaggedGatewayText(item, role))
      .filter(Boolean)
      .join("\n\n");
  }

  const record = asRecord(value);
  if (!record) {
    return "";
  }

  const itemRole = roleField(record);
  if (itemRole) {
    if (itemRole !== role) {
      return "";
    }
    return extractGatewayText(record.content ?? record.text ?? record.message ?? record.output ?? record.result);
  }

  const explicitRoleValue = record[role];
  if (explicitRoleValue !== undefined) {
    return extractGatewayText(explicitRoleValue);
  }

  for (const key of ["content", "message", "messages", "items", "parts"]) {
    const nestedText = extractRoleTaggedGatewayText(record[key], role);
    if (nestedText.trim()) {
      return nestedText;
    }
  }

  return "";
}

function sanitizeHistoryText(role: string, text: string): string {
  if (role !== "user") {
    return text;
  }
  const queryStart = text.lastIndexOf("<user_query>");
  if (queryStart !== -1) {
    const queryText = text.slice(queryStart + "<user_query>".length);
    const queryEnd = queryText.indexOf("</user_query>");
    return (queryEnd === -1 ? queryText : queryText.slice(0, queryEnd)).trim();
  }

  for (const marker of ["User request:", "User message:", "User prompt:", "User input:"]) {
    const index = text.lastIndexOf(marker);
    if (index !== -1) {
      return stripDisplayTimestampPrefix(text.slice(index + marker.length));
    }
  }

  const genericUserIndex = Math.max(text.lastIndexOf("\nUser:"), text.lastIndexOf("\nuser:"));
  if (genericUserIndex !== -1) {
    const prefix = text.slice(0, genericUserIndex);
    if (/(^|\n)\s*(system|developer|context|instructions|system message):/i.test(prefix)) {
      return stripDisplayTimestampPrefix(text.slice(genericUserIndex + "\nUser:".length));
    }
  }

  const systemStatusWrapper = text.match(/^\s*System:\s*\[[^\]]+\][\s\S]*?\n{2,}\s*([\s\S]+)$/i);
  if (systemStatusWrapper?.[1]?.trim()) {
    return stripDisplayTimestampPrefix(systemStatusWrapper[1]);
  }
  return text;
}

function stripDisplayTimestampPrefix(text: string): string {
  return text.replace(/^\s*\[[^\]]+\]\s*/, "").trim();
}

export function normalizeModels(value: unknown): ChatModelOption[] {
  const models = Array.isArray(asRecord(value)?.models) ? asRecord(value)?.models as unknown[] : [];
  const normalized: ChatModelOption[] = [];
  for (const item of models) {
    const record = asRecord(item);
    const key = stringField(record, "key") ?? stringField(record, "id") ?? stringField(record, "name");
    if (!key) {
      continue;
    }
    const name = stringField(record, "name") ?? key;
    const provider = key.includes("/") ? key.split("/")[0] : stringField(record, "provider");
    normalized.push({
      id: key,
      label: provider && name !== key ? `${name} (${provider})` : name,
      provider: provider ?? null,
      contextWindow: numberField(record, "contextWindow"),
      available: booleanField(record, "available")
    });
  }
  return normalized;
}

export function normalizeReasoningOptions(value: unknown): ChatReasoningOption[] {
  const defaults = asRecord(asRecord(value)?.defaults);
  const raw = defaults?.thinkingLevels ?? defaults?.thinkingOptions;
  const levels = Array.isArray(raw) ? raw : [];
  const normalized = levels.map((item) => {
    if (typeof item === "string" && item.trim()) {
      return { id: item.trim(), label: item.trim() };
    }
    const record = asRecord(item);
    const id = stringField(record, "id");
    if (!id) {
      return undefined;
    }
    return { id, label: stringField(record, "label") ?? id };
  }).filter((option): option is ChatReasoningOption => {
    if (!option) {
      return false;
    }
    return ALLOWED_REASONING_OPTION_IDS.has(option.id);
  });
  return normalized.length > 0 ? normalized : FALLBACK_REASONING_OPTIONS;
}

export function normalizeSessions(value: unknown): ChatSessionSummary[] {
  const sessions = Array.isArray(asRecord(value)?.sessions) ? asRecord(value)?.sessions as unknown[] : [];
  const normalized: ChatSessionSummary[] = [];
  for (const item of sessions) {
    const record = asRecord(item);
    const key = stringField(record, "key");
    if (!key) {
      continue;
    }
    normalized.push({
      key,
      sessionId: stringField(record, "sessionId") ?? null,
      label: stringField(record, "label") ?? null,
      displayName: stringField(record, "displayName") ?? stringField(record, "label") ?? null,
      updatedAt: numberField(record, "updatedAt"),
      model: stringField(record, "model") ?? null,
      modelProvider: stringField(record, "modelProvider") ?? null,
      contextTokens: numberField(record, "contextTokens"),
      inputTokens: numberField(record, "inputTokens"),
      outputTokens: numberField(record, "outputTokens"),
      totalTokens: numberField(record, "totalTokens"),
      estimatedCostUsd: numberField(record, "estimatedCostUsd"),
      fastMode: booleanField(record, "fastMode"),
      hasActiveRun: booleanField(record, "hasActiveRun"),
      thinkingLevel: stringField(record, "thinkingLevel") ?? null,
      reasoningLevel: stringField(record, "reasoningLevel") ?? null,
      verboseLevel: stringField(record, "verboseLevel") ?? null
    });
  }
  return normalized;
}

export function usageFromSession(session: ChatSessionSummary | undefined): ChatUsageSummary {
  return {
    inputTokens: session?.inputTokens ?? null,
    outputTokens: session?.outputTokens ?? null,
    totalTokens: session?.totalTokens ?? null,
    contextTokens: session?.contextTokens ?? null,
    estimatedCostUsd: session?.estimatedCostUsd ?? null
  };
}

export function normalizeCommands(value: unknown): ChatCommandOption[] {
  const commands = Array.isArray(asRecord(value)?.commands) ? asRecord(value)?.commands as unknown[] : [];
  const normalized: ChatCommandOption[] = [];
  for (const item of commands) {
    const record = asRecord(item);
    const name = stringField(record, "name");
    if (!name) {
      continue;
    }
    const aliases = Array.isArray(record?.textAliases)
      ? record.textAliases.filter((alias): alias is string => typeof alias === "string")
      : [];
    normalized.push({
      name,
      description: stringField(record, "description") ?? null,
      category: stringField(record, "category") ?? null,
      textAliases: aliases,
      acceptsArgs: booleanField(record, "acceptsArgs") ?? false
    });
  }
  return normalized;
}

export function normalizeTools(value: unknown): ChatToolSummary[] {
  const groups = Array.isArray(asRecord(value)?.groups) ? asRecord(value)?.groups as unknown[] : [];
  const normalized: ChatToolSummary[] = [];
  for (const groupValue of groups) {
    const group = asRecord(groupValue);
    const groupLabel = stringField(group, "label") ?? stringField(group, "id") ?? null;
    const tools = Array.isArray(group?.tools) ? group.tools as unknown[] : [];
    for (const item of tools) {
      const record = asRecord(item);
      const id = stringField(record, "id");
      if (!id) {
        continue;
      }
      normalized.push({
        id,
        label: stringField(record, "label") ?? id,
        description: stringField(record, "description") ?? stringField(record, "rawDescription") ?? null,
        source: stringField(record, "source") ?? null,
        group: groupLabel
      });
    }
  }
  return normalized;
}

function statusFromRaw(raw: Record<string, unknown> | undefined): ChatToolEventMessage["status"] {
  const status = stringField(raw, "status") ?? stringField(raw, "state") ?? stringField(raw, "phase");
  if (status === "completed" || status === "done" || status === "success") {
    return "completed";
  }
  if (status === "failed" || status === "error") {
    return "failed";
  }
  if (status === "blocked" || status === "denied") {
    return "blocked";
  }
  return status === "info" ? "info" : "running";
}

export function normalizeGatewayToolEvent(
  deviceId: string,
  sessionKey: string,
  payload: unknown
): ChatToolEventMessage | undefined {
  const record = asRecord(payload);
  if (!record) {
    return undefined;
  }
  const data = asRecord(record.data) ?? record;
  const stream = stringField(record, "stream") ?? "";
  const toolName = stringField(data, "toolName")
    ?? stringField(data, "tool")
    ?? stringField(data, "name")
    ?? stringField(data, "command")
    ?? (stream.includes("tool") || stream.includes("command") ? stream : undefined);
  if (!toolName && !stream.includes("tool") && !stream.includes("command")) {
    return undefined;
  }
  const status = statusFromRaw(data);
  const eventId = stringField(data, "id") ?? stringField(record, "id") ?? `${record.runId ?? "run"}:${record.seq ?? randomUUID()}`;
  const summary = stringField(data, "summary") ?? stringField(data, "message") ?? stringField(data, "text") ?? null;
  return {
    type: "chat.tool_event",
    deviceId,
    sessionKey,
    runId: stringField(record, "runId") ?? null,
    eventId,
    toolName: toolName ?? "tool",
    title: summary ?? toolName ?? "Tool activity",
    status,
    summary,
    args: data.args ?? data.arguments ?? null,
    output: data.output ?? data.result ?? null,
    error: stringField(data, "error") ?? null,
    raw: payload
  };
}

export function normalizeGatewayReasoningEvent(
  deviceId: string,
  fallbackSessionKey: string,
  payload: unknown,
  eventName?: string
): ChatReasoningDeltaMessage | undefined {
  const record = asRecord(payload);
  if (!record) {
    return undefined;
  }
  const data = asRecord(record.data) ?? asRecord(record.payload) ?? record;
  const normalizedEvent = (eventName ?? stringField(record, "event") ?? stringField(data, "event") ?? "").toLowerCase();
  const normalizedType = (stringField(record, "type") ?? stringField(data, "type") ?? "").toLowerCase();
  const normalizedState = (stringField(record, "state") ?? stringField(data, "state") ?? "").toLowerCase();
  const normalizedStream = (stringField(record, "stream") ?? stringField(data, "stream") ?? "").toLowerCase();
  const sourceText = [normalizedEvent, normalizedType, normalizedState, normalizedStream].join(" ");
  const isReasoningEvent = sourceText.includes("thinking")
    || sourceText.includes("reasoning")
    || normalizedState === "plan";
  const isDeltaLike = sourceText.includes("delta")
    || sourceText.includes("update")
    || sourceText.includes("stream")
    || normalizedState === "thinking"
    || normalizedState === "reasoning"
    || normalizedState === "plan";
  if (!isReasoningEvent || !isDeltaLike) {
    return undefined;
  }

  const delta = [
    data.delta,
    data.deltaText,
    data.text,
    data.message,
    data.content,
    record.delta,
    record.deltaText,
    record.message,
    record.content
  ].map(extractGatewayText).find((text) => text.trim());
  if (!delta) {
    return undefined;
  }

  return {
    type: "chat.reasoning_delta",
    deviceId,
    sessionKey: stringField(record, "sessionKey") ?? stringField(data, "sessionKey") ?? fallbackSessionKey,
    runId: stringField(record, "runId") ?? stringField(data, "runId") ?? `${record.seq ?? randomUUID()}`,
    delta,
    replace: Boolean(record.replace ?? data.replace)
  };
}

export function chatMessagesFromHistory(value: unknown): ChatHistoryMessage[] {
  const messages = Array.isArray(asRecord(value)?.messages) ? asRecord(value)?.messages as unknown[] : [];
  return messages.map(normalizeHistoryMessage).filter((message): message is ChatHistoryMessage => Boolean(message));
}

export function requestKeyFromSessionKey(sessionKey: string, agentId: string): string {
  const prefix = `agent:${agentId}:explicit:`;
  return sessionKey.startsWith(prefix) ? sessionKey.slice(prefix.length) : sessionKey;
}

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

  async createSession(options: { key?: string; label?: string; model?: string }): Promise<unknown> {
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

export function mapGatewayChatEvent(
  deviceId: string,
  payload: unknown
): ChatOutboundMessage | undefined {
  const record = asRecord(payload);
  const sessionKey = stringField(record, "sessionKey");
  const runId = stringField(record, "runId");
  if (!record || !sessionKey || !runId) {
    return undefined;
  }
  const eventType = stringField(record, "type");
  if (eventType === "assistant.delta") {
    const data = asRecord(record.data);
    return {
      type: "chat.delta",
      deviceId,
      sessionKey,
      runId,
      delta: extractGatewayText(data?.delta ?? data?.text ?? record.data),
      replace: false
    };
  }
  if (eventType === "assistant.message") {
    const data = asRecord(record.data);
    const text = extractGatewayText(data?.message ?? data?.content ?? data?.text ?? record.data);
    if (!text.trim()) {
      return undefined;
    }
    return {
      type: "chat.final",
      deviceId,
      sessionKey,
      runId,
      text,
      usage: data?.usage
    };
  }
  if (eventType === "run.failed" || eventType === "run.cancelled" || eventType === "run.timed_out") {
    const data = asRecord(record.data);
    return {
      type: "chat.error",
      deviceId,
      sessionKey,
      runId,
      message: stringField(data, "message") ?? stringField(data, "error") ?? `OpenClaw ${eventType.replace("run.", "")}`
    };
  }
  const state = stringField(record, "state");
  if (state === "delta") {
    return {
      type: "chat.delta",
      deviceId,
      sessionKey,
      runId,
      delta: extractGatewayText(record.message ?? record.deltaText ?? record.delta),
      replace: Boolean(record.replace)
    };
  }
  if (state === "final" || state === "aborted") {
    return {
      type: "chat.final",
      deviceId,
      sessionKey,
      runId,
      text: extractGatewayText(record.message),
      usage: record.usage
    };
  }
  if (state === "error") {
    return {
      type: "chat.error",
      deviceId,
      sessionKey,
      runId,
      message: stringField(record, "errorMessage") ?? "OpenClaw chat run failed"
    };
  }
  return undefined;
}
