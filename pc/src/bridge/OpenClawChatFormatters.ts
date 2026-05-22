import type {
  ChatCommandOption,
  ChatSessionSummary,
  ChatToolSummary
} from "../protocol/messages.js";

interface PendingRunSummary {
  sessionKey: string;
}

export interface ChatFormatterState {
  sessionKey: string;
  runId?: string | null;
  model?: string | null;
  reasoningEffort?: string | null;
  reasoningStream?: boolean | null;
  fastMode?: boolean | null;
  verboseLevel?: string | null;
  pendingRuns: ReadonlyMap<string, PendingRunSummary>;
  sessionSummaries: ReadonlyMap<string, ChatSessionSummary>;
}

export function formatHelp(commands: ChatCommandOption[]): string {
  const commandByName = commandLookup(commands);
  const session = ["/new", "/reset", "/compact [instructions]", "/stop"];
  const options = [
    "/think <level>",
    "/model <id>",
    "/fast status|on|off",
    "/verbose on|off|full",
    "/reasoning stream|off",
    "/trace on|off|raw"
  ].filter((entry) => commandByName.has(entry.slice(1).split(/[ <]/)[0]));
  const status = ["/status", "/tasks", "/whoami", "/context"].filter((entry) => commandByName.has(entry.slice(1)));
  const hasSkill = commandByName.has("skill");

  return [
    "ℹ️ Help",
    "",
    "Session",
    session.filter((entry) => commandByName.has(entry.slice(1).split(/[ <\[]/)[0])).join(" | "),
    "",
    "Options",
    options.join(" | "),
    "",
    "Status",
    status.join(" | "),
    "",
    "Skills",
    hasSkill ? "/skill <name> [input]" : "",
    "",
    "More: /commands for full list, /tools for available capabilities"
  ].filter((line, index, lines) => line !== "" || lines[index - 1] !== "").join("\n").trim();
}

export function formatCommandList(commands: ChatCommandOption[]): string {
  if (commands.length === 0) {
    return "No slash commands are available from OpenClaw right now.";
  }

  const native = commands.filter((command) => command.source !== "skill");
  const skills = commands.filter((command) => command.source === "skill");
  const lines = [
    `ℹ️ Commands (${commands.length})`,
    "",
    ...formatCommandGroups(native),
    ...(skills.length > 0 ? ["", `Skills (${skills.length})`, ...skills.slice(0, 40).map(formatCommandLine)] : [])
  ];
  if (skills.length > 40) {
    lines.push(`...and ${skills.length - 40} more skills. Use /skill <name> [input] to run one.`);
  }
  return lines.join("\n").trim();
}

export function formatToolList(tools: ChatToolSummary[], mode?: string): string {
  if (tools.length === 0) {
    return "🧰 Tools\nNo runtime tools are available for this session.";
  }
  const verbose = mode?.toLowerCase() === "verbose";
  const grouped = groupBy(tools, (tool) => tool.group ?? tool.source ?? "Tools");
  const lines = [`🧰 Tools (${tools.length})`];
  for (const [group, groupTools] of grouped) {
    lines.push("", group);
    for (const tool of groupTools.slice(0, verbose ? 30 : 20)) {
      const label = tool.label && tool.label !== tool.id ? `${tool.label} (${tool.id})` : tool.id;
      const description = verbose && tool.description ? ` - ${tool.description}` : "";
      lines.push(`/${label}${description}`);
    }
    if (groupTools.length > (verbose ? 30 : 20)) {
      lines.push(`...and ${groupTools.length - (verbose ? 30 : 20)} more in ${group}`);
    }
  }
  if (!verbose) {
    lines.push("", "Use /tools verbose for descriptions.");
  }
  return lines.join("\n");
}

export function formatTaskList(state: ChatFormatterState): string {
  const pending = [...state.pendingRuns.entries()];
  if (!state.runId && pending.length === 0) {
    return "📋 Tasks\nNo background tasks are running for this session.";
  }
  const lines = ["📋 Tasks"];
  if (state.runId) {
    lines.push(`Active run: ${state.runId}`);
  }
  for (const [runId, run] of pending) {
    lines.push(`/${runId} - ${run.sessionKey === state.sessionKey ? "current session" : run.sessionKey}`);
  }
  return lines.join("\n");
}

export function formatStatusReport(state: ChatFormatterState, health: unknown): string {
  const record = health && typeof health === "object" ? health as Record<string, unknown> : undefined;
  const eventLoop = record?.eventLoop && typeof record.eventLoop === "object" ? record.eventLoop as Record<string, unknown> : undefined;
  const sessions = state.sessionSummaries.size;
  return [
    "ℹ️ Status",
    "",
    `Session: ${state.sessionKey}`,
    `Run: ${state.runId ?? "idle"}`,
    `Model: ${state.model ?? "default"}`,
    `Thinking: ${state.reasoningEffort ?? "default"}`,
    `Reasoning stream: ${state.reasoningStream === true ? "on" : "off"}`,
    `Fast mode: ${state.fastMode === true ? "on" : state.fastMode === false ? "off" : "unknown"}`,
    `Verbose: ${state.verboseLevel ?? "unknown"}`,
    `Known sessions: ${sessions}`,
    record ? `Gateway: ${record.ok === true ? "ok" : "not ok"}${eventLoop?.degraded === true ? " (degraded)" : ""}` : "Gateway: unavailable"
  ].join("\n");
}

export function previewText(text: string): string | null {
  const normalized = text.replace(/\s+/g, " ").trim();
  if (!normalized) {
    return null;
  }
  return normalized.length <= 180 ? normalized : `${normalized.slice(0, 177).trimEnd()}...`;
}

function formatCommandGroups(commands: ChatCommandOption[]): string[] {
  const lines: string[] = [];
  for (const [category, categoryCommands] of groupBy(commands, (command) => titleCase(command.category ?? "other"))) {
    lines.push(category);
    for (const command of categoryCommands) {
      lines.push(formatCommandLine(command));
    }
    lines.push("");
  }
  while (lines.at(-1) === "") {
    lines.pop();
  }
  return lines;
}

function formatCommandLine(command: ChatCommandOption): string {
  const aliases = command.textAliases?.filter((alias) => alias.trim()) ?? [];
  const primary = aliases.find((alias) => alias.startsWith("/")) ?? `/${command.name}`;
  const secondary = aliases.filter((alias) => alias !== primary).slice(0, 3).join(", ");
  const args = command.args?.length
    ? ` ${command.args.map((arg) => arg.required ? `<${arg.name}>` : `[${arg.name}]`).join(" ")}`
    : command.acceptsArgs
      ? " [args]"
      : "";
  const aliasText = secondary ? ` (${secondary})` : "";
  const description = command.description ? ` - ${command.description}` : "";
  return `${primary}${args}${aliasText}${description}`;
}

function commandLookup(commands: ChatCommandOption[]): Set<string> {
  return new Set(commands.flatMap((command) => [
    command.name,
    ...(command.textAliases ?? []).map((alias) => alias.replace(/^\//, ""))
  ]));
}

function groupBy<T>(items: T[], keyFor: (item: T) => string): Map<string, T[]> {
  const grouped = new Map<string, T[]>();
  for (const item of items) {
    const key = keyFor(item);
    grouped.set(key, [...(grouped.get(key) ?? []), item]);
  }
  return grouped;
}

function titleCase(value: string): string {
  return value.replace(/(^|\s)\S/g, (letter) => letter.toUpperCase());
}
