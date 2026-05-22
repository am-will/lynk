import type { AgentRequestOptions } from "./AgentClient.js";
import { buildAgentPrompt } from "./promptPolicy.js";

export function buildHermesPrompt(text: string, options: AgentRequestOptions = {}): string {
  return buildAgentPrompt("Hermes Agent", text, options.taskKind, options.systemPrompt);
}
