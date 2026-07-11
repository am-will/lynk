import { Worker } from "node:worker_threads";
import type { ChatModelOption } from "../protocol/messages.js";
import { AdapterFailure } from "./harness/AdapterFailure.js";

export const HERMES_DISCOVERY_TTL_MS = 5 * 60_000;
export const HERMES_DISCOVERY_DEADLINE_MS = 30_000;
const MAX_DISCOVERED_MODELS = 1_000;

export type HermesDiscoveryResult =
  | { status: "fresh"; models: ChatModelOption[]; refreshedAt: number }
  | { status: "stale"; models: ChatModelOption[]; refreshedAt: number; error: AdapterFailure }
  | { status: "unavailable"; models: []; refreshedAt: null; error: AdapterFailure };

export type HermesDiscoveryRunner = (defaultModel: string) => Promise<ChatModelOption[]>;

export class HermesModelDiscoveryService {
  private cached?: { models: ChatModelOption[]; refreshedAt: number };
  private refresh?: Promise<HermesDiscoveryResult>;

  constructor(
    private readonly runner: HermesDiscoveryRunner = runHermesDiscoveryWorker,
    private readonly now: () => number = Date.now,
    private readonly ttlMs = HERMES_DISCOVERY_TTL_MS
  ) {}

  async get(defaultModel: string, options: { force?: boolean } = {}): Promise<HermesDiscoveryResult> {
    if (!options.force && this.cached && this.now() - this.cached.refreshedAt < this.ttlMs) {
      return { status: "fresh", ...this.cached };
    }
    if (this.refresh) {
      return await this.refresh;
    }
    this.refresh = this.runRefresh(defaultModel).finally(() => {
      this.refresh = undefined;
    });
    return await this.refresh;
  }

  invalidate(): void {
    this.cached = undefined;
  }

  private async runRefresh(defaultModel: string): Promise<HermesDiscoveryResult> {
    try {
      const models = (await this.runner(defaultModel)).slice(0, MAX_DISCOVERED_MODELS);
      const refreshedAt = this.now();
      this.cached = { models, refreshedAt };
      return { status: "fresh", models, refreshedAt };
    } catch (cause) {
      const error = cause instanceof AdapterFailure
        ? cause
        : new AdapterFailure("unavailable", "Hermes model discovery is unavailable", {
            cause,
            harnessId: "hermes",
            operation: "model-discovery"
          });
      if (this.cached) {
        return { status: "stale", ...this.cached, error };
      }
      return { status: "unavailable", models: [], refreshedAt: null, error };
    }
  }
}

export function runHermesDiscoveryWorker(defaultModel: string): Promise<ChatModelOption[]> {
  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL("./HermesModelDiscoveryWorker.js", import.meta.url), {
      workerData: { defaultModel }
    });
    let settled = false;
    const timer = setTimeout(() => {
      finish(() => reject(new AdapterFailure(
        "timeout",
        `Hermes model discovery timed out after ${HERMES_DISCOVERY_DEADLINE_MS}ms`,
        { harnessId: "hermes", operation: "model-discovery" }
      )));
      void worker.terminate();
    }, HERMES_DISCOVERY_DEADLINE_MS);
    timer.unref?.();

    const finish = (settle: () => void) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      worker.removeAllListeners();
      settle();
    };
    worker.once("message", (message: unknown) => {
      const record = message && typeof message === "object" ? message as Record<string, unknown> : undefined;
      if (record?.ok === true && Array.isArray(record.models)) {
        finish(() => resolve(record.models as ChatModelOption[]));
      } else {
        finish(() => reject(new AdapterFailure(
          "unavailable",
          typeof record?.error === "string" ? record.error : "Hermes discovery worker returned an invalid result",
          { harnessId: "hermes", operation: "model-discovery" }
        )));
      }
    });
    worker.once("error", (cause) => finish(() => reject(new AdapterFailure(
      "unavailable",
      `Hermes discovery worker failed: ${cause.message}`,
      { cause, harnessId: "hermes", operation: "model-discovery" }
    ))));
    worker.once("exit", (code) => {
      if (code !== 0) {
        finish(() => reject(new AdapterFailure(
          "unavailable",
          `Hermes discovery worker exited with code ${code}`,
          { harnessId: "hermes", operation: "model-discovery" }
        )));
      }
    });
  });
}
