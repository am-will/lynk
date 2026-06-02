import { existsSync, readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import type { ChatModelOption, ChatReasoningOption } from "../protocol/messages.js";
import {
  parsePositiveInt,
  readHermesConfigSummary,
  unquoteYamlScalar
} from "../hermes/HermesConfigReader.js";
import {
  defaultReasoningForProvider,
  hermesCodexOauthContextWindow,
  reasoningOptionsForProvider
} from "./chat/ModelCatalog.js";

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

const CODEX_OAUTH_URL_FRAGMENT = "chatgpt.com/backend-api/codex";

interface HermesContextLengthCacheEntry {
  model: string;
  endpoint: string;
  contextWindow: number;
}

export function discoverHermesModels(defaultModel: string): ChatModelOption[] {
  const home = process.env.HERMES_HOME?.trim() || join(homedir(), ".hermes");
  const config = readHermesConfigSummary();
  const contextCache = readHermesContextLengthCache(join(home, "context_length_cache.yaml"));
  const providerIds = new Set<string>();
  const models: ChatModelOption[] = [];

  addConfiguredModel(models, providerIds, config, defaultModel, contextCache);
  for (const provider of config.providers.keys()) {
    providerIds.add(provider);
  }
  for (const provider of readCredentialPoolProviders(join(home, "auth.json"))) {
    providerIds.add(provider);
  }

  const catalog = loadHermesProviderCatalog(home, [...providerIds]);
  for (const provider of providerIds) {
    const configured = config.providers.get(provider)?.models ?? [];
    const catalogModels = catalog.get(provider) ?? FALLBACK_PROVIDER_MODELS[provider] ?? [];
    const ids = uniqueStrings([
      ...configured.map((model) => model.id),
      ...catalogModels
    ]);
    for (const id of ids) {
      const configuredModel = configured.find((model) => model.id === id);
      upsertHermesModel(models, {
        id: hermesModelSelectionId(provider, id),
        label: `${providerLabel(provider)} / ${id}`,
        provider,
        modelId: hermesModelSelectionId(provider, id),
        contextWindow: configuredModel?.contextWindow ?? contextWindowForHermesModel(provider, id, contextCache) ?? null,
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
  config: ReturnType<typeof readHermesConfigSummary>,
  defaultModel: string,
  contextCache: HermesContextLengthCacheEntry[]
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
    contextWindow: config.modelContextLength ?? contextWindowForHermesModel(provider, model, contextCache),
    available: true,
    reasoningOptions: reasoningOptionsForHermesProvider(provider, model),
    defaultReasoningEffort: defaultReasoningForHermesProvider(provider, model)
  });
}

function readHermesContextLengthCache(path: string): HermesContextLengthCacheEntry[] {
  if (!existsSync(path)) {
    return [];
  }
  try {
    return readFileSync(path, "utf8")
      .split(/\r?\n/)
      .map((line) => {
        const match = line.match(/^\s+(.+):\s*([0-9][0-9_,]*)\s*$/);
        if (!match) {
          return undefined;
        }
        const separator = match[1].indexOf("@");
        if (separator <= 0) {
          return undefined;
        }
        const contextWindow = parsePositiveInt(match[2]);
        if (!contextWindow) {
          return undefined;
        }
        return {
          model: unquoteYamlScalar(match[1].slice(0, separator).trim()),
          endpoint: unquoteYamlScalar(match[1].slice(separator + 1).trim()).replace(/\/+$/, ""),
          contextWindow
        };
      })
      .filter((entry): entry is HermesContextLengthCacheEntry => Boolean(entry));
  } catch {
    return [];
  }
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

function contextWindowForHermesModel(
  provider: string,
  model: string,
  contextCache: HermesContextLengthCacheEntry[]
): number | undefined {
  return cachedContextWindowForHermesModel(provider, model, contextCache)
    ?? providerFallbackContextWindow(provider, model);
}

function cachedContextWindowForHermesModel(
  provider: string,
  model: string,
  contextCache: HermesContextLengthCacheEntry[]
): number | undefined {
  const queryModel = bareHermesModel(provider, model).toLowerCase();
  for (const entry of contextCache) {
    const cachedModel = bareHermesModel(provider, entry.model).toLowerCase();
    if (cachedModel !== queryModel) {
      continue;
    }
    if (!contextCacheEndpointMatchesProvider(provider, entry.endpoint)) {
      continue;
    }
    return entry.contextWindow;
  }
  return undefined;
}

function contextCacheEndpointMatchesProvider(provider: string, endpoint: string): boolean {
  const normalizedProvider = provider.toLowerCase();
  const normalizedEndpoint = endpoint.toLowerCase();
  if (normalizedProvider === "openai-codex") {
    return normalizedEndpoint.includes(CODEX_OAUTH_URL_FRAGMENT);
  }
  return !normalizedEndpoint.includes(CODEX_OAUTH_URL_FRAGMENT);
}

function providerFallbackContextWindow(provider: string, model: string): number | undefined {
  if (provider.toLowerCase() !== "openai-codex") {
    return undefined;
  }
  return hermesCodexOauthContextWindow(bareHermesModel(provider, model));
}

function bareHermesModel(provider: string, model: string): string {
  const trimmed = model.trim();
  const prefix = `${provider.trim()}:`.toLowerCase();
  return trimmed.toLowerCase().startsWith(prefix) ? trimmed.slice(prefix.length).trim() : trimmed;
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
  return reasoningOptionsForProvider(provider, model);
}

function defaultReasoningForHermesProvider(provider: string, model: string): string | undefined {
  return defaultReasoningForProvider(provider, model);
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

function titleCase(value: string): string {
  return value
    .split(/[-_]/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}
