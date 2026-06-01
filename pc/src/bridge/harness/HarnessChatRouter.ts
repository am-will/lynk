import type { AuditLog } from "../AuditLog.js";
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
} from "../AgentHarness.js";
import { CodexChatClient } from "../CodexChatClient.js";
import type { BridgeConfig } from "../config.js";
import type { ChatCommandOption } from "../../protocol/messages.js";
import { HermesChatClient } from "../HermesChatClient.js";
import { OpenCodeChatClient } from "../opencode/OpenCodeChatClient.js";
import type { GatewayChatClient } from "../OpenClawChatTypes.js";
import { assertHarnessSupportsAttachments } from "../chat/ChatSendAttachments.js";
import type {
  GatewayChatSendResult,
  GatewayEventHandler,
  HarnessChatSendOptions,
  HarnessChatSteerOptions,
  HarnessPermissionReplyOptions
} from "../chat/ChatTransportTypes.js";
import { OpenClawGatewayChatClient } from "../OpenClawGatewayChatClient.js";
import { NormalizedHarnessAdapter, type HarnessChatAdapter, type HarnessCreatedSession } from "./HarnessChatAdapter.js";

const OPENCLAW_BRIDGE_COMMANDS: ChatCommandOption[] = [
  {
    name: "verbose",
    description: "Set OpenClaw verbose output level",
    category: "Options",
    textAliases: ["/verbose"],
    acceptsArgs: true,
    args: [{
      name: "level",
      description: "on, off, or full",
      type: "string",
      required: false
    }]
  },
  {
    name: "reasoning",
    description: "Set OpenClaw reasoning stream visibility",
    category: "Options",
    textAliases: ["/reasoning"],
    acceptsArgs: true,
    args: [{
      name: "level",
      description: "stream/on to show reasoning, off to hide it",
      type: "string",
      required: false
    }]
  }
];

export class HarnessChatRouter implements GatewayChatClient {
  private readonly adapters = new Map<HarnessId, HarnessChatAdapter>();

  constructor(
    private readonly config: BridgeConfig,
    audit?: AuditLog,
    adapters?: Iterable<HarnessChatAdapter>
  ) {
    if (adapters) {
      for (const adapter of adapters) {
        this.adapters.set(adapter.harnessId, adapter);
      }
      return;
    }
    this.adapters.set("openclaw", new NormalizedHarnessAdapter("openclaw", new OpenClawGatewayChatClient(config), { supportsAttachments: true }));
    if (config.hermesConfigured ?? config.hermesApiKey) {
      this.adapters.set("hermes", new NormalizedHarnessAdapter("hermes", new HermesChatClient(config), { supportsAttachments: true }));
    }
    if (config.codexConfigured) {
      this.adapters.set("codex", new NormalizedHarnessAdapter("codex", new CodexChatClient(audit, undefined, undefined, {
        command: config.codexAppServerCommand,
        cwd: config.codexAgentCwd,
        approvalPolicy: config.codexAppServerApprovalPolicy,
        sandbox: config.codexAppServerSandbox
      }), { supportsAttachments: true }));
    }
    if (config.opencodeConfigured) {
      this.adapters.set("opencode", new NormalizedHarnessAdapter("opencode", new OpenCodeChatClient(audit, undefined, undefined, {
        serverUrl: config.opencodeServerUrl,
        command: config.opencodeServerCommand,
        cwd: config.opencodeAgentCwd,
        username: config.opencodeServerUsername,
        password: config.opencodeServerPassword,
        defaultAgent: config.opencodeDefaultAgent,
        timeoutMs: config.opencodeRunTimeoutMs
      }), { supportsAttachments: true }));
    }
  }

  addEventListener(handler: GatewayEventHandler): () => void {
    const unsubscribers = [...this.adapters.values()].map((adapter) => adapter.addEventListener(handler));
    return () => {
      for (const unsubscribe of unsubscribers) {
        unsubscribe();
      }
    };
  }

  async history(sessionKey: string): Promise<unknown> {
    return await this.adapterForSession(sessionKey).history(sessionKey);
  }

  async sendChat(options: HarnessChatSendOptions): Promise<GatewayChatSendResult> {
    const adapter = this.adapterForSession(options.sessionKey);
    assertHarnessSupportsAttachments(adapter.harnessId, adapter.capabilities, options.attachments);
    return await adapter.sendChat(options);
  }

  async steerChat(options: HarnessChatSteerOptions): Promise<GatewayChatSendResult> {
    const adapter = this.adapterForSession(options.sessionKey);
    assertHarnessSupportsAttachments(adapter.harnessId, adapter.capabilities, options.attachments);
    if (!adapter.steerChat) {
      throw new Error(`${adapter.harnessId} harness does not support active-turn steering.`);
    }
    return await adapter.steerChat(options);
  }

  async abort(sessionKey: string, runId?: string): Promise<unknown> {
    return await this.adapterForSession(sessionKey).abort(sessionKey, runId);
  }

  async listModels(): Promise<unknown> {
    const models = [];
    for (const info of harnessInfos(this.config)) {
      if (!info.enabled) {
        continue;
      }
      const adapter = this.adapters.get(info.id);
      if (!adapter) {
        continue;
      }
      const harnessModels = await adapter.listModels().catch(() => []);
      for (const model of harnessModels) {
        models.push(namespaceModelOption(model, info.id));
      }
    }
    return { models };
  }

  async listSessions(limit = 50, harnessId: HarnessId = DEFAULT_HARNESS_ID): Promise<unknown> {
    const snapshot = await this.adapterForHarness(harnessId).listSessions(limit);
    return {
      sessions: snapshot.sessions.map((session) => namespaceSessionSummary(session, harnessId)),
      defaults: {
        thinkingLevels: snapshot.reasoningOptions.map((option) => option.id)
      }
    };
  }

  async syncRemoteReplies(harnessId: HarnessId = DEFAULT_HARNESS_ID, limit = 50): Promise<void> {
    await this.adapterForHarness(harnessId).syncRemoteReplies?.(limit);
  }

  async createSession(options: { key?: string; label?: string; model?: string; workspacePath?: string; createWorkspaceIfMissing?: boolean }): Promise<unknown> {
    const selection = parseHarnessModel(options.model);
    const harnessId = explicitHarnessForSessionKey(options.key) ?? selection?.harnessId ?? harnessForSessionKey(options.key);
    const adapter = this.adapterForHarness(harnessId);
    const key = this.keyForHarness(harnessId, options.key);
    const created = await adapter.createSession({
      key,
      label: options.label,
      model: selection?.modelId ?? options.model,
      ...((harnessId === "codex" || harnessId === "opencode") && options.workspacePath ? { workspacePath: options.workspacePath } : {}),
      ...((harnessId === "codex" || harnessId === "opencode") && options.createWorkspaceIfMissing ? { createWorkspaceIfMissing: true } : {})
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
    return await this.adapterForHarness(harnessId).patchSession(sessionKey, nextPatch);
  }

  async listCommands(sessionKey?: string): Promise<unknown> {
    const harnessId = harnessForSessionKey(sessionKey);
    const commands = await this.adapterForHarness(harnessId).listCommands(sessionKey);
    if (harnessId === "openclaw") {
      return { commands: withOpenClawBridgeCommands(commands) };
    }
    return { commands: await this.withOpenClawSkills(commands) };
  }

  async effectiveTools(sessionKey: string): Promise<unknown> {
    return { tools: await this.adapterForSession(sessionKey).effectiveTools(sessionKey) };
  }

  async respondToPermission(options: HarnessPermissionReplyOptions): Promise<unknown> {
    const adapter = this.adapterForSession(options.sessionKey);
    if (!adapter.respondToPermission) {
      throw new Error(`${adapter.harnessId} harness does not support permission replies.`);
    }
    return await adapter.respondToPermission(options);
  }

  async health(): Promise<unknown> {
    const health: Record<string, unknown> = {};
    for (const [harnessId, adapter] of this.adapters.entries()) {
      health[harnessId] = await adapter.health().catch((error) => ({
        ok: false,
        error: error instanceof Error ? error.message : String(error)
      }));
    }
    return { harnesses: health };
  }

  close(): void {
    for (const adapter of this.adapters.values()) {
      adapter.close();
    }
  }

  defaultSessionKey(harnessId: HarnessId, deviceId: string): string {
    return defaultSessionKeyForHarness(harnessId, this.config, deviceId);
  }

  encodeModel(harnessId: HarnessId, modelId: string): string {
    return encodeHarnessModel(harnessId, modelId);
  }

  private adapterForSession(sessionKey: string | undefined): HarnessChatAdapter {
    return this.adapterForHarness(harnessForSessionKey(sessionKey));
  }

  private adapterForHarness(harnessId: HarnessId): HarnessChatAdapter {
    const adapter = this.adapters.get(harnessId);
    if (!adapter) {
      throw new Error(`${harnessId} harness is not configured.`);
    }
    return adapter;
  }

  private async withOpenClawSkills(commands: ChatCommandOption[]): Promise<ChatCommandOption[]> {
    const openclaw = this.adapters.get("openclaw");
    if (!openclaw) {
      return commands;
    }
    const openclawCommands = await openclaw.listCommands().catch(() => []);
    const existingSkillNames = new Set(
      commands
        .filter(isSkillCommand)
        .map((command) => command.name.toLowerCase())
    );
    const sharedSkills = openclawCommands
      .filter(isSkillCommand)
      .filter((command) => {
        const key = command.name.toLowerCase();
        if (existingSkillNames.has(key)) {
          return false;
        }
        existingSkillNames.add(key);
        return true;
      });
    return [...commands, ...sharedSkills];
  }

  private keyForHarness(harnessId: HarnessId, key: string | undefined): string | undefined {
    if (!key || harnessId === "openclaw" || key.startsWith(`${harnessId}:`)) {
      return key;
    }
    return `${harnessId}:${key}`;
  }

  private namespaceCreatedSession(created: HarnessCreatedSession, harnessId: HarnessId, fallbackKey?: string): HarnessCreatedSession {
    const rawKey = created.key?.trim() || fallbackKey;
    const key = harnessId === "openclaw" || !rawKey || rawKey.startsWith(`${harnessId}:`)
      ? rawKey
      : `${harnessId}:${rawKey}`;
    return {
      ...created,
      ...(key ? { key } : {}),
      harnessId,
      harnessLabel: harnessInfos(this.config).find((info) => info.id === harnessId)?.label
    };
  }
}

function isSkillCommand(command: ChatCommandOption): boolean {
  return command.source?.toLowerCase() === "skill";
}

function withOpenClawBridgeCommands(commands: ChatCommandOption[]): ChatCommandOption[] {
  const existingNames = new Set(commands.map((command) => command.name.toLowerCase()));
  const bridgeCommands = OPENCLAW_BRIDGE_COMMANDS.filter((command) => {
    const key = command.name.toLowerCase();
    if (existingNames.has(key)) {
      return false;
    }
    existingNames.add(key);
    return true;
  });
  return [...commands, ...bridgeCommands];
}

function explicitHarnessForSessionKey(sessionKey: string | undefined): HarnessId | undefined {
  const harnessId = harnessForSessionKey(sessionKey);
  return harnessId === DEFAULT_HARNESS_ID ? undefined : harnessId;
}
