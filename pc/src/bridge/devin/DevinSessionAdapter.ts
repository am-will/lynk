import type {
  AvailableCommand,
  SessionConfigOption,
  SessionMode,
  SessionModeState
} from "@agentclientprotocol/sdk";
import { isAbsolute, resolve } from "node:path";
import type {
  ChatCommandOption,
  ChatModelOption,
  ChatReasoningOption,
  ChatSessionSummary,
  ChatToolSummary
} from "../../protocol/messages.js";
import type { HarnessId } from "../AgentHarness.js";
import { ChatClientError } from "../chat/ChatErrors.js";
import type {
  GatewayChatSendResult,
  GatewayEventHandler,
  HarnessCapabilities,
  HarnessChatSendOptions,
  HarnessChatSteerOptions,
  HarnessPermissionReplyOptions
} from "../chat/ChatTransportTypes.js";
import type {
  HarnessChatAdapter,
  HarnessChatHistory,
  HarnessCreatedSession,
  HarnessSessionList
} from "../harness/HarnessChatAdapter.js";
import { InMemoryHarnessSessionStore, type HarnessStoredSession } from "../harness/InMemoryHarnessSessionStore.js";
import { DevinAcpClient } from "./DevinAcpClient.js";
import type { DevinAcpEvent, DevinAcpProcessFactory } from "./DevinAcpTypes.js";
import { chatCommandsFromAvailableCommands, DevinSessionUpdateCollector } from "./DevinHistoryReplay.js";
import { DevinSessionCatalog } from "./DevinSessionCatalog.js";
import {
  chatModelOptionsFromDevinConfig,
  devinConfigFromOptions,
  selectDevinModelConfigId,
  selectDevinThoughtConfigId,
  type DevinEffectiveConfig
} from "./DevinSessionConfig.js";
import { prepareDevinWorkspace } from "./DevinWorkspace.js";

export interface DevinSessionAdapterOptions {
  client?: DevinAcpClient;
  command?: string;
  cwd?: string;
  storagePath: string;
  runTimeoutMs?: number;
  startupTimeoutMs?: number;
  requestTimeoutMs?: number;
  processFactory?: DevinAcpProcessFactory;
}

export class DevinSessionAdapter implements HarnessChatAdapter {
  readonly harnessId: HarnessId = "devin";
  readonly capabilities: HarnessCapabilities = { supportsAttachments: false };

  private readonly client: DevinAcpClient;
  private readonly store: InMemoryHarnessSessionStore;
  private readonly catalog: DevinSessionCatalog;
  private readonly workspaceCwd: string;

  private transportGeneration = 0;
  private readonly attachedSessions = new Set<string>();
  private readonly configCache = new Map<string, DevinEffectiveConfig>();
  private readonly commandsCache = new Map<string, ChatCommandOption[]>();
  private readonly currentModeCache = new Map<string, { currentModeId?: string; availableModes?: SessionMode[] }>();
  private modelCache?: { generation: number; config: DevinEffectiveConfig; models: ChatModelOption[] };

  constructor(options: DevinSessionAdapterOptions) {
    this.workspaceCwd = resolveAbsoluteCwd(options.cwd);
    this.client = options.client ?? new DevinAcpClient({
      command: options.command,
      cwd: this.workspaceCwd,
      startupTimeoutMs: options.startupTimeoutMs,
      requestTimeoutMs: options.requestTimeoutMs ?? options.runTimeoutMs,
      processFactory: options.processFactory
    });
    this.store = new InMemoryHarnessSessionStore("devin", {
      defaultModel: "default",
      modelProvider: "devin",
      storagePath: options.storagePath,
      persistEmptySessions: true
    });
    this.catalog = new DevinSessionCatalog({
      client: this.client,
      store: this.store,
      toSummary: (session) => this.toSummary(session)
    });
    this.client.addEventListener((event) => this.handleClientEvent(event));
  }

  addEventListener(_handler: GatewayEventHandler): () => void {
    // Wave 4 owns normalization of live ACP updates into Lynk chat events.
    return () => undefined;
  }

  async history(sessionKey: string): Promise<HarnessChatHistory> {
    const sessionId = devinSessionIdFromKey(sessionKey);
    if (!this.attachedSessions.has(sessionId)) {
      await this.attachSession(sessionKey, sessionId);
    }
    return this.store.history(sessionKey);
  }

  async sendChat(_options: HarnessChatSendOptions): Promise<GatewayChatSendResult> {
    throw new Error("Devin live chat streaming is implemented in Wave 4.");
  }

  async steerChat(_options: HarnessChatSteerOptions): Promise<GatewayChatSendResult> {
    throw new Error("Devin active-turn steering is implemented in Wave 4.");
  }

  async abort(_sessionKey: string, _runId?: string): Promise<unknown> {
    throw new Error("Devin run cancellation is implemented in Wave 4.");
  }

  async listModels(): Promise<ChatModelOption[]> {
    if (this.modelCache?.generation === this.transportGeneration) {
      return this.modelCache.models;
    }
    const collector = new DevinSessionUpdateCollector(this.client);
    try {
      const probe = await this.client.sessionNew({ cwd: this.workspaceCwd, mcpServers: [] });
      const snapshot = collector.snapshot(probe.sessionId);
      const config = devinConfigFromOptions(probe.configOptions ?? snapshot.configOptions);
      const models = chatModelOptionsFromDevinConfig(config);
      if (this.client.snapshot?.closeSession) {
        await this.client.sessionClose({ sessionId: probe.sessionId }).catch(() => undefined);
      }
      this.modelCache = { generation: this.transportGeneration, config, models };
      return models;
    } finally {
      collector.detach();
    }
  }

  async listSessions(limit = 50): Promise<HarnessSessionList> {
    const result = await this.catalog.listSessions(limit);
    return { sessions: result.sessions, reasoningOptions: this.reasoningOptions() };
  }

  async syncRemoteReplies(_limit = 50): Promise<void> {
    // ACP session/list and session/load are the authoritative synchronization path.
  }

  async createSession(options: {
    key?: string;
    label?: string;
    model?: string;
    workspacePath?: string;
    createWorkspaceIfMissing?: boolean;
  }): Promise<HarnessCreatedSession> {
    const workspacePath = prepareDevinWorkspace(
      options.workspacePath,
      options.createWorkspaceIfMissing ?? false,
      this.workspaceCwd
    );
    const cwd = workspacePath ?? this.workspaceCwd;
    const collector = new DevinSessionUpdateCollector(this.client);
    try {
      const created = await this.client.sessionNew({ cwd, mcpServers: [] });
      const sessionId = created.sessionId;
      const sessionKey = `devin:${sessionId}`;
      const snapshot = collector.snapshot(sessionId);
      let config = devinConfigFromOptions(created.configOptions ?? snapshot.configOptions);

      const requestedModel = options.model?.trim();
      if (requestedModel) {
        config = await this.applyRequestedConfig(sessionId, config, "model", requestedModel);
      }

      this.applyConfig(sessionKey, sessionId, config.options, created.modes ?? snapshot.currentModeState);
      this.applyCommands(sessionKey, sessionId, snapshot.commands);
      const session = this.store.ensureSession(sessionKey, sessionId);
      this.store.patchSession(sessionKey, {
        ...(options.label ? { displayName: options.label } : {}),
        model: config.modelConfig?.currentValue ?? requestedModel ?? "default",
        ...(config.thoughtConfig?.currentValue ? { thinking: config.thoughtConfig.currentValue } : {})
      });
      this.store.setMetadata(session, "workspacePath", cwd);
      this.store.setMetadata(session, "createdByLynk", true);
      this.attachedSessions.add(sessionId);

      return {
        key: sessionKey,
        sessionId,
        label: options.label ?? sessionId,
        displayName: options.label ?? null,
        harnessId: "devin",
        harnessLabel: "Devin",
        workspacePath: cwd,
        workspaceName: workspaceNameFromPath(cwd)
      };
    } finally {
      collector.detach();
    }
  }

  async patchSession(sessionKey: string, patch: Record<string, unknown>): Promise<void> {
    const sessionId = devinSessionIdFromKey(sessionKey);
    const requestedModel = stringPatch(patch, "model");
    const requestedReasoning = stringPatch(patch, "thinking");
    const displayName = stringPatch(patch, "displayName");
    if (displayName) {
      this.store.patchSession(sessionKey, { displayName });
    }
    if (!requestedModel && !requestedReasoning) {
      return;
    }
    if (!this.attachedSessions.has(sessionId)) {
      await this.attachSession(sessionKey, sessionId);
    }
    let config = this.configCache.get(sessionId) ?? devinConfigFromOptions([]);
    if (requestedModel) {
      config = await this.applyRequestedConfig(sessionId, config, "model", requestedModel);
    }
    if (requestedReasoning) {
      config = await this.applyRequestedConfig(sessionId, config, "reasoning", requestedReasoning);
    }
    this.applyConfig(sessionKey, sessionId, config.options);
  }

  async listCommands(sessionKey?: string): Promise<ChatCommandOption[]> {
    if (!sessionKey) {
      return [];
    }
    devinSessionIdFromKey(sessionKey);
    return this.commandsCache.get(sessionKey) ?? this.storedCommands(sessionKey);
  }

  async effectiveTools(_sessionKey: string): Promise<ChatToolSummary[]> {
    return [];
  }

  async respondToPermission(_options: HarnessPermissionReplyOptions): Promise<unknown> {
    throw new Error("Devin permission replies are implemented in Wave 4.");
  }

  async health(): Promise<unknown> {
    return this.client.health();
  }

  close(): void {
    void this.client.close();
  }

  private async attachSession(sessionKey: string, sessionId: string): Promise<void> {
    const session = this.store.requireSession(sessionKey);
    const workspacePath = typeof session?.metadata?.workspacePath === "string"
      ? session.metadata.workspacePath
      : this.workspaceCwd;
    const collector = new DevinSessionUpdateCollector(this.client);
    try {
      const loaded = await this.client.sessionLoad({ sessionId, cwd: workspacePath, mcpServers: [] });
      const snapshot = collector.snapshot(sessionId);
      this.store.replaceHistory(sessionKey, snapshot.messages);
      this.applyConfig(sessionKey, sessionId, loaded.configOptions ?? snapshot.configOptions, loaded.modes ?? snapshot.currentModeState);
      this.applyCommands(sessionKey, sessionId, snapshot.commands);
      const stored = this.store.ensureSession(sessionKey, sessionId);
      this.store.setMetadata(stored, "workspacePath", workspacePath);
      this.attachedSessions.add(sessionId);
    } finally {
      collector.detach();
    }
  }

  private async applyRequestedConfig(
    sessionId: string,
    config: DevinEffectiveConfig,
    kind: "model" | "reasoning",
    value: string
  ): Promise<DevinEffectiveConfig> {
    const configId = kind === "model"
      ? selectDevinModelConfigId(config, value)
      : selectDevinThoughtConfigId(config, value);
    if (!configId) {
      return config;
    }
    const response = await this.client.sessionSetConfigOption({ sessionId, configId, value });
    return devinConfigFromOptions(response.configOptions);
  }

  private applyConfig(
    sessionKey: string,
    sessionId: string,
    options: SessionConfigOption[] | null | undefined,
    modes?: SessionModeState | null
  ): DevinEffectiveConfig {
    const config = devinConfigFromOptions(options);
    this.configCache.set(sessionId, config);
    const patch: Record<string, unknown> = {};
    if (config.modelConfig?.currentValue) patch.model = config.modelConfig.currentValue;
    if (config.thoughtConfig?.currentValue) patch.thinking = config.thoughtConfig.currentValue;
    if (Object.keys(patch).length > 0) this.store.patchSession(sessionKey, patch);
    const session = this.store.ensureSession(sessionKey, sessionId);
    this.store.setMetadata(session, "configOptions", config.options);
    if (modes?.currentModeId) {
      this.currentModeCache.set(sessionId, {
        currentModeId: modes.currentModeId,
        availableModes: modes.availableModes
      });
      this.store.setMetadata(session, "currentModeId", modes.currentModeId);
      this.store.setMetadata(session, "availableModes", modes.availableModes);
    }
    return config;
  }

  private applyCommands(sessionKey: string, sessionId: string, commands: AvailableCommand[]): void {
    const mapped = chatCommandsFromAvailableCommands(commands);
    this.commandsCache.set(sessionKey, mapped);
    const session = this.store.ensureSession(sessionKey, sessionId);
    this.store.setMetadata(session, "availableCommands", mapped);
  }

  private storedCommands(sessionKey: string): ChatCommandOption[] {
    const commands = this.store.requireSession(sessionKey)?.metadata?.availableCommands;
    return Array.isArray(commands) ? commands as ChatCommandOption[] : [];
  }

  private handleClientEvent(event: DevinAcpEvent): void {
    if (event.type === "session/update") {
      const sessionId = event.notification.sessionId;
      const key = `devin:${sessionId}`;
      if (event.notification.update.sessionUpdate === "available_commands_update") {
        this.applyCommands(key, sessionId, event.notification.update.availableCommands);
      }
      return;
    }
    if (event.state === "starting") this.transportGeneration += 1;
    if (event.state === "starting" || event.state === "failed" || event.state === "stopped") {
      this.attachedSessions.clear();
      this.configCache.clear();
      this.commandsCache.clear();
      this.currentModeCache.clear();
      this.modelCache = undefined;
    }
  }

  private reasoningOptions(): ChatReasoningOption[] {
    if (this.modelCache?.generation === this.transportGeneration) {
      return reasoningOptionsFromConfig(this.modelCache.config);
    }
    for (const config of this.configCache.values()) {
      const options = reasoningOptionsFromConfig(config);
      if (options.length > 0) return options;
    }
    for (const session of this.store.listStoredSessions(50)) {
      const raw = session.metadata?.configOptions;
      if (Array.isArray(raw)) {
        const options = reasoningOptionsFromConfig(devinConfigFromOptions(raw as SessionConfigOption[]));
        if (options.length > 0) return options;
      }
    }
    return [];
  }

  private toSummary(session: HarnessStoredSession): ChatSessionSummary {
    const metadata = session.metadata ?? {};
    const workspacePath = typeof metadata.workspacePath === "string" && isAbsolute(metadata.workspacePath)
      ? metadata.workspacePath
      : undefined;
    return {
      key: session.key,
      sessionId: session.sessionId,
      label: session.displayName ?? session.label,
      displayName: session.displayName,
      workspacePath,
      workspaceName: workspacePath ? workspaceNameFromPath(workspacePath) : undefined,
      source: "devin",
      updatedAt: typeof metadata.acpUpdatedAt === "number" ? metadata.acpUpdatedAt : session.updatedAt,
      model: session.model ?? "default",
      modelProvider: "devin",
      thinkingLevel: session.thinkingLevel ?? null,
      hasActiveRun: Boolean(session.activeRunId),
      harnessId: "devin",
      harnessLabel: "Devin"
    };
  }
}

function devinSessionIdFromKey(sessionKey: string): string {
  if (!sessionKey.startsWith("devin:")) {
    throw invalidSessionKey(sessionKey);
  }
  const sessionId = sessionKey.slice("devin:".length).trim();
  if (!sessionId) throw invalidSessionKey(sessionKey);
  return sessionId;
}

function invalidSessionKey(sessionKey: string): ChatClientError {
  return new ChatClientError(`Invalid Devin session key: ${sessionKey}`, { code: "devin.invalid_session_key" });
}

function workspaceNameFromPath(workspacePath: string): string {
  const segments = workspacePath.split(/[\\/]/).filter(Boolean);
  return segments[segments.length - 1] ?? workspacePath;
}

function resolveAbsoluteCwd(cwd: string | undefined): string {
  const trimmed = cwd?.trim();
  return trimmed ? (isAbsolute(trimmed) ? trimmed : resolve(process.cwd(), trimmed)) : process.cwd();
}

function stringPatch(patch: Record<string, unknown>, key: string): string | undefined {
  const value = patch[key];
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function reasoningOptionsFromConfig(config: DevinEffectiveConfig): ChatReasoningOption[] {
  return config.thoughtConfig?.options.map((option) => ({ id: option.value, label: option.label })) ?? [];
}
