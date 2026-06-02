import { execFile } from "node:child_process";
import { randomUUID } from "node:crypto";
import { join } from "node:path";
import { promisify } from "node:util";
import { HermesApiClient, type HermesRunsApi } from "../dispatcher/HermesApiClient.js";
import { createHermesConfigRunsClient } from "../dispatcher/HermesConfigRunsClient.js";
import { HermesRunDriver, type HermesActiveRun, type HermesRunDriverEvent } from "../dispatcher/HermesRunDriver.js";
import type { ChatAttachment, ChatHistoryMessage, ChatSessionSummary } from "../protocol/messages.js";
import { resolveCommand, type CommandResolution } from "../host/CommandDiscovery.js";
import type { BridgeConfig } from "./config.js";
import { discoverHermesModels } from "./HermesModelDiscovery.js";
import { InMemoryHarnessSessionStore, type HarnessStoredSession } from "./harness/InMemoryHarnessSessionStore.js";
import type { GatewayChatSendResult, GatewayEvent, GatewayEventHandler } from "./chat/ChatTransportTypes.js";
import {
  cliPrompt,
  hermesCliModelArgs,
  hermesCliThinkingArgs,
  mergeHermesModels,
  normalizeHermesApiModels,
  normalizeHermesSkills,
  normalizeHermesToolsets
} from "./hermes/HermesChatHelpers.js";

interface ActiveChatRun {
  sessionKey: string;
  active: HermesActiveRun;
  mode: "api" | "local-stream" | "cli";
  driver?: HermesRunDriver;
}

interface RemoteSessionObservation {
  updatedAt: number | null;
  latestRunId?: string;
}

interface SelectedRunsDriver {
  mode: "api" | "local-stream";
  api: HermesRunsApi;
  driver: HermesRunDriver;
}

const execFileAsync = promisify(execFile);

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" ? value as Record<string, unknown> : undefined;
}

function isHealthyHermesResponse(value: unknown): boolean {
  const record = asRecord(value);
  return record?.ok === true || record?.status === "ok";
}

function firstStringField(value: unknown, keys: string[]): string | undefined {
  if (typeof value === "string" && value.trim()) {
    return value.trim();
  }
  if (Array.isArray(value)) {
    for (const item of value) {
      const nested = firstStringField(item, keys);
      if (nested) {
        return nested;
      }
    }
    return undefined;
  }
  const record = asRecord(value);
  if (!record) {
    return undefined;
  }
  for (const key of keys) {
    const field = record[key];
    if (typeof field === "string" && field.trim()) {
      return field.trim();
    }
  }
  for (const field of Object.values(record)) {
    const nested = firstStringField(field, keys);
    if (nested) {
      return nested;
    }
  }
  return undefined;
}

function firstNumberField(value: unknown, keys: string[]): number | null {
  const record = asRecord(value);
  if (!record) {
    return null;
  }
  for (const key of keys) {
    const field = record[key];
    if (typeof field === "number" && Number.isFinite(field)) {
      return field;
    }
  }
  return null;
}

export class HermesChatClient {
  private readonly api?: HermesRunsApi;
  private readonly driver?: HermesRunDriver;
  private readonly localConfigApi?: HermesRunsApi;
  private readonly localConfigDriver?: HermesRunDriver;
  private readonly cli: CommandResolution;
  private readonly sessions: InMemoryHarnessSessionStore;
  private readonly handlers = new Set<GatewayEventHandler>();
  private readonly activeRuns = new Map<string, ActiveChatRun>();
  private readonly remoteSessionObservations = new Map<string, RemoteSessionObservation>();
  private remoteSessionsInitialized = false;

  constructor(
    private readonly config: BridgeConfig,
    api?: HermesRunsApi,
    sessionStoragePath: string | null = join(process.cwd(), "state", "hermes-sessions.json")
  ) {
    this.cli = resolveCommand(config.hermesCliCommand ?? process.env.HERMES_COMMAND ?? "hermes");
    if (!config.hermesApiKey && !this.cli.available) {
      throw new Error("Hermes requires either HERMES_API_KEY for the runs API or a hermes CLI on PATH.");
    }
    if (api || config.hermesApiKey) {
      this.api = api ?? new HermesApiClient({
        apiBaseUrl: config.hermesApiBaseUrl,
        apiKey: config.hermesApiKey ?? "",
        model: config.hermesModel,
        runTimeoutMs: config.hermesRunTimeoutMs
      });
      this.driver = new HermesRunDriver(this.api, config.hermesRunTimeoutMs);
    }
    if (!api && config.hermesApiKey) {
      this.localConfigApi = createHermesConfigRunsClient(config.hermesModel);
      if (this.localConfigApi) {
        this.localConfigDriver = new HermesRunDriver(this.localConfigApi, config.hermesRunTimeoutMs);
      }
    }
    this.sessions = new InMemoryHarnessSessionStore("hermes", {
      defaultModel: config.hermesModel,
      modelProvider: "hermes",
      storagePath: sessionStoragePath ?? undefined
    });
  }

  addEventListener(handler: GatewayEventHandler): () => void {
    this.handlers.add(handler);
    return () => this.handlers.delete(handler);
  }

  async history(sessionKey: string): Promise<unknown> {
    const local = this.sessions.history(sessionKey);
    const session = this.sessions.ensureSession(sessionKey);
    const api = await this.selectMetadataApi();
    const payload = await api?.listSessionMessages(session.sessionId).catch(() => undefined);
    const remoteMessages = normalizeHermesMessages(payload);
    if (remoteMessages.length === 0) {
      return local;
    }
    return {
      ...local,
      sessionId: session.sessionId,
      messages: mergeHermesHistory(local.messages, remoteMessages)
    };
  }

  async sendChat(options: {
    sessionKey: string;
    sessionId?: string;
    message: string;
    attachments?: ChatAttachment[];
    thinking?: string;
    idempotencyKey?: string;
  }): Promise<GatewayChatSendResult> {
    const session = this.sessions.ensureSession(options.sessionKey, options.sessionId);
    this.sessions.setThinkingLevel(session, options.thinking);
    this.sessions.appendUserMessage(session, options.message, options.idempotencyKey, options.attachments);
    const instructions = hermesConversationInstructions(this.sessions.historyMessages(session).slice(0, -1));

    const selectedDriver = await this.selectRunsDriver();
    if (!selectedDriver) {
      return this.sendCliChat(session, options, instructions);
    }
    const active = await selectedDriver.driver.createRun({
      input: options.message,
      sessionId: session.sessionId,
      model: session.model,
      instructions,
      idempotencyKey: options.idempotencyKey,
      attachments: options.attachments,
      serviceTier: session.fastMode === true ? "priority" : null
    });
    const runId = active.runId;
    this.sessions.setActiveRun(session, runId);
    this.activeRuns.set(runId, {
      sessionKey: session.key,
      active,
      mode: selectedDriver.mode,
      driver: selectedDriver.driver
    });
    void this.processRun(session.key, active, selectedDriver.driver);
    return { runId, sessionKey: session.key };
  }

  async abort(_sessionKey: string, runId?: string): Promise<unknown> {
    if (!runId) {
      return {};
    }
    const active = this.activeRuns.get(runId);
    if (active) {
      const driver = active.driver ?? (active.mode === "api" ? this.driver : undefined);
      if (driver) {
        await driver.stopRun(active.active);
      } else {
        active.active.controller.abort();
      }
    } else {
      await this.api?.stopRun(runId);
    }
    this.emit("agent", {
      type: "run.cancelled",
      sessionKey: active?.sessionKey ?? _sessionKey,
      runId,
      data: { message: "Hermes run stopped." }
    });
    return { status: "stopping" };
  }

  async steerChat(options: {
    sessionKey: string;
    sessionId?: string;
    runId?: string;
    message: string;
    attachments?: ChatAttachment[];
    thinking?: string;
    idempotencyKey?: string;
  }): Promise<GatewayChatSendResult> {
    const active = this.activeRunFor(options.sessionKey, options.runId);
    if (!active) {
      throw new Error("No active Hermes run to steer");
    }
    const driver = active.driver ?? (active.mode === "api" ? this.driver : undefined);
    if (active.mode === "cli" || !driver) {
      throw new Error("Hermes CLI fallback does not support active-turn steering.");
    }
    if (active.mode === "local-stream") {
      throw new Error("Hermes local streaming adapter does not support active-turn steering.");
    }
    const session = this.sessions.ensureSession(active.sessionKey, options.sessionId);
    this.sessions.appendUserMessage(session, options.message, options.idempotencyKey, options.attachments);
    await driver.steerRun(
      active.active,
      options.message,
      options.attachments,
      session.fastMode === true ? "priority" : null
    );
    return { runId: active.active.runId, sessionKey: active.sessionKey };
  }

  async listModels(): Promise<unknown> {
    const api = await this.selectMetadataApi();
    const payload = await api?.listModels().catch(() => undefined);
    const apiModels = normalizeHermesApiModels(payload, this.config.hermesModel);
    const discoveredModels = discoverHermesModels(this.config.hermesModel);
    const models = mergeHermesModels(apiModels, discoveredModels, this.config.hermesModel);
    return { models };
  }

  async listSessions(limit = 50): Promise<unknown> {
    const api = await this.selectMetadataApi();
    const remotePayload = await api?.listSessions().catch(() => undefined);
    const remoteSessions = normalizeHermesSessions(remotePayload).slice(0, limit);
    const byKey = new Map<string, ChatSessionSummary>();
    for (const session of this.sessions.listSessions(limit)) {
      byKey.set(session.key, session);
    }
    for (const session of remoteSessions) {
      byKey.set(session.key, {
        ...byKey.get(session.key),
        ...session
      });
    }
    return {
      sessions: [...byKey.values()]
        .sort((left, right) => (right.updatedAt ?? 0) - (left.updatedAt ?? 0))
        .slice(0, limit),
      defaults: {
        thinkingLevels: ["low", "medium", "high", "xhigh"]
      }
    };
  }

  async syncRemoteReplies(limit = 50): Promise<void> {
    const api = await this.selectMetadataApi();
    if (!api) {
      return;
    }
    const remotePayload = await api.listSessions().catch(() => undefined);
    const remoteSessions = normalizeHermesSessions(remotePayload).slice(0, limit);
    await this.detectRemoteSessionReplies(remoteSessions);
  }

  async createSession(options: { key?: string; label?: string; model?: string; workspacePath?: string; createWorkspaceIfMissing?: boolean }): Promise<unknown> {
    return this.sessions.createSession(options);
  }

  async patchSession(sessionKey: string, patch: Record<string, unknown>): Promise<unknown> {
    this.sessions.patchSession(sessionKey, patch);
    return { ok: true };
  }

  async listCommands(): Promise<unknown> {
    const api = await this.selectMetadataApi();
    const skills = api ? normalizeHermesSkills(await api.listSkills().catch(() => undefined)) : [];
    return {
      commands: [
        { name: "status", description: "Show Hermes status", textAliases: ["/status"], acceptsArgs: false },
        { name: "new", description: "Start a new Hermes chat", textAliases: ["/new"], acceptsArgs: false },
        { name: "help", description: "Show available Hermes commands", textAliases: ["/help"], acceptsArgs: false },
        { name: "skills", description: "List available Hermes skills", textAliases: ["/skills"], acceptsArgs: false },
        { name: "skill", description: "Load or use a Hermes skill", textAliases: ["/skill"], acceptsArgs: true },
        ...skills
      ]
    };
  }

  async effectiveTools(): Promise<unknown> {
    const api = await this.selectMetadataApi();
    const capabilities = await api?.capabilities().catch(() => undefined);
    const toolsets = await api?.listToolsets().catch(() => undefined);
    return { tools: normalizeHermesToolsets(toolsets), capabilities, toolsets: asRecord(toolsets)?.data ?? [] };
  }

  async health(): Promise<unknown> {
    if (this.api) {
      try {
        const health = await this.api.health();
        if (isHealthyHermesResponse(health)) {
          return { ...asRecord(health), ok: true, mode: "api" };
        }
      } catch {
        // Fall through to CLI mode if Hermes itself is installed but no Lynk runs API is serving.
      }
    }
    if (this.localConfigApi) {
      try {
        const health = await this.localConfigApi.health();
        if (isHealthyHermesResponse(health)) {
          return { ...asRecord(health), ok: true, mode: "local-stream" };
        }
      } catch {
        // Fall through to CLI mode if the configured local provider is not reachable.
      }
    }
    if (this.cli.available) {
      return {
        ok: true,
        mode: "cli",
        message: "Hermes runs API is not reachable; using Hermes CLI fallback."
      };
    }
    return { ok: false, error: "Hermes runs API is not reachable and the hermes CLI was not found on PATH." };
  }

  close(): void {
    for (const active of this.activeRuns.values()) {
      active.active.controller.abort();
    }
    this.activeRuns.clear();
  }

  private activeRunFor(sessionKey: string, runId?: string): ActiveChatRun | undefined {
    if (runId) {
      const active = this.activeRuns.get(runId);
      return active?.sessionKey === sessionKey ? active : undefined;
    }
    return [...this.activeRuns.values()].find((active) => active.sessionKey === sessionKey);
  }

  private async detectRemoteSessionReplies(remoteSessions: ChatSessionSummary[]): Promise<void> {
    const initialized = this.remoteSessionsInitialized;
    for (const session of remoteSessions) {
      const previous = this.remoteSessionObservations.get(session.key);
      if (!initialized || !previous) {
        await this.baselineRemoteSession(session);
        continue;
      }
      if (!remoteSessionAdvanced(previous, session)) {
        continue;
      }
      if (this.activeRunFor(session.key)) {
        this.remoteSessionObservations.set(session.key, {
          updatedAt: session.updatedAt ?? null,
          latestRunId: previous?.latestRunId
        });
        continue;
      }
      const latest = await this.latestRemoteAssistantMessage(session);
      if (!latest) {
        this.remoteSessionObservations.set(session.key, {
          updatedAt: session.updatedAt ?? null,
          latestRunId: previous?.latestRunId
        });
        continue;
      }
      const runId = hermesExternalRunId(session, latest);
      if (previous?.latestRunId !== runId) {
        this.emit("chat", {
          sessionKey: session.key,
          runId,
          state: "final",
          message: latest.text
        });
      }
      this.remoteSessionObservations.set(session.key, {
        updatedAt: session.updatedAt ?? null,
        latestRunId: runId
      });
    }
    this.remoteSessionsInitialized = true;
  }

  private async baselineRemoteSession(session: ChatSessionSummary): Promise<void> {
    const latest = await this.latestRemoteAssistantMessage(session);
    this.remoteSessionObservations.set(session.key, {
      updatedAt: session.updatedAt ?? null,
      latestRunId: latest ? hermesExternalRunId(session, latest) : undefined
    });
  }

  private async latestRemoteAssistantMessage(session: ChatSessionSummary): Promise<ChatHistoryMessage | undefined> {
    const sessionId = session.sessionId ?? session.key.replace(/^hermes:/, "");
    const api = await this.selectMetadataApi();
    const payload = await api?.listSessionMessages(sessionId).catch(() => undefined);
    return normalizeHermesMessages(payload)
      .slice()
      .reverse()
      .find((message) => message.role.toLowerCase() === "assistant" && message.text.trim());
  }

  private async processRun(sessionKey: string, active: HermesActiveRun, driver: HermesRunDriver): Promise<void> {
    const session = this.sessions.ensureSession(sessionKey);
    const runId = active.runId;
    try {
      const completed = await driver.streamRun(active, (event) => this.handleRunEvent(session, active.runId, event));
      const finalText = completed.finalText;
      if (finalText.trim()) {
        this.sessions.upsertAssistantMessage(session, runId, finalText);
      }
      this.sessions.clearActiveRun(session, runId);
      this.sessions.setUsage(session, asRecord(completed.status.raw)?.usage as Record<string, unknown> | undefined);
      this.emit("chat", {
        sessionKey,
        runId,
        state: "final",
        message: finalText
      });
    } catch (error) {
      if (!active.controller.signal.aborted) {
        this.emit("chat", {
          sessionKey,
          runId,
          state: "error",
          error: error instanceof Error ? error.message : String(error)
        });
      }
    } finally {
      this.activeRuns.delete(runId);
      this.sessions.clearActiveRun(session, runId);
    }
  }

  private async sendCliChat(
    session: HarnessStoredSession,
    options: {
      sessionKey: string;
      sessionId?: string;
      message: string;
      attachments?: ChatAttachment[];
      thinking?: string;
      idempotencyKey?: string;
    },
    instructions?: string
  ): Promise<GatewayChatSendResult> {
    if (!this.cli.available || !this.cli.resolvedPath) {
      throw new Error("Hermes CLI fallback is unavailable. Install Hermes or start the Lynk-compatible Hermes runs API.");
    }
    const runId = options.idempotencyKey ?? `hermes_cli_${randomUUID()}`;
    const active: HermesActiveRun = {
      runId,
      sessionId: session.sessionId,
      controller: new AbortController()
    };
    this.sessions.setActiveRun(session, runId);
    this.activeRuns.set(runId, {
      sessionKey: session.key,
      active,
      mode: "cli"
    });
    void this.processCliRun(session.key, active, cliPrompt(options.message, instructions), session.model, options.thinking);
    return { runId, sessionKey: session.key };
  }

  private async processCliRun(
    sessionKey: string,
    active: HermesActiveRun,
    prompt: string,
    model: string | undefined,
    thinking: string | undefined
  ): Promise<void> {
    const session = this.sessions.ensureSession(sessionKey);
    const runId = active.runId;
    try {
      const { stdout } = await execFileAsync(this.cli.resolvedPath ?? this.cli.executable, [
        ...hermesCliModelArgs(model),
        ...hermesCliThinkingArgs(thinking),
        "-z",
        prompt
      ], {
        timeout: this.config.hermesRunTimeoutMs,
        maxBuffer: 1024 * 1024,
        signal: active.controller.signal
      });
      const finalText = stdout.trim();
      if (finalText) {
        this.sessions.upsertAssistantMessage(session, runId, finalText);
      }
      this.emit("chat", {
        sessionKey,
        runId,
        state: "final",
        message: finalText
      });
    } catch (error) {
      if (!active.controller.signal.aborted) {
        this.emit("chat", {
          sessionKey,
          runId,
          state: "error",
          error: error instanceof Error ? error.message : String(error)
        });
      }
    } finally {
      this.activeRuns.delete(runId);
      this.sessions.clearActiveRun(session, runId);
    }
  }

  private async selectRunsDriver(): Promise<SelectedRunsDriver | undefined> {
    if (this.api && this.driver) {
      try {
        const health = await this.api.health();
        if (isHealthyHermesResponse(health)) {
          return { mode: "api", api: this.api, driver: this.driver };
        }
      } catch {
        // Try the Hermes config-backed streaming adapter next.
      }
    }
    if (this.localConfigApi && this.localConfigDriver) {
      try {
        const health = await this.localConfigApi.health();
        if (isHealthyHermesResponse(health)) {
          return { mode: "local-stream", api: this.localConfigApi, driver: this.localConfigDriver };
        }
      } catch {
        return undefined;
      }
    }
    return undefined;
  }

  private async selectMetadataApi(): Promise<HermesRunsApi | undefined> {
    const selected = await this.selectRunsDriver();
    return selected?.api ?? this.api ?? this.localConfigApi;
  }

  private handleRunEvent(session: HarnessStoredSession, runId: string, event: HermesRunDriverEvent): void {
    if (event.type === "tool") {
      this.emit("agent", {
        sessionKey: session.key,
        runId,
        type: "tool",
        toolName: event.toolName ?? "hermes_tool",
        status: event.status ?? "running",
        data: event.raw.data
      });
      return;
    }
    if (event.type !== "delta") {
      return;
    }
    this.sessions.upsertAssistantMessage(session, runId, event.accumulated, { persist: false });
    this.emit("chat", {
      sessionKey: session.key,
      runId,
      state: "delta",
      delta: event.delta
    });
  }

  private emit(event: string, payload: unknown): void {
    const gatewayEvent: GatewayEvent = { event, payload };
    for (const handler of this.handlers) {
      handler(gatewayEvent);
    }
  }
}

function normalizeHermesSessions(payload: unknown): ChatSessionSummary[] {
  const record = asRecord(payload);
  const rawSessions = Array.isArray(record?.sessions)
    ? record.sessions
    : Array.isArray(record?.data)
      ? record.data
      : Array.isArray(payload)
        ? payload
        : [];
  return rawSessions
    .map(normalizeHermesSession)
    .filter((session): session is ChatSessionSummary => Boolean(session));
}

function normalizeHermesSession(value: unknown): ChatSessionSummary | undefined {
  const record = asRecord(value);
  const sessionId = firstStringField(record, ["session_id", "sessionId", "id"]);
  if (!sessionId) {
    return undefined;
  }
  // Hermes-compatible backends may expose token counts either nested or flat.
  const tokenCounts = asRecord(record?.token_counts) ?? asRecord(record?.tokenCounts) ?? record;
  const preview = firstStringField(record, ["preview", "title", "label", "last_message"]);
  const timestamp = timestampMs(record?.timestamp ?? record?.updated_at ?? record?.created_at);
  return {
    key: `hermes:${sessionId}`,
    sessionId,
    label: preview ?? sessionId,
    displayName: preview ?? sessionId,
    updatedAt: timestamp,
    model: firstStringField(record, ["model", "model_id", "modelId"]) ?? null,
    modelProvider: "hermes",
    inputTokens: firstNumberField(tokenCounts, ["input", "input_tokens", "prompt", "prompt_tokens"]),
    outputTokens: firstNumberField(tokenCounts, ["output", "output_tokens", "completion", "completion_tokens"]),
    totalTokens: firstNumberField(tokenCounts, ["total", "total_tokens"])
  };
}

function normalizeHermesMessages(payload: unknown): ChatHistoryMessage[] {
  const record = asRecord(payload);
  const rawMessages = Array.isArray(record?.messages)
    ? record.messages
    : Array.isArray(record?.data)
      ? record.data
      : Array.isArray(payload)
        ? payload
        : [];
  return rawMessages
    .map(normalizeHermesMessage)
    .filter((message): message is ChatHistoryMessage => Boolean(message));
}

function normalizeHermesMessage(value: unknown): ChatHistoryMessage | undefined {
  const record = asRecord(value);
  if (!record) {
    return undefined;
  }
  const text = firstStringField(record, ["content", "text", "message"]);
  if (!text) {
    return undefined;
  }
  return {
    id: firstStringField(record, ["message_id", "messageId", "id"]) ?? null,
    role: firstStringField(record, ["role", "author", "speaker"]) ?? "assistant",
    text,
    timestamp: timestampMs(record.timestamp ?? record.created_at ?? record.updated_at)
  };
}

function mergeHermesHistory(localMessages: ChatHistoryMessage[], remoteMessages: ChatHistoryMessage[]): ChatHistoryMessage[] {
  const merged = [...remoteMessages];
  for (const message of localMessages) {
    if (merged.some((existing) => sameHermesHistoryMessage(existing, message))) {
      continue;
    }
    merged.push(message);
  }
  return merged.sort((left, right) => {
    const leftTimestamp = left.timestamp ?? Number.MAX_SAFE_INTEGER;
    const rightTimestamp = right.timestamp ?? Number.MAX_SAFE_INTEGER;
    if (leftTimestamp !== rightTimestamp) {
      return leftTimestamp - rightTimestamp;
    }
    return 0;
  });
}

function sameHermesHistoryMessage(left: ChatHistoryMessage, right: ChatHistoryMessage): boolean {
  if (left.id && right.id && left.id === right.id) {
    return true;
  }
  return left.role === right.role &&
    left.text === right.text &&
    JSON.stringify(left.attachments ?? []) === JSON.stringify(right.attachments ?? []);
}

function hermesConversationInstructions(messages: ChatHistoryMessage[]): string | undefined {
  const context = messages
    .filter((message) => ["system", "user", "assistant"].includes(message.role.toLowerCase()) && message.text.trim())
    .slice(-12)
    .map((message) => `${hermesRoleLabel(message.role)}: ${message.text.trim()}`)
    .join("\n\n")
    .slice(-16_000)
    .trim();
  if (!context) {
    return undefined;
  }
  return [
    "Use this recent conversation context when answering the latest user request.",
    "Do not repeat the context verbatim unless the user asks for it.",
    "",
    "<conversation_context>",
    context,
    "</conversation_context>"
  ].join("\n");
}

function hermesRoleLabel(role: string): string {
  const normalized = role.toLowerCase();
  if (normalized === "user") {
    return "User";
  }
  if (normalized === "assistant") {
    return "Assistant";
  }
  if (normalized === "system") {
    return "System";
  }
  return "Message";
}

function remoteSessionAdvanced(previous: RemoteSessionObservation | undefined, session: ChatSessionSummary): boolean {
  if (!previous) {
    return true;
  }
  const updatedAt = session.updatedAt ?? null;
  if (updatedAt == null || previous.updatedAt == null) {
    return true;
  }
  return updatedAt > previous.updatedAt;
}

function hermesExternalRunId(session: ChatSessionSummary, message: ChatHistoryMessage): string {
  const sessionMarker = session.sessionId ?? session.key;
  const messageMarker = message.id ?? message.timestamp?.toString() ?? session.updatedAt?.toString() ?? hashString(message.text);
  return `hermes-external:${sessionMarker}:${messageMarker}`;
}

function hashString(value: string): string {
  let hash = 0;
  for (let index = 0; index < value.length; index += 1) {
    hash = ((hash << 5) - hash + value.charCodeAt(index)) | 0;
  }
  return Math.abs(hash).toString(36);
}

function timestampMs(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) {
    return value < 10_000_000_000 ? value * 1000 : value;
  }
  if (typeof value === "string" && value.trim()) {
    const parsed = Date.parse(value);
    return Number.isFinite(parsed) ? parsed : null;
  }
  return null;
}
