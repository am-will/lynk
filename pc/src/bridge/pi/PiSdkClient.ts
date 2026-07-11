import { join } from "node:path";
import {
  AuthStorage,
  createAgentSessionFromServices,
  createAgentSessionRuntime,
  createAgentSessionServices,
  getAgentDir,
  ModelRegistry,
  SessionManager,
  type AgentSession,
  type AgentSessionRuntime,
  type CreateAgentSessionRuntimeFactory
} from "@earendil-works/pi-coding-agent";
import type { AuditLog } from "../AuditLog.js";
import { defaultWorkspaceRoot } from "../../host/HostPaths.js";

export interface PiSdkClientOptions {
  cwd?: string;
  agentDir?: string;
  defaultModel?: string;
  timeoutMs?: number;
}

export interface PiRuntimeOptions {
  cwd?: string;
  sessionPath?: string;
  model?: string;
  thinkingLevel?: string | null;
}

export type PiModel = ReturnType<ModelRegistry["getAll"]>[number];
export type PiThinkingLevel = Parameters<AgentSession["setThinkingLevel"]>[0];

const DEFAULT_TIMEOUT_MS = 600_000;
const PI_THINKING_LEVELS = new Set(["off", "minimal", "low", "medium", "high", "xhigh"]);

export class PiSdkClient {
  private readonly cwd: string;
  private readonly agentDir: string;
  private readonly defaultModel?: string;
  private readonly timeoutMs: number;
  private readonly authStorage: AuthStorage;
  private readonly modelRegistry: ModelRegistry;

  constructor(
    private readonly audit?: AuditLog,
    options: PiSdkClientOptions = {}
  ) {
    this.cwd = options.cwd?.trim() || process.env.PI_AGENT_CWD?.trim() || defaultWorkspaceRoot();
    this.agentDir = options.agentDir?.trim() || process.env.PI_AGENT_DIR?.trim() || getAgentDir();
    this.defaultModel = options.defaultModel?.trim() || process.env.PI_DEFAULT_MODEL?.trim() || undefined;
    const envTimeoutMs = Number.parseInt(process.env.PI_RUN_TIMEOUT_SECONDS ?? "", 10);
    this.timeoutMs = options.timeoutMs ?? (Number.isFinite(envTimeoutMs) && envTimeoutMs > 0 ? envTimeoutMs * 1000 : DEFAULT_TIMEOUT_MS);
    this.authStorage = AuthStorage.create(join(this.agentDir, "auth.json"));
    this.modelRegistry = ModelRegistry.create(this.authStorage, join(this.agentDir, "models.json"));
  }

  defaultCwd(): string {
    return this.cwd;
  }

  defaultAgentDir(): string {
    return this.agentDir;
  }

  runTimeoutMs(): number {
    return this.timeoutMs;
  }

  listModels(): PiModel[] {
    this.modelRegistry.refresh();
    return this.modelRegistry.getAvailable();
  }

  allModels(): PiModel[] {
    this.modelRegistry.refresh();
    return this.modelRegistry.getAll();
  }

  async health(): Promise<Record<string, unknown>> {
    try {
      const models = this.listModels();
      return {
        ok: models.length > 0,
        harness: "pi",
        agentDir: this.agentDir,
        cwd: this.cwd,
        modelCount: models.length,
        message: models.length > 0 ? "Pi SDK has available models." : "Pi SDK is installed, but no authenticated models are available."
      };
    } catch (error) {
      return {
        ok: false,
        harness: "pi",
        agentDir: this.agentDir,
        cwd: this.cwd,
        error: errorMessage(error)
      };
    }
  }

  async createRuntime(options: PiRuntimeOptions = {}): Promise<AgentSessionRuntime> {
    const cwd = options.cwd?.trim() || this.cwd;
    const sessionManager = options.sessionPath
      ? SessionManager.open(options.sessionPath, undefined, cwd)
      : SessionManager.create(cwd);
    return await this.createRuntimeForSessionManager(sessionManager, {
      cwd: sessionManager.getCwd() || cwd,
      model: options.model,
      thinkingLevel: options.thinkingLevel
    });
  }

  async switchRuntimeSession(
    runtime: AgentSessionRuntime,
    sessionPath: string,
    options: Pick<PiRuntimeOptions, "cwd" | "model" | "thinkingLevel"> = {}
  ): Promise<AgentSessionRuntime> {
    await runtime.switchSession(sessionPath, options.cwd ? { cwdOverride: options.cwd } : undefined);
    await this.applySessionControls(runtime.session, options.model, options.thinkingLevel);
    return runtime;
  }

  async newRuntimeSession(
    runtime: AgentSessionRuntime,
    options: Pick<PiRuntimeOptions, "model" | "thinkingLevel"> = {}
  ): Promise<AgentSessionRuntime> {
    await runtime.newSession();
    await this.applySessionControls(runtime.session, options.model, options.thinkingLevel);
    return runtime;
  }

  async runWithTimeout<T>(label: string, run: () => Promise<T>, onTimeout?: () => Promise<void>): Promise<T> {
    let timeout: NodeJS.Timeout | undefined;
    try {
      return await Promise.race([
        run(),
        new Promise<T>((_resolve, reject) => {
          timeout = setTimeout(() => {
            void onTimeout?.();
            reject(new Error(`${label} timed out after ${Math.round(this.timeoutMs / 1000)} seconds`));
          }, this.timeoutMs);
        })
      ]);
    } finally {
      if (timeout) {
        clearTimeout(timeout);
      }
    }
  }

  async abort(runtime: AgentSessionRuntime | undefined): Promise<void> {
    if (!runtime) {
      return;
    }
    await runtime.session.abort();
  }

  async close(runtime?: AgentSessionRuntime): Promise<void> {
    await runtime?.dispose();
  }

  findModel(selection: string | undefined | null): PiModel | undefined {
    const requested = selection?.trim() || this.defaultModel;
    const models = this.listModels();
    if (!requested) {
      return models[0];
    }
    const parsed = parsePiModelId(requested);
    return models.find((model) => {
      const id = piModelId(model);
      return id === requested || model.id === requested || (parsed ? model.provider === parsed.provider && model.id === parsed.modelId : false);
    }) ?? models[0];
  }

  normalizeThinkingLevel(level: string | null | undefined): PiThinkingLevel {
    const normalized = level?.trim() === "none" ? "off" : level?.trim();
    return (normalized && PI_THINKING_LEVELS.has(normalized) ? normalized : "medium") as PiThinkingLevel;
  }

  private async createRuntimeForSessionManager(
    sessionManager: SessionManager,
    options: Pick<PiRuntimeOptions, "cwd" | "model" | "thinkingLevel">
  ): Promise<AgentSessionRuntime> {
    const createRuntime: CreateAgentSessionRuntimeFactory = async ({ cwd, sessionManager, sessionStartEvent }) => {
      const services = await createAgentSessionServices({
        cwd,
        agentDir: this.agentDir,
        authStorage: this.authStorage,
        modelRegistry: this.modelRegistry
      });
      const selectedModel = this.findModel(options.model);
      const result = await createAgentSessionFromServices({
        services,
        sessionManager,
        sessionStartEvent,
        ...(selectedModel ? { model: selectedModel } : {}),
        thinkingLevel: this.normalizeThinkingLevel(options.thinkingLevel)
      });
      return {
        ...result,
        services,
        diagnostics: services.diagnostics
      };
    };

    const runtime = await createAgentSessionRuntime(createRuntime, {
      cwd: options.cwd || sessionManager.getCwd() || this.cwd,
      agentDir: this.agentDir,
      sessionManager
    });
    this.audit?.record("pi_runtime_created", undefined, {
      cwd: runtime.cwd,
      sessionId: runtime.session.sessionId,
      sessionFile: runtime.session.sessionFile
    });
    return runtime;
  }

  private async applySessionControls(session: AgentSession, model: string | undefined | null, thinkingLevel: string | null | undefined): Promise<void> {
    const selectedModel = this.findModel(model);
    if (selectedModel) {
      await session.setModel(selectedModel);
    }
    session.setThinkingLevel(this.normalizeThinkingLevel(thinkingLevel));
  }
}

export function piModelId(model: PiModel): string {
  return `${model.provider}/${model.id}`;
}

export function parsePiModelId(value: string): { provider: string; modelId: string } | undefined {
  const clean = value.trim();
  const separator = clean.indexOf("/");
  if (separator <= 0 || separator === clean.length - 1) {
    return undefined;
  }
  return {
    provider: clean.slice(0, separator),
    modelId: clean.slice(separator + 1)
  };
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
