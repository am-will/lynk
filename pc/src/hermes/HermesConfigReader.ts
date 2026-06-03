import { existsSync, readFileSync } from "node:fs";
import { resolveHermesConfigPath } from "../host/HermesConfigPath.js";

export interface HermesConfiguredModel {
  id: string;
  contextWindow?: number;
}

export interface HermesProviderSummary {
  provider: string;
  baseUrl?: string;
  apiKey?: string;
  apiMode?: string;
  defaultModel?: string;
  models: HermesConfiguredModel[];
}

export interface HermesConfigSummary {
  path: string;
  modelProvider?: string;
  modelDefault?: string;
  modelBaseUrl?: string;
  modelApiKey?: string;
  modelContextLength?: number;
  providers: Map<string, HermesProviderSummary>;
}

export function readHermesConfigSummary(path: string = resolveHermesConfigPath()): HermesConfigSummary {
  const summary: HermesConfigSummary = { path, providers: new Map() };
  if (!existsSync(path)) {
    return summary;
  }
  const lines = readFileSync(path, "utf8").split(/\r?\n/);
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
      readModelField(summary, trimmed);
      continue;
    }
    if (section !== "providers") {
      continue;
    }
    if (indent === 2 && trimmed.endsWith(":")) {
      provider = unquoteYamlScalar(trimmed.slice(0, -1).trim());
      if (provider) {
        summary.providers.set(provider, existingProvider(summary, provider));
      }
      inProviderModels = false;
      continue;
    }
    if (!provider) {
      continue;
    }
    if (indent === 4 && trimmed === "models:") {
      inProviderModels = true;
      continue;
    }
    if (inProviderModels) {
      readProviderModelField(summary, provider, trimmed, indent);
    } else if (indent === 4) {
      readProviderField(summary, provider, trimmed);
    }
  }
  return summary;
}

export function parsePositiveInt(value: string): number | undefined {
  const parsed = Number.parseInt(value.replace(/[,_]/g, ""), 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined;
}

export function unquoteYamlScalar(value: string): string {
  return value.replace(/^['"]|['"]$/g, "");
}

function readModelField(summary: HermesConfigSummary, trimmed: string): void {
  const [key, value] = yamlPair(trimmed);
  if (key === "provider") {
    summary.modelProvider = value;
  } else if (key === "default" || key === "model") {
    summary.modelDefault = value;
  } else if (key === "base_url") {
    summary.modelBaseUrl = value;
  } else if (key === "api_key") {
    summary.modelApiKey = resolveConfigValue(value);
  } else if (key === "context_length") {
    summary.modelContextLength = parsePositiveInt(value);
  }
}

function readProviderField(summary: HermesConfigSummary, provider: string, trimmed: string): void {
  const [key, value] = yamlPair(trimmed);
  const entry = existingProvider(summary, provider);
  if (key === "base_url") {
    entry.baseUrl = value;
  } else if (key === "api_key") {
    entry.apiKey = resolveConfigValue(value);
  } else if (key === "api_mode") {
    entry.apiMode = value;
  } else if (key === "default_model") {
    entry.defaultModel = value;
  }
  summary.providers.set(provider, entry);
}

function readProviderModelField(summary: HermesConfigSummary, provider: string, trimmed: string, indent: number): void {
  const entry = existingProvider(summary, provider);
  if (indent === 6 && trimmed.endsWith(":")) {
    entry.models.push({ id: unquoteYamlScalar(trimmed.slice(0, -1).trim()) });
  } else if (indent === 8 && trimmed.startsWith("context_length:")) {
    const last = entry.models.at(-1);
    if (last) {
      last.contextWindow = parsePositiveInt(trimmed.slice("context_length:".length).trim());
    }
  }
  summary.providers.set(provider, entry);
}

function existingProvider(summary: HermesConfigSummary, provider: string): HermesProviderSummary {
  return summary.providers.get(provider) ?? { provider, models: [] };
}

function yamlPair(line: string): [string, string] {
  const separator = line.indexOf(":");
  if (separator === -1) {
    return [line, ""];
  }
  return [line.slice(0, separator).trim(), unquoteYamlScalar(line.slice(separator + 1).trim())];
}

function resolveConfigValue(value: string): string | undefined {
  const trimmed = unquoteYamlScalar(value).trim();
  const envMatch = trimmed.match(/^\$\{([A-Za-z_][A-Za-z0-9_]*)\}$/);
  if (envMatch) {
    return process.env[envMatch[1]]?.trim() || undefined;
  }
  return trimmed || undefined;
}

function leadingSpaces(line: string): number {
  return line.length - line.trimStart().length;
}
