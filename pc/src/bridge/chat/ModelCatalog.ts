import type { ChatReasoningOption } from "../../protocol/messages.js";

export const DEFAULT_REASONING_OPTIONS: ChatReasoningOption[] = [
  { id: "low", label: "low" },
  { id: "medium", label: "medium" },
  { id: "high", label: "high" },
  { id: "xhigh", label: "xhigh" }
];

export const ALLOWED_REASONING_OPTION_IDS = new Set([
  "none",
  "minimal",
  ...DEFAULT_REASONING_OPTIONS.map((option) => option.id)
]);

const CODEX_APP_SERVER_CONTEXT_WINDOWS: Record<string, number> = {
  "gpt-5.5": 400_000,
  "gpt-5.4": 400_000,
  "gpt-5.4-mini": 400_000,
  "gpt-5.3-codex": 400_000,
  "gpt-5.3-codex-spark": 400_000,
  "gpt-5.2": 400_000,
  "gpt-5.2-codex": 400_000,
  "gpt-5.1-codex-max": 400_000,
  "gpt-5.1-codex-mini": 400_000
};

const HERMES_CODEX_OAUTH_CONTEXT_WINDOWS: Record<string, number> = {
  "gpt-5.1-codex-max": 272_000,
  "gpt-5.1-codex-mini": 272_000,
  "gpt-5.3-codex-spark": 128_000,
  "gpt-5.3-codex": 272_000,
  "gpt-5.2-codex": 272_000,
  "gpt-5.4-mini": 272_000,
  "gpt-5.5": 272_000,
  "gpt-5.4": 272_000,
  "gpt-5.2": 272_000,
  "gpt-5": 272_000
};

export function codexAppServerContextWindow(model: string): number | undefined {
  return CODEX_APP_SERVER_CONTEXT_WINDOWS[model.trim()];
}

export function hermesCodexOauthContextWindow(model: string): number | undefined {
  return longestSlugMatch(model, HERMES_CODEX_OAUTH_CONTEXT_WINDOWS);
}

export function reasoningOptionsForProvider(provider: string, model: string): ChatReasoningOption[] | undefined {
  const normalizedProvider = provider.toLowerCase();
  const normalizedModel = model.toLowerCase();
  if (
    normalizedProvider.includes("minimax") ||
    normalizedProvider === "local" ||
    normalizedModel.includes("minimax") ||
    normalizedProvider === "anthropic" ||
    normalizedModel.includes("claude")
  ) {
    return undefined;
  }
  return DEFAULT_REASONING_OPTIONS;
}

export function defaultReasoningForProvider(provider: string, model: string): string | undefined {
  return reasoningOptionsForProvider(provider, model)?.some((option) => option.id === "medium")
    ? "medium"
    : undefined;
}

function longestSlugMatch(model: string, catalog: Record<string, number>): number | undefined {
  const normalized = model.toLowerCase();
  for (const [slug, contextWindow] of Object.entries(catalog).sort((left, right) => right[0].length - left[0].length)) {
    if (normalized.includes(slug)) {
      return contextWindow;
    }
  }
  return undefined;
}
