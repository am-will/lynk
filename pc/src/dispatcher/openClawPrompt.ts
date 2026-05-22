import type { AgentRequestOptions } from "./AgentClient.js";
import { buildAgentPrompt } from "./promptPolicy.js";

export function buildOpenClawPrompt(text: string, options: AgentRequestOptions = {}): string {
  return buildAgentPrompt("OpenClaw", text, options.taskKind, options.systemPrompt);
}
