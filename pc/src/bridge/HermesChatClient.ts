import { execFile } from "node:child_process";
import { randomUUID } from "node:crypto";
import { join } from "node:path";
import { promisify } from "node:util";
import { HermesApiClient } from "../dispatcher/HermesApiClient.js";
import { HermesRunDriver, type HermesActiveRun, type HermesRunDriverEvent } from "../dispatcher/HermesRunDriver.js";
import type { ChatAttachment, ChatCommandOption, ChatHistoryMessage, ChatModelOption, ChatSessionSummary, ChatToolSummary } from "../protocol/messages.js";
import { resolveCommand, type CommandResolution } from "../host/CommandDiscovery.js";
import type { BridgeConfig } from "./config.js";
import { discoverHermesModels } from "./HermesModelDiscovery.js";
import { InMemoryHarnessSessionStore, type HarnessStoredSession } from "./harness/InMemoryHarnessSessionStore.js";
import type { GatewayChatSendResult, GatewayEvent, GatewayEventHandler } from "./chat/ChatTransportTypes.js";

interface ActiveChatRun {
  sessionKey: string;
  active: HermesActiveRun;
  mode: "api" | "cli";
}

interface RemoteSessionObservation {
  updatedAt: number | null;
  latestRunId?: string;
}

const execFileAsync = promisify(execFile);

function sendAgentDebugLog(runId: string, hypothesisId: string, location: string, message: string, data: Record<string, unknown>): void {
  // #region agent log
  fetch('http://127.0.0.1:7837/ingest/4052aa84-fb93-478a-bce2-a86b2ed750c1',{method:'POST',headers:{'Content-Type':'application/json','X-Debug-Session-Id':'5e993b'},body:JSON.stringify({sessionId:'5e993b',runId,hypothesisId,location,message,data,timestamp:Date.now()})}).catch(()=>{});
  // #endregion
}

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

function directStringField(value: unknown, keys: string[]): string | undefined {
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
  return undefined;
}

function normalizeHermesApiModels(payload: unknown, defaultModel: string): ChatModelOption[] {
  const rawModels = Array.isArray(asRecord(payload)?.data)
    ? asRecord(payload)?.data as unknown[]
    : Array.isArray(asRecord(payload)?.models)
      ? asRecord(payload)?.models as unknown[]
      : [];
  return rawModels
    .map((item) => {
      const record = asRecord(item);
      const id = firstStringField(record, ["id", "model", "name"]) ?? defaultModel;
      const provider = directStringField(record, ["provider", "providerId", "provider_id"]) ?? "hermes";
      const modelId = hermesApiSelectionId(provider, id);
      const name = directStringField(record, ["label", "displayName", "display_name", "name"]) ?? id;
      return {
        id: modelId,
        label: provider === "hermes" ? name : `${provider} / ${name}`,
        provider,
        modelId,
        contextWindow: firstNumberField(record, ["contextWindow", "context_window", "context_length", "maxContextTokens"]),
        available: true
      };
    });
}

function mergeHermesModels(apiModels: ChatModelOption[], discoveredModels: ChatModelOption[], defaultModel: string): ChatModelOption[] {
  if (apiModels.length === 0) {
    return discoveredModels.length > 0 ? discoveredModels : [{
      id: defaultModel,
      label: defaultModel,
      provider: "hermes",
      modelId: defaultModel,
      available: true
    }];
  }

  const discoveredByKey = new Map(discoveredModels.flatMap((model) => modelKeys(model).map((key) => [key, model] as const)));
  const seen = new Set<string>();
  const suppressGenericHermesProxy = discoveredModels.length > 0;
  const merged = apiModels.flatMap((apiModel) => {
    if (suppressGenericHermesProxy && isGenericHermesProxyModel(apiModel, discoveredModels, defaultModel)) {
      return [];
    }
    const discovered = modelKeys(apiModel)
      .map((key) => discoveredByKey.get(key))
      .find(Boolean);
    for (const key of modelKeys(apiModel)) {
      seen.add(key);
    }
    if (!discovered) {
      return [apiModel];
    }
    for (const key of modelKeys(discovered)) {
      seen.add(key);
    }
    return [{
      ...discovered,
      ...apiModel,
      contextWindow: apiModel.contextWindow ?? discovered.contextWindow ?? null,
      reasoningOptions: apiModel.reasoningOptions ?? discovered.reasoningOptions ?? null,
      defaultReasoningEffort: apiModel.defaultReasoningEffort ?? discovered.defaultReasoningEffort ?? null
    }];
  });

  for (const discovered of discoveredModels) {
    if (!modelKeys(discovered).some((key) => seen.has(key))) {
      merged.push(discovered);
    }
  }
  return merged;
}

function hermesApiSelectionId(provider: string, id: string): string {
  return provider && provider !== "hermes" && !id.includes(":") ? `${provider}:${id}` : id;
}

function modelKeys(model: ChatModelOption): string[] {
  return [...new Set([model.id, model.modelId ?? undefined].filter((value): value is string => Boolean(value)))];
}

function isGenericHermesProxyModel(apiModel: ChatModelOption, discoveredModels: ChatModelOption[], defaultModel: string): boolean {
  if (apiModel.provider !== "hermes") {
    return false;
  }
  const apiBareId = bareSelectionModel(apiModel.id);
  if (!apiBareId) {
    return false;
  }
  return apiModel.id === defaultModel || discoveredModels.some((model) => model.provider !== "hermes" && bareSelectionModel(model.modelId ?? model.id) === apiBareId);
}

function bareSelectionModel(value: string): string {
  const separator = value.indexOf(":");
  return separator > 0 ? value.slice(separator + 1) : value;
}

function normalizeHermesSkills(payload: unknown): ChatCommandOption[] {
  const rawSkills = Array.isArray(asRecord(payload)?.data)
    ? asRecord(payload)?.data as unknown[]
    : Array.isArray(asRecord(payload)?.skills)
      ? asRecord(payload)?.skills as unknown[]
      : [];
  return rawSkills.flatMap((item) => {
    const record = asRecord(item);
    const name = directStringField(record, ["name", "id"]);
    if (!name) {
      return [];
    }
    return [{
      name,
      description: directStringField(record, ["description", "summary"]),
      category: directStringField(record, ["category"]),
      textAliases: [`/skill ${name}`],
      source: "skill",
      acceptsArgs: true,
      args: [{ name: "input", description: "Optional prompt or instructions for this skill", type: "string", required: false }]
    }];
  });
}

function normalizeHermesToolsets(payload: unknown): ChatToolSummary[] {
  const rawToolsets = Array.isArray(asRecord(payload)?.data)
    ? asRecord(payload)?.data as unknown[]
    : Array.isArray(asRecord(payload)?.toolsets)
      ? asRecord(payload)?.toolsets as unknown[]
      : [];
  const tools: ChatToolSummary[] = [];
  const seen = new Set<string>();
  for (const item of rawToolsets) {
    const record = asRecord(item);
    if (record?.enabled === false) {
      continue;
    }
    const group = directStringField(record, ["label", "name"]);
    const source = directStringField(record, ["name"]);
    const rawTools = Array.isArray(record?.tools) ? record.tools as unknown[] : [];
    for (const rawTool of rawTools) {
      const rawToolRecord = asRecord(rawTool);
      const id = typeof rawTool === "string" ? rawTool : directStringField(rawToolRecord, ["id", "name"]);
      if (!id || seen.has(id)) {
        continue;
      }
      seen.add(id);
      tools.push({
        id,
        label: id,
        description: typeof rawTool === "string" ? null : directStringField(rawToolRecord, ["description"]),
        source,
        group
      });
    }
  }
  return tools;
}

function cliPrompt(message: string, instructions: string | undefined): string {
  return instructions?.trim()
    ? `${instructions.trim()}\n\nCurrent user message:\n${message}`
    : message;
}

function hermesCliModelArgs(model: string | undefined): string[] {
  const trimmed = model?.trim();
  if (!trimmed) {
    return [];
  }
  const separator = trimmed.indexOf(":");
  if (separator > 0) {
    const provider = trimmed.slice(0, separator).trim();
    const modelId = trimmed.slice(separator + 1).trim();
    return provider && modelId ? ["--provider", provider, "--model", modelId] : ["--model", trimmed];
  }
  return ["--model", trimmed];
}

function hermesCliThinkingArgs(_thinking: string | undefined): string[] {
  return [];
}

export class HermesChatClient {
  private readonly api?: HermesApiClient;
  private readonly driver?: HermesRunDriver;
  private readonly cli: CommandResolution;
  private readonly sessions: InMemoryHarnessSessionStore;
  private readonly handlers = new Set<GatewayEventHandler>();
  private readonly activeRuns = new Map<string, ActiveChatRun>();
  private readonly remoteSessionObservations = new Map<string, RemoteSessionObservation>();
  private readonly debugDeltaRunIds = new Set<string>();
  private remoteSessionsInitialized = false;

  constructor(
    private readonly config: BridgeConfig,
    api?: HermesApiClient,
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
    const payload = await this.api?.listSessionMessages(session.sessionId).catch(() => undefined);
    const remoteMessages = normalizeHermesMessages(payload);
    sendAgentDebugLog("history", "H1,H2", "HermesChatClient.ts:111", "Hermes history resolved", {
      sessionKey,
      sessionId: session.sessionId,
      localMessageCount: local.messages.length,
      remoteMessageCount: remoteMessages.length,
      returnedSource: remoteMessages.length === 0 ? "local" : "remote",
      localRoles: local.messages.map((message) => message.role),
      remoteRoles: remoteMessages.map((message) => message.role)
    });
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

    if (!await this.shouldUseRunsApi()) {
      return this.sendCliChat(session, options, instructions);
    }
    const driver = this.driver;
    if (!driver) {
      throw new Error("Hermes runs API is not configured and Hermes CLI fallback is unavailable.");
    }
    const active = await driver.createRun({
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
    sendAgentDebugLog(runId, "H1,H4", "HermesChatClient.ts:145", "Hermes run created for chat send", {
      requestedSessionKey: options.sessionKey,
      resolvedSessionKey: session.key,
      requestedSessionId: options.sessionId ?? null,
      resolvedSessionId: session.sessionId,
      activeSessionId: active.sessionId,
      localMessageCount: session.messages.length,
      model: session.model ?? null,
      inputLength: options.message.length,
      hasAttachments: Boolean(options.attachments?.length),
      serviceTier: session.fastMode === true ? "priority" : null
    });
    if (instructions) {
      sendAgentDebugLog(runId, "H6", "HermesChatClient.ts:160", "Hermes contextual instructions prepared", {
        sessionKey: session.key,
        sessionId: session.sessionId,
        contextMessageCount: this.sessions.historyMessages(session).slice(0, -1).length,
        instructionLength: instructions.length
      });
    }
    this.activeRuns.set(runId, {
      sessionKey: session.key,
      active,
      mode: "api"
    });
    void this.processRun(session.key, active);
    return { runId, sessionKey: session.key };
  }

  async abort(_sessionKey: string, runId?: string): Promise<unknown> {
    if (!runId) {
      return {};
    }
    const active = this.activeRuns.get(runId);
    if (active) {
      const session = this.sessions.ensureSession(active.sessionKey);
      const partialAssistant = session.messages.find((message) => message.id === `assistant_${runId}`);
      sendAgentDebugLog(runId, "H3,H4", "HermesChatClient.ts:174", "Hermes abort requested for active run", {
        requestedSessionKey: _sessionKey,
        activeSessionKey: active.sessionKey,
        activeSessionId: active.active.sessionId,
        localMessageCount: session.messages.length,
        partialAssistantLength: partialAssistant?.text.length ?? 0
      });
      if (active.mode === "api") {
        await this.driver?.stopRun(active.active);
      } else {
        active.active.controller.abort();
      }
    } else {
      sendAgentDebugLog(runId, "H1,H3", "HermesChatClient.ts:184", "Hermes abort requested for untracked run", {
        requestedSessionKey: _sessionKey
      });
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
    if (active.mode !== "api") {
      throw new Error("Hermes CLI fallback does not support active-turn steering.");
    }
    const session = this.sessions.ensureSession(active.sessionKey, options.sessionId);
    this.sessions.appendUserMessage(session, options.message, options.idempotencyKey, options.attachments);
    await this.driver?.steerRun(
      active.active,
      options.message,
      options.attachments,
      session.fastMode === true ? "priority" : null
    );
    return { runId: active.active.runId, sessionKey: active.sessionKey };
  }

  async listModels(): Promise<unknown> {
    const payload = await this.api?.listModels().catch(() => undefined);
    const apiModels = normalizeHermesApiModels(payload, this.config.hermesModel);
    const discoveredModels = discoverHermesModels(this.config.hermesModel);
    const models = mergeHermesModels(apiModels, discoveredModels, this.config.hermesModel);
    return { models };
  }

  async listSessions(limit = 50): Promise<unknown> {
    const remotePayload = await this.api?.listSessions().catch(() => undefined);
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
    if (!this.api) {
      return;
    }
    const remotePayload = await this.api.listSessions().catch(() => undefined);
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
    const skills = this.api ? normalizeHermesSkills(await this.api.listSkills().catch(() => undefined)) : [];
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
    const capabilities = await this.api?.capabilities().catch(() => undefined);
    const toolsets = await this.api?.listToolsets().catch(() => undefined);
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
    const payload = await this.api?.listSessionMessages(sessionId).catch(() => undefined);
    return normalizeHermesMessages(payload)
      .slice()
      .reverse()
      .find((message) => message.role.toLowerCase() === "assistant" && message.text.trim());
  }

  private async processRun(sessionKey: string, active: HermesActiveRun): Promise<void> {
    const session = this.sessions.ensureSession(sessionKey);
    const runId = active.runId;
    const driver = this.driver;
    if (!driver) {
      return;
    }
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
      sendAgentDebugLog(runId, "H1,H4", "HermesChatClient.ts:395", "Hermes run finalized", {
        sessionKey,
        sessionId: session.sessionId,
        finalLength: finalText.length,
        localMessageCount: session.messages.length
      });
    } catch (error) {
      const partialAssistant = session.messages.find((message) => message.id === `assistant_${runId}`);
      sendAgentDebugLog(runId, "H3,H4", "HermesChatClient.ts:403", "Hermes run processing ended with error or abort", {
        sessionKey,
        sessionId: session.sessionId,
        aborted: active.controller.signal.aborted,
        partialAssistantLength: partialAssistant?.text.length ?? 0,
        localMessageCount: session.messages.length,
        errorName: error instanceof Error ? error.name : typeof error
      });
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

  private async shouldUseRunsApi(): Promise<boolean> {
    if (!this.api || !this.driver) {
      return false;
    }
    try {
      const health = await this.api.health();
      return isHealthyHermesResponse(health);
    } catch {
      return false;
    }
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
    if (!this.debugDeltaRunIds.has(runId)) {
      this.debugDeltaRunIds.add(runId);
      sendAgentDebugLog(runId, "H4", "HermesChatClient.ts:431", "Hermes first assistant delta stored in memory", {
        sessionKey: session.key,
        sessionId: session.sessionId,
        accumulatedLength: event.accumulated.length,
        localMessageCount: session.messages.length
      });
    }
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
