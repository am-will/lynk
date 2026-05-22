import { randomUUID } from "node:crypto";
import type {
  ChatCommandOption,
  ChatHistoryMessage,
  ChatModelOption,
  ChatOutboundMessage,
  ChatReasoningDeltaMessage,
  ChatReasoningOption,
  ChatSessionSummary,
  ChatToolEventMessage,
  ChatToolSummary,
  ChatUsageSummary
} from "../protocol/messages.js";

const FALLBACK_REASONING_OPTIONS: ChatReasoningOption[] = [
  { id: "low", label: "low" },
  { id: "medium", label: "medium" },
  { id: "high", label: "high" },
  { id: "xhigh", label: "xhigh" }
];
const ALLOWED_REASONING_OPTION_IDS = new Set(["none", "minimal", ...FALLBACK_REASONING_OPTIONS.map((option) => option.id)]);

export function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" ? value as Record<string, unknown> : undefined;
}

export function stringField(record: Record<string, unknown> | undefined, key: string): string | undefined {
  const value = record?.[key];
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function numberField(record: Record<string, unknown> | undefined, key: string): number | null {
  const value = record?.[key];
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function booleanField(record: Record<string, unknown> | undefined, key: string): boolean | null {
  const value = record?.[key];
  return typeof value === "boolean" ? value : null;
}

function roleField(record: Record<string, unknown> | undefined): string | undefined {
  const role = stringField(record, "role") ?? stringField(record, "speaker") ?? stringField(record, "author");
  if (role) {
    return role.toLowerCase();
  }
  const type = stringField(record, "type")?.toLowerCase();
  return type === "user" || type === "assistant" || type === "system" ? type : undefined;
}

export function extractGatewayText(value: unknown): string {
  if (typeof value === "string") {
    return value;
  }
  if (Array.isArray(value)) {
    return value.map(extractGatewayText).filter(Boolean).join("");
  }
  const record = asRecord(value);
  if (!record) {
    return "";
  }

  for (const key of ["text", "delta", "deltaText", "message", "content", "output", "result"]) {
    const text = extractGatewayText(record[key]);
    if (text) {
      return text;
    }
  }
  return "";
}

export function normalizeHistoryMessage(value: unknown): ChatHistoryMessage | undefined {
  const record = asRecord(value);
  if (!record) {
    return undefined;
  }
  const role = stringField(record, "role") ?? "assistant";
  const rawText = extractGatewayHistoryText(record.content ?? record.text ?? record.message, role);
  const text = sanitizeHistoryText(role, rawText);
  if (!text.trim()) {
    return undefined;
  }
  const openClawMeta = asRecord(record.__openclaw);
  return {
    id: stringField(openClawMeta, "id") ?? stringField(record, "id") ?? null,
    role,
    text,
    timestamp: numberField(record, "timestamp")
  };
}

function extractGatewayHistoryText(value: unknown, role: string): string {
  const roleText = extractRoleTaggedGatewayText(value, role.toLowerCase());
  return roleText.trim() ? roleText : extractGatewayText(value);
}

function extractRoleTaggedGatewayText(value: unknown, role: string): string {
  if (Array.isArray(value)) {
    return value
      .map((item) => extractRoleTaggedGatewayText(item, role))
      .filter(Boolean)
      .join("\n\n");
  }

  const record = asRecord(value);
  if (!record) {
    return "";
  }

  const itemRole = roleField(record);
  if (itemRole) {
    if (itemRole !== role) {
      return "";
    }
    return extractGatewayText(record.content ?? record.text ?? record.message ?? record.output ?? record.result);
  }

  const explicitRoleValue = record[role];
  if (explicitRoleValue !== undefined) {
    return extractGatewayText(explicitRoleValue);
  }

  for (const key of ["content", "message", "messages", "items", "parts"]) {
    const nestedText = extractRoleTaggedGatewayText(record[key], role);
    if (nestedText.trim()) {
      return nestedText;
    }
  }

  return "";
}

function sanitizeHistoryText(role: string, text: string): string {
  if (role !== "user") {
    return text;
  }
  const queryStart = text.lastIndexOf("<user_query>");
  if (queryStart !== -1) {
    const queryText = text.slice(queryStart + "<user_query>".length);
    const queryEnd = queryText.indexOf("</user_query>");
    return (queryEnd === -1 ? queryText : queryText.slice(0, queryEnd)).trim();
  }

  for (const marker of ["User request:", "User message:", "User prompt:", "User input:"]) {
    const index = text.lastIndexOf(marker);
    if (index !== -1) {
      return stripDisplayTimestampPrefix(text.slice(index + marker.length));
    }
  }

  const genericUserIndex = Math.max(text.lastIndexOf("\nUser:"), text.lastIndexOf("\nuser:"));
  if (genericUserIndex !== -1) {
    const prefix = text.slice(0, genericUserIndex);
    if (/(^|\n)\s*(system|developer|context|instructions|system message):/i.test(prefix)) {
      return stripDisplayTimestampPrefix(text.slice(genericUserIndex + "\nUser:".length));
    }
  }

  const systemStatusWrapper = text.match(/^\s*System:\s*\[[^\]]+\][\s\S]*?\n{2,}\s*([\s\S]+)$/i);
  if (systemStatusWrapper?.[1]?.trim()) {
    return stripDisplayTimestampPrefix(systemStatusWrapper[1]);
  }
  return text;
}

function stripDisplayTimestampPrefix(text: string): string {
  return text.replace(/^\s*\[[^\]]+\]\s*/, "").trim();
}

export function normalizeModels(value: unknown): ChatModelOption[] {
  const root = asRecord(value);
  const models = Array.isArray(root?.models)
    ? root.models as unknown[]
    : Array.isArray(root?.data)
      ? root.data as unknown[]
      : [];
  const normalized: ChatModelOption[] = [];
  for (const item of models) {
    const record = asRecord(item);
    const key = stringField(record, "key") ?? stringField(record, "id") ?? stringField(record, "model") ?? stringField(record, "name");
    if (!key) {
      continue;
    }
    const name = stringField(record, "label") ?? stringField(record, "name") ?? stringField(record, "displayName") ?? key;
    const provider = key.includes("/") ? key.split("/")[0] : stringField(record, "provider");
    const reasoningOptions = normalizeModelReasoningOptions(record ?? {});
    const label = provider && name !== key && !name.toLowerCase().includes(provider.toLowerCase())
      ? `${name} (${provider})`
      : name;
    normalized.push({
      id: key,
      label,
      provider: provider ?? null,
      ...(stringField(record, "harnessId") ? { harnessId: stringField(record, "harnessId") } : {}),
      ...(stringField(record, "harnessLabel") ? { harnessLabel: stringField(record, "harnessLabel") } : {}),
      ...(stringField(record, "modelId") ? { modelId: stringField(record, "modelId") } : {}),
      contextWindow: numberField(record, "contextWindow"),
      available: booleanField(record, "available"),
      ...(reasoningOptions && reasoningOptions.length > 0 ? { reasoningOptions } : {}),
      ...(stringField(record, "defaultReasoningEffort") ? { defaultReasoningEffort: stringField(record, "defaultReasoningEffort") } : {})
    });
  }
  return normalized;
}

function normalizeModelReasoningOptions(record: Record<string, unknown> | undefined): ChatReasoningOption[] | undefined {
  if (!record) {
    return undefined;
  }
  const raw = record?.reasoningOptions ?? record?.thinkingLevels ?? record?.supportedReasoningEfforts;
  if (!Array.isArray(raw)) {
    return undefined;
  }
  return raw.map((item) => {
    if (typeof item === "string" && item.trim()) {
      return { id: item.trim(), label: item.trim() };
    }
    const itemRecord = asRecord(item);
    const id = stringField(itemRecord, "id")
      ?? stringField(itemRecord, "reasoningEffort")
      ?? stringField(itemRecord, "value");
    if (!id) {
      return undefined;
    }
    return { id, label: stringField(itemRecord, "label") ?? id };
  }).filter((option): option is ChatReasoningOption => {
    if (!option) {
      return false;
    }
    return ALLOWED_REASONING_OPTION_IDS.has(option.id);
  });
}

export function normalizeReasoningOptions(value: unknown): ChatReasoningOption[] {
  const defaults = asRecord(asRecord(value)?.defaults);
  const raw = defaults?.thinkingLevels ?? defaults?.thinkingOptions;
  const levels = Array.isArray(raw) ? raw : [];
  const normalized = levels.map((item) => {
    if (typeof item === "string" && item.trim()) {
      return { id: item.trim(), label: item.trim() };
    }
    const record = asRecord(item);
    const id = stringField(record, "id");
    if (!id) {
      return undefined;
    }
    return { id, label: stringField(record, "label") ?? id };
  }).filter((option): option is ChatReasoningOption => {
    if (!option) {
      return false;
    }
    return ALLOWED_REASONING_OPTION_IDS.has(option.id);
  });
  return normalized.length > 0 ? normalized : FALLBACK_REASONING_OPTIONS;
}

export function normalizeSessions(value: unknown): ChatSessionSummary[] {
  const sessions = Array.isArray(asRecord(value)?.sessions) ? asRecord(value)?.sessions as unknown[] : [];
  const normalized: ChatSessionSummary[] = [];
  for (const item of sessions) {
    const record = asRecord(item);
    const key = stringField(record, "key");
    if (!key) {
      continue;
    }
    normalized.push({
      key,
      sessionId: stringField(record, "sessionId") ?? null,
      label: stringField(record, "label") ?? null,
      displayName: stringField(record, "displayName") ?? stringField(record, "label") ?? null,
      ...(stringField(record, "harnessId") ? { harnessId: stringField(record, "harnessId") } : {}),
      ...(stringField(record, "harnessLabel") ? { harnessLabel: stringField(record, "harnessLabel") } : {}),
      updatedAt: numberField(record, "updatedAt"),
      model: stringField(record, "model") ?? null,
      modelProvider: stringField(record, "modelProvider") ?? null,
      contextTokens: numberField(record, "contextTokens"),
      inputTokens: numberField(record, "inputTokens"),
      outputTokens: numberField(record, "outputTokens"),
      totalTokens: numberField(record, "totalTokens"),
      estimatedCostUsd: numberField(record, "estimatedCostUsd"),
      fastMode: booleanField(record, "fastMode"),
      hasActiveRun: booleanField(record, "hasActiveRun"),
      thinkingLevel: stringField(record, "thinkingLevel") ?? null,
      reasoningLevel: stringField(record, "reasoningLevel") ?? null,
      verboseLevel: stringField(record, "verboseLevel") ?? null
    });
  }
  return normalized;
}

export function usageFromSession(session: ChatSessionSummary | undefined): ChatUsageSummary {
  const contextTokens = sessionContextTokens(session);
  return {
    inputTokens: session?.inputTokens ?? null,
    outputTokens: session?.outputTokens ?? null,
    totalTokens: session?.totalTokens ?? null,
    contextTokens,
    estimatedCostUsd: session?.estimatedCostUsd ?? null
  };
}

export function enrichSessionsWithModelContext(
  sessions: ChatSessionSummary[],
  models: Iterable<ChatModelOption>
): ChatSessionSummary[] {
  return sessions.map((session) => {
    const contextTokens = sessionContextTokens(session, models);
    if (contextTokens === (session.contextTokens ?? null)) {
      return session;
    }
    return {
      ...session,
      contextTokens
    };
  });
}

function sessionContextTokens(
  session: ChatSessionSummary | undefined,
  models: Iterable<ChatModelOption> = []
): number | null {
  const explicit = positiveNumber(session?.contextTokens);
  if (explicit !== null) {
    return explicit;
  }
  return modelContextWindowForSession(session, models);
}

function modelContextWindowForSession(
  session: ChatSessionSummary | undefined,
  models: Iterable<ChatModelOption>
): number | null {
  if (!session?.model) {
    return null;
  }
  const sessionModel = session.model.trim();
  const sessionSelection = splitHarnessModel(sessionModel);
  const sessionHarness = session.harnessId ?? sessionSelection.harnessId;
  for (const model of models) {
    const contextWindow = positiveNumber(model.contextWindow);
    if (contextWindow === null) {
      continue;
    }
    if (modelMatchesSession(model, sessionModel, sessionSelection.modelId, sessionHarness)) {
      return contextWindow;
    }
  }
  return null;
}

function modelMatchesSession(
  model: ChatModelOption,
  sessionModel: string,
  sessionModelId: string,
  sessionHarness: string | undefined
): boolean {
  const modelIds = uniqueStrings([model.id, model.modelId].filter((value): value is string => Boolean(value?.trim())));
  if (modelIds.includes(sessionModel)) {
    return true;
  }
  const modelSelection = splitHarnessModel(model.id);
  const modelHarness = model.harnessId ?? modelSelection.harnessId;
  if (sessionHarness && modelHarness && sessionHarness !== modelHarness) {
    return false;
  }
  return modelIds.some((id) => {
    const modelId = splitHarnessModel(id).modelId;
    return modelId === sessionModelId || modelId.endsWith(`:${sessionModelId}`);
  });
}

function splitHarnessModel(value: string): { harnessId?: string; modelId: string } {
  const trimmed = value.trim();
  const separatorIndex = trimmed.indexOf(":");
  if (separatorIndex <= 0) {
    return { modelId: trimmed };
  }
  const prefix = trimmed.slice(0, separatorIndex).toLowerCase();
  if (prefix === "openclaw" || prefix === "hermes" || prefix === "codex") {
    return { harnessId: prefix, modelId: trimmed.slice(separatorIndex + 1).trim() };
  }
  return { modelId: trimmed };
}

function uniqueStrings(values: string[]): string[] {
  return [...new Set(values.map((value) => value.trim()).filter(Boolean))];
}

function positiveNumber(value: number | null | undefined): number | null {
  return typeof value === "number" && Number.isFinite(value) && value > 0 ? value : null;
}

export function normalizeCommands(value: unknown): ChatCommandOption[] {
  const commands = Array.isArray(asRecord(value)?.commands) ? asRecord(value)?.commands as unknown[] : [];
  const normalized: ChatCommandOption[] = [];
  for (const item of commands) {
    const record = asRecord(item);
    const name = stringField(record, "name");
    if (!name) {
      continue;
    }
    const aliases = Array.isArray(record?.textAliases)
      ? record.textAliases.filter((alias): alias is string => typeof alias === "string")
      : [];
    normalized.push({
      name,
      description: stringField(record, "description") ?? null,
      category: stringField(record, "category") ?? null,
      textAliases: aliases,
      source: stringField(record, "source") ?? null,
      acceptsArgs: booleanField(record, "acceptsArgs") ?? false,
      args: commandArgs(record)
    });
  }
  return normalized;
}

function commandArgs(record: Record<string, unknown> | undefined): ChatCommandOption["args"] {
  const args = Array.isArray(record?.args) ? record.args as unknown[] : [];
  const normalized: NonNullable<ChatCommandOption["args"]> = [];
  for (const item of args) {
    const arg = asRecord(item);
    const name = stringField(arg, "name");
    if (!name) {
      continue;
    }
    normalized.push({
      name,
      description: stringField(arg, "description") ?? null,
      type: stringField(arg, "type") ?? null,
      required: booleanField(arg, "required")
    });
  }
  return normalized;
}

export function normalizeTools(value: unknown): ChatToolSummary[] {
  const root = asRecord(value);
  const directTools = Array.isArray(root?.tools) ? root.tools as unknown[] : [];
  if (directTools.length > 0) {
    const normalized: ChatToolSummary[] = [];
    for (const item of directTools) {
      const record = asRecord(item);
      const id = stringField(record, "id");
      if (!id) {
        continue;
      }
      normalized.push({
        id,
        label: stringField(record, "label") ?? id,
        description: stringField(record, "description") ?? stringField(record, "rawDescription") ?? null,
        source: stringField(record, "source") ?? null,
        group: stringField(record, "group") ?? null
      });
    }
    return normalized;
  }
  const groups = Array.isArray(root?.groups) ? root.groups as unknown[] : [];
  const normalized: ChatToolSummary[] = [];
  for (const groupValue of groups) {
    const group = asRecord(groupValue);
    const groupLabel = stringField(group, "label") ?? stringField(group, "id") ?? null;
    const tools = Array.isArray(group?.tools) ? group.tools as unknown[] : [];
    for (const item of tools) {
      const record = asRecord(item);
      const id = stringField(record, "id");
      if (!id) {
        continue;
      }
      normalized.push({
        id,
        label: stringField(record, "label") ?? id,
        description: stringField(record, "description") ?? stringField(record, "rawDescription") ?? null,
        source: stringField(record, "source") ?? null,
        group: groupLabel
      });
    }
  }
  return normalized;
}

function statusFromRaw(raw: Record<string, unknown> | undefined): ChatToolEventMessage["status"] {
  const status = stringField(raw, "status") ?? stringField(raw, "state") ?? stringField(raw, "phase");
  if (status === "completed" || status === "done" || status === "success") {
    return "completed";
  }
  if (status === "failed" || status === "error") {
    return "failed";
  }
  if (status === "blocked" || status === "denied") {
    return "blocked";
  }
  return status === "info" ? "info" : "running";
}

export function normalizeGatewayToolEvent(
  deviceId: string,
  sessionKey: string,
  payload: unknown
): ChatToolEventMessage | undefined {
  const record = asRecord(payload);
  if (!record) {
    return undefined;
  }
  const data = asRecord(record.data) ?? record;
  const stream = stringField(record, "stream") ?? "";
  const toolName = stringField(data, "toolName")
    ?? stringField(data, "tool")
    ?? stringField(data, "name")
    ?? stringField(data, "command")
    ?? (stream.includes("tool") || stream.includes("command") ? stream : undefined);
  if (!toolName && !stream.includes("tool") && !stream.includes("command")) {
    return undefined;
  }
  const status = statusFromRaw(data);
  const eventId = stringField(data, "id") ?? stringField(record, "id") ?? `${record.runId ?? "run"}:${record.seq ?? randomUUID()}`;
  const summary = stringField(data, "summary") ?? stringField(data, "message") ?? stringField(data, "text") ?? null;
  return {
    type: "chat.tool_event",
    deviceId,
    sessionKey,
    runId: stringField(record, "runId") ?? null,
    eventId,
    toolName: toolName ?? "tool",
    title: summary ?? toolName ?? "Tool activity",
    status,
    summary,
    args: data.args ?? data.arguments ?? null,
    output: data.output ?? data.result ?? null,
    error: stringField(data, "error") ?? null,
    raw: payload
  };
}

export function normalizeGatewayReasoningEvent(
  deviceId: string,
  fallbackSessionKey: string,
  payload: unknown,
  eventName?: string
): ChatReasoningDeltaMessage | undefined {
  const record = asRecord(payload);
  if (!record) {
    return undefined;
  }
  const data = asRecord(record.data) ?? asRecord(record.payload) ?? record;
  const normalizedEvent = (eventName ?? stringField(record, "event") ?? stringField(data, "event") ?? "").toLowerCase();
  const normalizedType = (stringField(record, "type") ?? stringField(data, "type") ?? "").toLowerCase();
  const normalizedState = (stringField(record, "state") ?? stringField(data, "state") ?? "").toLowerCase();
  const normalizedStream = (stringField(record, "stream") ?? stringField(data, "stream") ?? "").toLowerCase();
  const sourceText = [normalizedEvent, normalizedType, normalizedState, normalizedStream].join(" ");
  const isReasoningEvent = sourceText.includes("thinking")
    || sourceText.includes("reasoning")
    || normalizedState === "plan";
  const isDeltaLike = sourceText.includes("delta")
    || sourceText.includes("update")
    || sourceText.includes("stream")
    || normalizedState === "thinking"
    || normalizedState === "reasoning"
    || normalizedState === "plan";
  if (!isReasoningEvent || !isDeltaLike) {
    return undefined;
  }

  const delta = [
    data.delta,
    data.deltaText,
    data.text,
    data.message,
    data.content,
    record.delta,
    record.deltaText,
    record.message,
    record.content
  ].map(extractGatewayText).find((text) => text.trim());
  if (!delta) {
    return undefined;
  }

  return {
    type: "chat.reasoning_delta",
    deviceId,
    sessionKey: stringField(record, "sessionKey") ?? stringField(data, "sessionKey") ?? fallbackSessionKey,
    runId: stringField(record, "runId") ?? stringField(data, "runId") ?? `${record.seq ?? randomUUID()}`,
    delta,
    replace: Boolean(record.replace ?? data.replace)
  };
}

export function chatMessagesFromHistory(value: unknown): ChatHistoryMessage[] {
  const messages = Array.isArray(asRecord(value)?.messages) ? asRecord(value)?.messages as unknown[] : [];
  return messages.map(normalizeHistoryMessage).filter((message): message is ChatHistoryMessage => Boolean(message));
}

export function requestKeyFromSessionKey(sessionKey: string, agentId: string): string {
  const prefix = `agent:${agentId}:explicit:`;
  return sessionKey.startsWith(prefix) ? sessionKey.slice(prefix.length) : sessionKey;
}

export function mapGatewayChatEvent(
  deviceId: string,
  payload: unknown
): ChatOutboundMessage | undefined {
  const record = asRecord(payload);
  const sessionKey = stringField(record, "sessionKey");
  const runId = stringField(record, "runId");
  if (!record || !sessionKey || !runId) {
    return undefined;
  }
  const eventType = stringField(record, "type");
  if (eventType === "assistant.delta") {
    const data = asRecord(record.data);
    return {
      type: "chat.delta",
      deviceId,
      sessionKey,
      runId,
      delta: extractGatewayText(data?.delta ?? data?.text ?? record.data),
      replace: false
    };
  }
  if (eventType === "assistant.message") {
    const data = asRecord(record.data);
    const text = extractGatewayText(data?.message ?? data?.content ?? data?.text ?? record.data);
    if (!text.trim()) {
      return undefined;
    }
    return {
      type: "chat.final",
      deviceId,
      sessionKey,
      runId,
      text,
      usage: data?.usage
    };
  }
  if (eventType === "run.failed" || eventType === "run.cancelled" || eventType === "run.timed_out") {
    const data = asRecord(record.data);
    return {
      type: "chat.error",
      deviceId,
      sessionKey,
      runId,
      message: stringField(data, "message") ?? stringField(data, "error") ?? `OpenClaw ${eventType.replace("run.", "")}`
    };
  }
  const state = stringField(record, "state");
  if (state === "delta") {
    return {
      type: "chat.delta",
      deviceId,
      sessionKey,
      runId,
      delta: extractGatewayText(record.message ?? record.deltaText ?? record.delta),
      replace: Boolean(record.replace)
    };
  }
  if (state === "final" || state === "aborted") {
    return {
      type: "chat.final",
      deviceId,
      sessionKey,
      runId,
      text: extractGatewayText(record.message),
      usage: record.usage
    };
  }
  if (state === "error") {
    return {
      type: "chat.error",
      deviceId,
      sessionKey,
      runId,
      message: stringField(record, "errorMessage") ?? stringField(record, "error") ?? "OpenClaw chat run failed"
    };
  }
  return undefined;
}
