import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import type { AgentTaskKind } from "./AgentClient.js";

const DEFAULT_SYSTEM_PROMPT_PATH = "android/app/src/main/java/dev/androidagent/DefaultSystemPrompt.kt";

export const PHONE_TURN_HINT = [
  "Phone-control turn hint:",
  "- This request is explicitly an Android phone-control task.",
  "- Use the $android-control skill before acting."
].join("\n");

export const PHONE_AGENT_SYSTEM_PROMPT = loadDefaultSystemPrompt();

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

function loadDefaultSystemPrompt(): string {
  const source = readDefaultSystemPromptSource();
  const match = source.match(/val\s+text:\s*String\s*=\s*"""([\s\S]*?)"""\.trimIndent\(\)/);
  if (!match) {
    throw new Error(`Could not extract DefaultSystemPrompt.text from ${DEFAULT_SYSTEM_PROMPT_PATH}`);
  }
  return kotlinRawStringToText(match[1] ?? "");
}

function readDefaultSystemPromptSource(): string {
  const currentFile = fileURLToPath(import.meta.url);
  const repoRootFromModule = resolve(dirname(currentFile), "../../..");
  const candidates = [
    resolve(process.cwd(), DEFAULT_SYSTEM_PROMPT_PATH),
    resolve(process.cwd(), "..", DEFAULT_SYSTEM_PROMPT_PATH),
    resolve(repoRootFromModule, DEFAULT_SYSTEM_PROMPT_PATH)
  ];
  for (const path of candidates) {
    try {
      return readFileSync(path, "utf8");
    } catch {
      // Try the next common launch location.
    }
  }
  throw new Error(`Could not read ${DEFAULT_SYSTEM_PROMPT_PATH} from ${candidates.join(", ")}`);
}

function kotlinRawStringToText(value: string): string {
  const lines = value.replace(/\r\n/g, "\n").split("\n");
  while (lines[0]?.trim() === "") {
    lines.shift();
  }
  while (lines[lines.length - 1]?.trim() === "") {
    lines.pop();
  }
  const nonBlankIndents = lines
    .filter((line) => line.trim() !== "")
    .map((line) => line.match(/^[ \t]*/)?.[0].length ?? 0);
  const indent = nonBlankIndents.length > 0 ? Math.min(...nonBlankIndents) : 0;
  return lines
    .map((line) => line.slice(indent))
    .join("\n")
    .replace(/\$\{'\$'\}/g, "$")
    .trim();
}
