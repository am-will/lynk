import type { HarnessId } from "./AgentHarness.js";
import { ChatClientError } from "./chat/ChatErrors.js";

export interface HarnessReadinessStatus {
  ok: boolean;
  configured: boolean;
  label: string;
  modelCount: number;
  state: "ready" | "missing_config" | "no_models";
  message: string;
  action?: string;
}

export interface BackendReadinessStatus {
  harnesses: Record<HarnessId, HarnessReadinessStatus>;
}

export function healthForHarness(payload: unknown, harnessId: HarnessId): Record<string, unknown> | undefined {
  const record = asRecord(payload);
  const harnesses = asRecord(record?.harnesses);
  if (harnesses) {
    return asRecord(harnesses[harnessId]);
  }
  return harnessId === "openclaw" ? record : undefined;
}

export function harnessUnavailableError(harnessId: HarnessId, label: string, cause: unknown): ChatClientError {
  const detail = errorDetail(cause);
  const action = harnessRecoveryAction(harnessId);
  return new ChatClientError(`${label} backend is not reachable. ${action}${detail ? ` Details: ${detail}` : ""}`, {
    code: `${harnessId}.unreachable`
  });
}

export function readinessAction(harnessId: HarnessId): string {
  switch (harnessId) {
    case "openclaw":
      return "Install and start OpenClaw Gateway, then run host integration refresh.";
    case "hermes":
      return "Set HERMES_API_KEY or configure Hermes in the host bridge config, then run host integration refresh.";
    case "codex":
      return "Install Codex CLI with app-server support, then run host integration refresh.";
    case "opencode":
      return "Install OpenCode CLI or configure OPENCODE_SERVER_URL, then run host integration refresh.";
    default: {
      const exhaustive: never = harnessId;
      return exhaustive;
    }
  }
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" ? value as Record<string, unknown> : undefined;
}

function errorDetail(error: unknown): string | undefined {
  if (typeof error === "string") {
    return error.trim() || undefined;
  }
  if (error instanceof Error) {
    return error.message.trim() || undefined;
  }
  const record = asRecord(error);
  const message = typeof record?.message === "string" ? record.message : undefined;
  const errorMessage = typeof record?.error === "string" ? record.error : undefined;
  return message?.trim() || errorMessage?.trim() || undefined;
}

function harnessRecoveryAction(harnessId: HarnessId): string {
  switch (harnessId) {
    case "openclaw":
      return "Start OpenClaw Gateway with `openclaw gateway start` or choose a healthy harness in the model picker.";
    case "hermes":
      return "Verify `HERMES_API_BASE_URL` points at a Lynk-compatible Hermes runs API and that `HERMES_API_KEY` is set.";
    case "codex":
      return "Verify the Codex app-server command and workspace are configured, then try again.";
    case "opencode":
      return "Verify the OpenCode server URL or serve command and workspace are configured, then try again.";
    default: {
      const exhaustive: never = harnessId;
      return exhaustive;
    }
  }
}
