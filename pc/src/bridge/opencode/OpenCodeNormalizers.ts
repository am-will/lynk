import { basename } from "node:path";
import { DEFAULT_REASONING_OPTIONS } from "../chat/ModelCatalog.js";
import type { OpenCodeModelRef } from "./OpenCodeServerClient.js";

const OPENCODE_SESSION_PREFIX = "opencode:";

export function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" ? value as Record<string, unknown> : undefined;
}

export function stringField(record: Record<string, unknown> | undefined, key: string): string | undefined {
  const value = record?.[key];
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

export function numberField(record: Record<string, unknown> | undefined, key: string): number | undefined {
  const value = record?.[key];
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

export function arrayField(record: Record<string, unknown> | undefined, key: string): unknown[] {
  const value = record?.[key];
  return Array.isArray(value) ? value : [];
}

function objectValues(record: Record<string, unknown> | undefined): unknown[] {
  return record ? Object.values(record) : [];
}

export function booleanField(record: Record<string, unknown> | undefined, key: string): boolean | undefined {
  const value = record?.[key];
  return typeof value === "boolean" ? value : undefined;
}

export function opencodeSessionIdFromKey(sessionKey: string | undefined | null): string | undefined {
  const value = sessionKey?.startsWith(OPENCODE_SESSION_PREFIX)
    ? sessionKey.slice(OPENCODE_SESSION_PREFIX.length).trim()
    : undefined;
  return value || undefined;
}

export function workspaceNameFromPath(path: string | null | undefined): string | null {
  if (!path) {
    return null;
  }
  return basename(path) || path;
}

export function secondsToMillis(value: number | null | undefined): number | null {
  if (value === null || value === undefined) {
    return null;
  }
  return value < 10_000_000_000 ? value * 1000 : value;
}

export function parseModelRef(model: string | undefined | null): OpenCodeModelRef | undefined {
  const clean = model?.trim();
  if (!clean) {
    return undefined;
  }
  const separator = clean.indexOf("/");
  if (separator <= 0 || separator === clean.length - 1) {
    return undefined;
  }
  return {
    providerID: clean.slice(0, separator),
    modelID: clean.slice(separator + 1)
  };
}

export function modelIdFromRef(model: OpenCodeModelRef): string {
  return `${model.providerID}/${model.modelID}`;
}

export function messagesFromOpenCode(payload: unknown): Array<Record<string, unknown>> {
  const records = Array.isArray(payload) ? payload : arrayField(asRecord(payload), "messages");
  return records
    .map((entry, index) => {
      const record = asRecord(entry);
      const info = asRecord(record?.info) ?? record;
      const role = stringField(info, "role") ?? "assistant";
      const parts = arrayField(record, "parts");
      const text = textFromParts(parts, role);
      if (!text.trim()) {
        return undefined;
      }
      return {
        id: stringField(info, "id") ?? `opencode_message_${index}`,
        role,
        text,
        timestamp: secondsToMillis(numberField(asRecord(info?.time), "created"))
      } as Record<string, unknown>;
    })
    .filter((message): message is Record<string, unknown> => message !== undefined);
}

export function payloadHasUserMessage(payload: unknown): boolean {
  const records = Array.isArray(payload) ? payload : arrayField(asRecord(payload), "messages");
  return records.some((entry) => stringField(asRecord(asRecord(entry)?.info) ?? asRecord(entry), "role") === "user");
}

export function latestAssistantText(payload: unknown): string {
  const messages = messagesFromOpenCode(payload);
  return [...messages].reverse().find((message) => message.role === "assistant")?.text as string | undefined ?? "";
}

export function usageFromMessages(payload: unknown): Record<string, unknown> | undefined {
  const records = Array.isArray(payload) ? payload : arrayField(asRecord(payload), "messages");
  for (const entry of [...records].reverse()) {
    const info = asRecord(asRecord(entry)?.info);
    if (stringField(info, "role") !== "assistant") {
      continue;
    }
    const tokens = asRecord(info?.tokens);
    if (!tokens) {
      continue;
    }
    const cache = asRecord(tokens.cache);
    const inputTokens = numberField(tokens, "input");
    const outputTokens = numberField(tokens, "output");
    const reasoningTokens = numberField(tokens, "reasoning");
    const totalTokens = [inputTokens, outputTokens, reasoningTokens, numberField(cache, "read"), numberField(cache, "write")]
      .filter((value): value is number => value !== undefined)
      .reduce((sum, value) => sum + value, 0);
    return {
      inputTokens,
      outputTokens,
      reasoningTokens,
      totalTokens: totalTokens || undefined,
      estimatedCostUsd: numberField(info, "cost")
    };
  }
  return undefined;
}

export function sessionTitle(session: Record<string, unknown>): string {
  return stringField(session, "title") ?? stringField(session, "id") ?? "OpenCode session";
}

export function sessionDirectory(session: Record<string, unknown> | undefined, fallback?: string): string | null {
  return stringField(session, "directory") ?? fallback ?? null;
}

export function isIdleStatus(payload: unknown, sessionId: string): boolean {
  const payloadRecord = asRecord(payload);
  if (payloadRecord && Object.keys(payloadRecord).length === 0) {
    return true;
  }
  const status = payloadRecord?.[sessionId];
  const record = asRecord(status);
  const type = typeof status === "string" ? status : stringField(record, "type");
  return type === "idle";
}

export function eventPayload(value: unknown): Record<string, unknown> | undefined {
  const record = asRecord(value);
  return asRecord(record?.payload) ?? asRecord(record?.data) ?? record;
}

export function eventProperties(value: unknown): Record<string, unknown> | undefined {
  return asRecord(eventPayload(value)?.properties);
}

export function eventType(value: unknown): string {
  return stringField(eventPayload(value), "type") ?? "";
}

export function sessionIdFromEvent(value: unknown): string | undefined {
  return stringField(eventProperties(value), "sessionID") ?? stringField(eventProperties(value), "sessionId");
}

export function toolContentText(content: unknown): string | undefined {
  const values = Array.isArray(content) ? content : [];
  const text = values
    .map((item) => {
      const record = asRecord(item);
      if (stringField(record, "type") === "text") {
        return stringField(record, "text") ?? "";
      }
      const uri = stringField(record, "uri");
      const name = stringField(record, "name");
      return uri ? [name, uri].filter(Boolean).join(": ") : "";
    })
    .filter(Boolean)
    .join("\n");
  return text || undefined;
}

export function errorText(error: unknown): string | undefined {
  const record = asRecord(error);
  return stringField(record, "message") ?? stringField(record, "error") ?? (typeof error === "string" ? error : undefined);
}

export function permissionPreview(permission: Record<string, unknown>): string | undefined {
  const pattern = permission.pattern;
  const metadata = asRecord(permission.metadata);
  const parts = [
    stringField(permission, "type"),
    Array.isArray(pattern) ? pattern.join(", ") : typeof pattern === "string" ? pattern : undefined,
    stringField(metadata, "command") ?? stringField(metadata, "file") ?? stringField(metadata, "path")
  ].filter(Boolean);
  return parts.join("\n") || undefined;
}

export function normalizeTools(payload: unknown): Array<Record<string, unknown>> {
  const records = Array.isArray(payload) ? payload : objectValues(asRecord(payload));
  return records
    .map((tool) => {
      const record = asRecord(tool);
      const name = stringField(record, "name") ?? stringField(record, "id");
      return name ? { name, description: stringField(record, "description") ?? `OpenCode ${name}` } as Record<string, unknown> : undefined;
    })
    .filter((tool): tool is Record<string, unknown> => tool !== undefined);
}

export function normalizeOpenCodeModels(payload: unknown): Array<Record<string, unknown>> {
  const record = asRecord(payload);
  const connected = new Set(arrayField(record, "connected").filter((value): value is string => typeof value === "string"));
  const providers = arrayField(record, "all").length > 0
    ? arrayField(record, "all")
    : arrayField(record, "providers");
  const models: Array<Record<string, unknown>> = [];
  for (const providerValue of providers) {
    const provider = asRecord(providerValue);
    if (!provider) {
      continue;
    }
    const providerID = stringField(provider, "id");
    if (!providerID) {
      continue;
    }
    const providerModels = asRecord(provider.models);
    for (const [key, value] of Object.entries(providerModels ?? {})) {
      const model = asRecord(value);
      const modelID = stringField(model, "id") ?? key;
      const id = `${providerID}/${modelID}`;
      models.push({
        id,
        key: id,
        name: stringField(model, "name") ?? modelID,
        provider: "opencode",
        contextWindow: numberField(asRecord(model?.limit), "context"),
        available: connected.size === 0 || connected.has(providerID),
        reasoningOptions: DEFAULT_REASONING_OPTIONS.map((option) => option.id),
        defaultReasoningEffort: "medium"
      });
    }
  }
  return models;
}

function textFromParts(parts: unknown[], role: string): string {
  return parts
    .map((part) => {
      const record = asRecord(part);
      const type = stringField(record, "type");
      if (type === "text") {
        return stringField(record, "text") ?? "";
      }
      if (role === "assistant" && type === "reasoning") {
        return "";
      }
      return "";
    })
    .filter(Boolean)
    .join("");
}
