import {
  buildSessionContext,
  SessionManager,
  type SessionEntry
} from "@earendil-works/pi-coding-agent";

export function messagesFromPiEntries(entries: SessionEntry[]): Array<Record<string, unknown>> {
  return entries
    .map((entry): Record<string, unknown> | undefined => {
      if (entry.type === "message") {
        const text = textFromPiMessage(entry.message);
        if (!text) {
          return undefined;
        }
        return {
          id: entry.id,
          role: roleFromPiMessage(entry.message),
          text,
          timestamp: Date.parse(entry.timestamp) || null
        };
      }
      if (entry.type === "compaction" || entry.type === "branch_summary") {
        return {
          id: entry.id,
          role: "system",
          text: entry.summary,
          timestamp: Date.parse(entry.timestamp) || null
        };
      }
      if (entry.type === "custom_message") {
        const text = textFromContent(entry.content);
        return text ? {
          id: entry.id,
          role: "system",
          text,
          timestamp: Date.parse(entry.timestamp) || null
        } : undefined;
      }
      return undefined;
    })
    .filter((message): message is Record<string, unknown> => message !== undefined);
}

export function readPiSessionModel(sessionPath: string): string | undefined {
  try {
    const manager = SessionManager.open(sessionPath);
    const context = buildSessionContext(manager.getEntries());
    return context.model ? `${context.model.provider}/${context.model.modelId}` : undefined;
  } catch {
    return undefined;
  }
}

export function textFromPiMessage(message: unknown): string {
  const record = asRecord(message);
  if (!record) {
    return "";
  }
  if (typeof record.content === "string") {
    return record.content;
  }
  if (Array.isArray(record.content)) {
    return textFromContent(record.content);
  }
  return stringField(record, "text") ?? stringField(record, "summary") ?? "";
}

export function latestAssistantText(messages: readonly unknown[]): string {
  for (const message of [...messages].reverse()) {
    if (roleFromPiMessage(message) === "assistant") {
      const text = textFromPiMessage(message);
      if (text) {
        return text;
      }
    }
  }
  return "";
}

export function latestUsage(messages: readonly unknown[]): Record<string, unknown> | undefined {
  for (const message of [...messages].reverse()) {
    const usage = asRecord(asRecord(message)?.usage);
    if (!usage) {
      continue;
    }
    const inputTokens = numberField(usage, "input");
    const outputTokens = numberField(usage, "output");
    const totalTokens = numberField(usage, "totalTokens")
      ?? [inputTokens, outputTokens, numberField(usage, "cacheRead"), numberField(usage, "cacheWrite")]
        .filter((value): value is number => value !== undefined)
        .reduce((sum, value) => sum + value, 0);
    return { inputTokens, outputTokens, totalTokens };
  }
  return undefined;
}

export function usageFromPiMessage(message: unknown): Record<string, unknown> | undefined {
  return latestUsage([message]);
}

export function firstMessageTitle(text: string | undefined): string | undefined {
  const clean = text?.trim();
  if (!clean) {
    return undefined;
  }
  return clean.length > 80 ? `${clean.slice(0, 77)}...` : clean;
}

function textFromContent(content: unknown): string {
  if (typeof content === "string") {
    return content;
  }
  if (!Array.isArray(content)) {
    return "";
  }
  return content
    .map((item) => {
      const record = asRecord(item);
      if (!record) {
        return "";
      }
      const type = stringField(record, "type");
      if (type === "text") {
        return stringField(record, "text") ?? "";
      }
      if (type === "toolCall") {
        return `Using ${stringField(record, "name") ?? "tool"}`;
      }
      return "";
    })
    .filter(Boolean)
    .join("\n");
}

function roleFromPiMessage(message: unknown): string {
  const role = stringField(asRecord(message), "role");
  return role === "toolResult" ? "tool" : role ?? "assistant";
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" ? value as Record<string, unknown> : undefined;
}

function stringField(record: Record<string, unknown> | undefined, key: string): string | undefined {
  const value = record?.[key];
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function numberField(record: Record<string, unknown> | undefined, key: string): number | undefined {
  const value = record?.[key];
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}
