import type { ChatCommandOption, ChatModelOption, ChatToolSummary } from "../../protocol/messages.js";

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" ? value as Record<string, unknown> : undefined;
}

function firstStringField(value: unknown, keys: string[]): string | undefined {
  if (typeof value === "string" && value.trim()) {
    return value.trim();
  }
  if (Array.isArray(value)) {
    for (const item of value) {
      const nested = firstStringField(item, keys);
      if (nested) {
        return nested;
      }
    }
    return undefined;
  }
  const record = asRecord(value);
  if (!record) {
    return undefined;
  }
  for (const key of keys) {
    const field = record[key];
    if (typeof field === "string" && field.trim()) {
      return field.trim();
    }
  }
  for (const field of Object.values(record)) {
    const nested = firstStringField(field, keys);
    if (nested) {
      return nested;
    }
  }
  return undefined;
}

function firstNumberField(value: unknown, keys: string[]): number | null {
  const record = asRecord(value);
  if (!record) {
    return null;
  }
  for (const key of keys) {
    const field = record[key];
    if (typeof field === "number" && Number.isFinite(field)) {
      return field;
    }
  }
  return null;
}

function directStringField(value: unknown, keys: string[]): string | undefined {
  const record = asRecord(value);
  if (!record) {
    return undefined;
  }
  for (const key of keys) {
    const field = record[key];
    if (typeof field === "string" && field.trim()) {
      return field.trim();
    }
  }
  return undefined;
}

export function normalizeHermesApiModels(payload: unknown, defaultModel: string): ChatModelOption[] {
  const rawModels = Array.isArray(asRecord(payload)?.data)
    ? asRecord(payload)?.data as unknown[]
    : Array.isArray(asRecord(payload)?.models)
      ? asRecord(payload)?.models as unknown[]
      : [];
  return rawModels
    .map((item) => {
      const record = asRecord(item);
      const id = firstStringField(record, ["id", "model", "name"]) ?? defaultModel;
      const provider = directStringField(record, ["provider", "providerId", "provider_id"]) ?? "hermes";
      const modelId = hermesApiSelectionId(provider, id);
      const name = directStringField(record, ["label", "displayName", "display_name", "name"]) ?? id;
      return {
        id: modelId,
        label: provider === "hermes" ? name : `${provider} / ${name}`,
        provider,
        modelId,
        contextWindow: firstNumberField(record, ["contextWindow", "context_window", "context_length", "maxContextTokens"]),
        available: true
      };
    });
}

export function mergeHermesModels(apiModels: ChatModelOption[], discoveredModels: ChatModelOption[], defaultModel: string): ChatModelOption[] {
  if (apiModels.length === 0) {
    return discoveredModels.length > 0 ? discoveredModels : [{
      id: defaultModel,
      label: defaultModel,
      provider: "hermes",
      modelId: defaultModel,
      available: true
    }];
  }

  const discoveredByKey = new Map(discoveredModels.flatMap((model) => modelKeys(model).map((key) => [key, model] as const)));
  const seen = new Set<string>();
  const suppressGenericHermesProxy = discoveredModels.length > 0;
  const merged = apiModels.flatMap((apiModel) => {
    if (suppressGenericHermesProxy && isGenericHermesProxyModel(apiModel, discoveredModels, defaultModel)) {
      return [];
    }
    const discovered = modelKeys(apiModel)
      .map((key) => discoveredByKey.get(key))
      .find(Boolean);
    for (const key of modelKeys(apiModel)) {
      seen.add(key);
    }
    if (!discovered) {
      return [apiModel];
    }
    for (const key of modelKeys(discovered)) {
      seen.add(key);
    }
    return [{
      ...discovered,
      ...apiModel,
      contextWindow: apiModel.contextWindow ?? discovered.contextWindow ?? null,
      reasoningOptions: apiModel.reasoningOptions ?? discovered.reasoningOptions ?? null,
      defaultReasoningEffort: apiModel.defaultReasoningEffort ?? discovered.defaultReasoningEffort ?? null
    }];
  });

  for (const discovered of discoveredModels) {
    if (!modelKeys(discovered).some((key) => seen.has(key))) {
      merged.push(discovered);
    }
  }
  return merged;
}

export function normalizeHermesSkills(payload: unknown): ChatCommandOption[] {
  const rawSkills = Array.isArray(asRecord(payload)?.data)
    ? asRecord(payload)?.data as unknown[]
    : Array.isArray(asRecord(payload)?.skills)
      ? asRecord(payload)?.skills as unknown[]
      : [];
  return rawSkills.flatMap((item) => {
    const record = asRecord(item);
    const name = directStringField(record, ["name", "id"]);
    if (!name) {
      return [];
    }
    return [{
      name,
      description: directStringField(record, ["description", "summary"]),
      category: directStringField(record, ["category"]),
      textAliases: [`/skill ${name}`],
      source: "skill",
      acceptsArgs: true,
      args: [{ name: "input", description: "Optional prompt or instructions for this skill", type: "string", required: false }]
    }];
  });
}

export function normalizeHermesToolsets(payload: unknown): ChatToolSummary[] {
  const rawToolsets = Array.isArray(asRecord(payload)?.data)
    ? asRecord(payload)?.data as unknown[]
    : Array.isArray(asRecord(payload)?.toolsets)
      ? asRecord(payload)?.toolsets as unknown[]
      : [];
  const tools: ChatToolSummary[] = [];
  const seen = new Set<string>();
  for (const item of rawToolsets) {
    const record = asRecord(item);
    if (record?.enabled === false) {
      continue;
    }
    const group = directStringField(record, ["label", "name"]);
    const source = directStringField(record, ["name"]);
    const rawTools = Array.isArray(record?.tools) ? record.tools as unknown[] : [];
    for (const rawTool of rawTools) {
      const rawToolRecord = asRecord(rawTool);
      const id = typeof rawTool === "string" ? rawTool : directStringField(rawToolRecord, ["id", "name"]);
      if (!id || seen.has(id)) {
        continue;
      }
      seen.add(id);
      tools.push({
        id,
        label: id,
        description: typeof rawTool === "string" ? null : directStringField(rawToolRecord, ["description"]),
        source,
        group
      });
    }
  }
  return tools;
}

export function cliPrompt(message: string, instructions: string | undefined): string {
  return instructions?.trim()
    ? `${instructions.trim()}\n\nCurrent user message:\n${message}`
    : message;
}

export function hermesCliModelArgs(model: string | undefined): string[] {
  const trimmed = model?.trim();
  if (!trimmed) {
    return [];
  }
  const separator = trimmed.indexOf(":");
  if (separator > 0) {
    const provider = trimmed.slice(0, separator).trim();
    const modelId = trimmed.slice(separator + 1).trim();
    return provider && modelId ? ["--provider", provider, "--model", modelId] : ["--model", trimmed];
  }
  return ["--model", trimmed];
}

export function hermesCliThinkingArgs(_thinking: string | undefined): string[] {
  return [];
}

function hermesApiSelectionId(provider: string, id: string): string {
  return provider && provider !== "hermes" && !id.includes(":") ? `${provider}:${id}` : id;
}

function modelKeys(model: ChatModelOption): string[] {
  return [...new Set([model.id, model.modelId ?? undefined].filter((value): value is string => Boolean(value)))];
}

function isGenericHermesProxyModel(apiModel: ChatModelOption, discoveredModels: ChatModelOption[], defaultModel: string): boolean {
  if (apiModel.provider !== "hermes") {
    return false;
  }
  const apiBareId = bareSelectionModel(apiModel.id);
  if (!apiBareId) {
    return false;
  }
  return apiModel.id === defaultModel || discoveredModels.some((model) => model.provider !== "hermes" && bareSelectionModel(model.modelId ?? model.id) === apiBareId);
}

function bareSelectionModel(value: string): string {
  const separator = value.indexOf(":");
  return separator > 0 ? value.slice(separator + 1) : value;
}
