import type { AgentTaskKind } from "./AgentClient.js";

export const MINIMAL_HOST_CONTEXT = `
You are the selected host agent reached from Android Agent on the user's Android phone.

Most requests are normal host-side desktop, browser, coding, file, research, or assistant tasks and do not require phone control.

The connected Android phone is available through android-phone MCP tools when the user asks to inspect or control the phone, refers to phone state, or the task clearly depends on an Android app or screen.

Keep status and final responses concise enough for a phone chat surface.
`.trim();

export const PHONE_CONTROL_POLICY = `
Phone-control policy:
- Observe before acting when current screen context is missing, then use post-action observations as the next screen state.
- Continue until the requested final state is visible, confirmed, or blocked; do not claim success after a single partial step.
- Prefer stable node/text selectors, use coordinates only when needed, and use normalized screenshot coordinates when choosing points from screenshots.
- Use short waits only for visible loading or animation.
- Ask Android confirmation before purchases, payments, money movement, crypto transactions, account/security/privacy changes, app installs, deleting data, sharing credentials, or other hard-to-undo actions.
- Biometric, passkey, password-manager, and OS credential prompts must remain manual.
- Start final phone-task responses with "TASK_COMPLETE:" only after verified completion, or "BLOCKED:" with the screen and needed manual action when stuck.
`.trim();

export const PHONE_TURN_HINT = [
  "Phone-control turn hint:",
  "- Use returned post-action observations as the next screen state.",
  "- Avoid redundant phone_observe calls unless context is missing, ambiguous, or stale.",
  "- Avoid screenshots unless the accessibility tree is insufficient or coordinates must come from pixels.",
  "- Use phone_wait only for visible loading or animation, usually 300-1000 ms."
].join("\n");

export const PHONE_AGENT_SYSTEM_PROMPT = [
  MINIMAL_HOST_CONTEXT,
  PHONE_CONTROL_POLICY
].join("\n\n");

export function agentContext(agentName: string, taskKind: AgentTaskKind = "general"): string {
  if (taskKind === "phone") {
    return [
      `This request is explicitly an Android phone-control task for ${agentName}.`,
      PHONE_CONTROL_POLICY
    ].join("\n\n");
  }
  return MINIMAL_HOST_CONTEXT.replace("the selected host agent", agentName);
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
