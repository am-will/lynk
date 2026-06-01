import type { AgentTaskKind } from "../dispatcher/AgentClient.js";
import { PHONE_TURN_HINT } from "../dispatcher/promptPolicy.js";

const ALLOWED_THINKING_LEVELS = new Set(["none", "minimal", "low", "medium", "high", "xhigh"]);

export function isSameModelSelection(requestedModel: string, currentModel?: string | null): boolean {
  if (!currentModel) {
    return false;
  }
  return requestedModel === currentModel || currentModel.endsWith(`/${requestedModel}`);
}

export function messageForGateway(text: string, taskKind: AgentTaskKind): string {
  if (taskKind !== "phone") {
    return text;
  }
  return `${PHONE_TURN_HINT}\n\nUser request:\n${text}`;
}

export function isExplicitPhoneTask(text: string): boolean {
  const normalized = text.toLowerCase();
  if (/\b(mac|desktop|pc|laptop|browser|terminal|repo|codebase)\b/.test(normalized)) {
    return false;
  }
  const phoneTarget = /\b(android|phone|device|screen|keyboard|notification|notifications|settings app|facebook app|instagram app|messages app|sms)\b/.test(normalized);
  const phoneAction = /\b(open|launch|go to|tap|press|click|swipe|scroll|type|enter|read|check|show|look at|inspect|unlock|dismiss|enable|disable|send|text|screenshot|capture|summarize)\b/.test(normalized)
    || /\bturn\s+(?:on|off)\b/.test(normalized)
    || /\bwhat(?:'s| is)\s+on\b/.test(normalized);
  const directPhoneGesture = /\b(tap|press|click|swipe|scroll|type|enter)\b/.test(normalized)
    && /\b(button|field|screen|app|keyboard|notification|settings)\b/.test(normalized);
  return (phoneTarget && phoneAction) || directPhoneGesture;
}

export function firstMessageDisplayName(text: string): string | undefined {
  const normalized = text.replace(/\s+/g, " ").trim();
  if (!normalized) {
    return undefined;
  }
  return normalized.length <= 64 ? normalized : `${normalized.slice(0, 61).trimEnd()}...`;
}

export function realtimeSessionLabel(text: string): string {
  return firstMessageDisplayName(text) ?? "Realtime voice";
}

export function numberedLabel(baseLabel: string, attempt: number): string {
  const suffix = attempt <= 0 ? "" : ` ${attempt + 1}`;
  const maxBaseLength = 64 - suffix.length;
  const base = baseLabel.length <= maxBaseLength
    ? baseLabel
    : baseLabel.slice(0, maxBaseLength).trimEnd();
  return `${base}${suffix}`;
}

export function isDuplicateSessionLabelError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error);
  return /label|name|display/i.test(message) && /already|duplicate|exists|unique|used/i.test(message);
}

export function normalizeThinkingLevel(incoming?: string | null, current?: string | null): string {
  const normalizedIncoming = incoming?.trim().toLowerCase();
  if (normalizedIncoming && ALLOWED_THINKING_LEVELS.has(normalizedIncoming)) {
    return normalizedIncoming;
  }
  const normalizedCurrent = current?.trim().toLowerCase();
  if (normalizedCurrent && ALLOWED_THINKING_LEVELS.has(normalizedCurrent)) {
    return normalizedCurrent;
  }
  return "medium";
}

export function reasoningStreamEnabled(level: string | null | undefined): boolean | null {
  if (!level) {
    return null;
  }
  const normalized = level.toLowerCase();
  if (normalized === "stream") {
    return true;
  }
  if (normalized === "off" || normalized === "false") {
    return false;
  }
  return null;
}
