import type { PhoneCommand } from "./messages.js";

export interface PhoneCommandRequest {
  deviceId?: string;
  requestOwner: string;
  command: PhoneCommand;
  args?: Record<string, unknown>;
  approvalCapability?: string;
  timeoutMs?: number;
}

export interface PhoneCommandResult {
  id: string;
  deviceId: string;
  ok: boolean;
  observation?: unknown;
  screenshotBase64?: string | null;
  screenshot?: {
    widthPx: number;
    heightPx: number;
  } | null;
  error?: string | null;
  approvalCapability?: string | null;
  approvalExpiresAtMs?: number | null;
  approvedAction?: string | null;
}

export const DEFAULT_TIMEOUT_MS = 30_000;

export function newCommandId(): string {
  return `cmd_${Date.now()}_${Math.random().toString(16).slice(2)}`;
}
