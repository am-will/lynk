import { harnessDescriptor, type HarnessId } from "./AgentHarness.js";
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
  return harnessDescriptor(harnessId).readinessAction;
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
  return harnessDescriptor(harnessId).recoveryAction;
}
