import { existsSync, readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import type { ChatModelOption, ChatReasoningOption } from "../protocol/messages.js";

const FALLBACK_REASONING_OPTIONS: ChatReasoningOption[] = [
  { id: "low", label: "low" },
  { id: "medium", label: "medium" },
  { id: "high", label: "high" },
  { id: "xhigh", label: "xhigh" }
];

const FALLBACK_PROVIDER_MODELS: Record<string, string[]> = {
  anthropic: [
    "claude-opus-4-7",
    "claude-opus-4-6",
    "claude-sonnet-4-6",
    "claude-opus-4-5-20251101",
    "claude-sonnet-4-5-20250929",
    "claude-opus-4-20250514",
    "claude-sonnet-4-20250514",
    "claude-haiku-4-5-20251001"
  ],
  "openai-codex": [
    "gpt-5.5",
    "gpt-5.4",
    "gpt-5.4-mini",
    "gpt-5.3-codex",
    "gpt-5.3-codex-spark",
    "gpt-5.2",
    "gpt-5.2-codex",
    "gpt-5.1-codex-max",
    "gpt-5.1-codex-mini"
  ],
  minimax: [
    "MiniMax-M2.7",
    "MiniMax-M2.5",
    "MiniMax-M2.1",
    "MiniMax-M2"
  ]
};

const PROVIDER_LABELS: Record<string, string> = {
  anthropic: "Anthropic",
  copilot: "GitHub Copilot",
  gemini: "Google AI Studio",
  minimax: "MiniMax",
  "openai-codex": "OpenAI Codex",
  openrouter: "OpenRouter"
};

interface HermesConfigSummary {
  modelProvider?: string;
  modelDefault?: string;
  modelContextLength?: number;
  providers: Map<string, Array<{ id: string; contextWindow?: number }>>;
}

export function discoverHermesModels(defaultModel: string): ChatModelOption[] {
  const home = process.env.HERMES_HOME?.trim() || join(homedir(), ".hermes");
  const config = readHermesConfigSummary(join(home, "config.yaml"));
  const providerIds = new Set<string>();
  const models: ChatModelOption[] = [];

  addConfiguredModel(models, providerIds, config, defaultModel);
  for (const provider of config.providers.keys()) {
    providerIds.add(provider);
  }
  for (const provider of readCredentialPoolProviders(join(home, "auth.json"))) {
    providerIds.add(provider);
  }

  const catalog = loadHermesProviderCatalog(home, [...providerIds]);
  for (const provider of providerIds) {
    const configured = config.providers.get(provider) ?? [];
    const catalogModels = catalog.get(provider) ?? FALLBACK_PROVIDER_MODELS[provider] ?? [];
    const ids = uniqueStrings([
      ...configured.map((model) => model.id),
      ...catalogModels
    ]);
    for (const id of ids) {
      upsertHermesModel(models, {
        id: hermesModelSelectionId(provider, id),
        label: `${providerLabel(provider)} / ${id}`,
        provider,
        modelId: hermesModelSelectionId(provider, id),
        contextWindow: configured.find((model) => model.id === id)?.contextWindow ?? null,
        available: true,
        reasoningOptions: reasoningOptionsForHermesProvider(provider, id),
        defaultReasoningEffort: defaultReasoningForHermesProvider(provider, id)
      });
    }
  }

  if (models.length === 0) {
    upsertHermesModel(models, {
      id: defaultModel,
      label: defaultModel,
      provider: "hermes",
      modelId: defaultModel,
      available: true
    });
  }
  return models;
}

function addConfiguredModel(
  models: ChatModelOption[],
  providerIds: Set<string>,
  config: HermesConfigSummary,
  defaultModel: string
): void {
  const model = config.modelDefault?.trim() || defaultModel.trim();
  if (!model) {
    return;
  }
  const provider = config.modelProvider?.trim() || providerFromModel(model) || "hermes";
  providerIds.add(provider);
  upsertHermesModel(models, {
    id: hermesModelSelectionId(provider, model),
    label: `${providerLabel(provider)} / ${model}`,
    provider,
    modelId: hermesModelSelectionId(provider, model),
    contextWindow: config.modelContextLength,
    available: true,
    reasoningOptions: reasoningOptionsForHermesProvider(provider, model),
    defaultReasoningEffort: defaultReasoningForHermesProvider(provider, model)
  });
}

function readHermesConfigSummary(path: string): HermesConfigSummary {
  if (!existsSync(path)) {
    return { providers: new Map() };
  }
  const lines = readFileSync(path, "utf8").split(/\r?\n/);
  const summary: HermesConfigSummary = { providers: new Map() };
  let section: string | undefined;
  let provider: string | undefined;
  let inProviderModels = false;
  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) {
      continue;
    }
    const indent = leadingSpaces(line);
    if (indent === 0) {
      section = trimmed.replace(/:.*/, "");
      provider = undefined;
      inProviderModels = false;
      continue;
    }
    if (section === "model" && indent === 2) {
      const [key, value] = yamlPair(trimmed);
      if (key === "provider") {
        summary.modelProvider = value;
      } else if (key === "default" || key === "model") {
        summary.modelDefault = value;
      } else if (key === "context_length") {
        summary.modelContextLength = positiveInt(value);
      }
      continue;
    }
    if (section === "providers") {
      if (indent === 2 && trimmed.endsWith(":")) {
        provider = trimmed.slice(0, -1).trim();
        if (provider) {
          summary.providers.set(provider, summary.providers.get(provider) ?? []);
        }
        inProviderModels = false;
        continue;
      }
      if (indent === 4 && trimmed === "models:") {
        inProviderModels = true;
        continue;
      }
      if (provider && inProviderModels && indent === 6 && trimmed.endsWith(":")) {
        const modelId = unquote(trimmed.slice(0, -1).trim());
        const models = summary.providers.get(provider) ?? [];
        models.push({ id: modelId });
        summary.providers.set(provider, models);
        continue;
      }
      if (provider && inProviderModels && indent === 8 && trimmed.startsWith("context_length:")) {
        const models = summary.providers.get(provider);
        const last = models?.[models.length - 1];
        if (last) {
          last.contextWindow = positiveInt(trimmed.slice("context_length:".length).trim());
        }
      }
    }
  }
  return summary;
}

function readCredentialPoolProviders(path: string): string[] {
  if (!existsSync(path)) {
    return [];
  }
  try {
    const data = JSON.parse(readFileSync(path, "utf8")) as { credential_pool?: unknown; providers?: unknown };
    const keys = [
      ...objectKeys(data.credential_pool),
      ...objectKeys(data.providers)
    ];
    return uniqueStrings(keys.map((key) => normalizeCredentialProvider(key)).filter((key): key is string => Boolean(key)));
  } catch {
    return [];
  }
}

function loadHermesProviderCatalog(home: string, providers: string[]): Map<string, string[]> {
  if (providers.length === 0) {
    return new Map();
  }
  const hermesAgentDir = join(home, "hermes-agent");
  if (!existsSync(hermesAgentDir)) {
    return new Map();
  }
  const script = [
    "import json, os, sys",
    "env_path=os.path.join(os.environ.get('HERMES_HOME', ''), '.env')",
    "for line in (open(env_path, encoding='utf-8') if os.path.exists(env_path) else []):",
    "    s=line.strip()",
    "    if s and not s.startswith('#') and '=' in s:",
    "        k,v=s.split('=',1); os.environ.setdefault(k, v.strip().strip('\\\"').strip(\"'\"))",
    "from hermes_cli.models import curated_models_for_provider",
    "out={}",
    "for provider in sys.argv[1:]:",
    "    try:",
    "        out[provider]=[model for model,_ in curated_models_for_provider(provider)]",
    "    except Exception:",
    "        out[provider]=[]",
    "print(json.dumps(out))"
  ].join("\n");
  const result = spawnSync("python3", ["-c", script, ...providers], {
    cwd: hermesAgentDir,
    env: {
      ...process.env,
      HERMES_HOME: home,
      PYTHONPATH: hermesAgentDir
    },
    encoding: "utf8",
    timeout: 10_000
  });
  if (result.status !== 0 || !result.stdout.trim()) {
    return new Map();
  }
  try {
    const parsed = JSON.parse(result.stdout) as Record<string, unknown>;
    return new Map(Object.entries(parsed).map(([provider, value]) => [
      provider,
      Array.isArray(value) ? value.filter((item): item is string => typeof item === "string" && item.trim().length > 0) : []
    ]));
  } catch {
    return new Map();
  }
}

function upsertHermesModel(models: ChatModelOption[], model: ChatModelOption): void {
  const index = models.findIndex((existing) => existing.id === model.id);
  if (index === -1) {
    models.push(model);
  } else {
    models[index] = { ...models[index], ...model };
  }
}

function hermesModelSelectionId(provider: string, model: string): string {
  const trimmedProvider = provider.trim();
  const trimmedModel = model.trim();
  if (!trimmedProvider || trimmedProvider === "hermes") {
    return trimmedModel;
  }
  if (trimmedModel.includes(":") && trimmedModel.split(":", 1)[0] === trimmedProvider) {
    return trimmedModel;
  }
  return `${trimmedProvider}:${trimmedModel}`;
}

function reasoningOptionsForHermesProvider(provider: string, model: string): ChatReasoningOption[] | undefined {
  const normalized = provider.toLowerCase();
  const lowerModel = model.toLowerCase();
  if (normalized.includes("minimax") || normalized === "local" || lowerModel.includes("minimax")) {
    return undefined;
  }
  if (normalized === "anthropic" || lowerModel.includes("claude")) {
    return undefined;
  }
  return FALLBACK_REASONING_OPTIONS;
}

function defaultReasoningForHermesProvider(provider: string, model: string): string | undefined {
  return reasoningOptionsForHermesProvider(provider, model)?.some((option) => option.id === "medium") ? "medium" : undefined;
}

function providerLabel(provider: string): string {
  return PROVIDER_LABELS[provider] ?? titleCase(provider.replace(/^custom:/, ""));
}

function providerFromModel(model: string): string | undefined {
  const separator = model.indexOf(":");
  return separator > 0 ? model.slice(0, separator) : undefined;
}

function normalizeCredentialProvider(value: string): string | undefined {
  const key = value.trim();
  if (!key) {
    return undefined;
  }
  if (key.startsWith("custom:")) {
    return key.slice("custom:".length);
  }
  return key;
}

function uniqueStrings(values: string[]): string[] {
  const seen = new Set<string>();
  const result: string[] = [];
  for (const value of values) {
    const trimmed = value.trim();
    if (!trimmed || seen.has(trimmed)) {
      continue;
    }
    seen.add(trimmed);
    result.push(trimmed);
  }
  return result;
}

function objectKeys(value: unknown): string[] {
  return value && typeof value === "object" ? Object.keys(value as Record<string, unknown>) : [];
}

function yamlPair(line: string): [string, string] {
  const separator = line.indexOf(":");
  if (separator === -1) {
    return [line, ""];
  }
  return [line.slice(0, separator).trim(), unquote(line.slice(separator + 1).trim())];
}

function unquote(value: string): string {
  return value.replace(/^['"]|['"]$/g, "");
}

function positiveInt(value: string): number | undefined {
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined;
}

function leadingSpaces(value: string): number {
  return value.length - value.trimStart().length;
}

function titleCase(value: string): string {
  return value
    .split(/[-_]/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}
