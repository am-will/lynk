import type { AgentTaskKind } from "./AgentClient.js";
import { GENERATED_DEFAULT_SYSTEM_PROMPT } from "./defaultSystemPrompt.generated.js";

export const PHONE_TURN_HINT = [
  "Phone-control turn hint:",
  "- This request is explicitly an Android phone-control task.",
  "- Use the $android-control skill before acting."
].join("\n");

export const PHONE_AGENT_SYSTEM_PROMPT = GENERATED_DEFAULT_SYSTEM_PROMPT;

export function agentContext(agentName: string, taskKind: AgentTaskKind = "general"): string {
  const basePrompt = PHONE_AGENT_SYSTEM_PROMPT.replace("the selected host agent", agentName);
  if (taskKind === "phone") {
    return [
      `This request is explicitly an Android phone-control task for ${agentName}.`,
      basePrompt
    ].join("\n\n");
  }
  return basePrompt;
}

export function buildAgentPrompt(agentName: string, userText: string, taskKind: AgentTaskKind = "general", customPrompt?: string): string {
  return [
    customPrompt?.trim(),
    agentContext(agentName, taskKind),
    `User request:\n${userText.trim()}`
  ].filter(Boolean).join("\n\n");
}

export function buildPhoneAgentPrompt(userText: string, systemPrompt?: string): string {
  const prompt = systemPrompt?.trim() || PHONE_AGENT_SYSTEM_PROMPT;
  return `${prompt}\n\nUser request from Android bubble:\n${userText}`;
}
