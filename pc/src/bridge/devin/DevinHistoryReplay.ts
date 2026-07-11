import type {
  AvailableCommand,
  SessionConfigOption,
  SessionModeState,
  SessionNotification
} from "@agentclientprotocol/sdk";
import type { ChatCommandOption, ChatHistoryMessage } from "../../protocol/messages.js";
import type { DevinAcpClient } from "./DevinAcpClient.js";

export interface DevinSetupSnapshot {
  messages: ChatHistoryMessage[];
  commands: AvailableCommand[];
  currentModeState?: SessionModeState;
  configOptions: SessionConfigOption[];
}

export class DevinSessionUpdateCollector {
  private readonly collected: SessionNotification[] = [];
  private unsubscribe?: () => void;

  constructor(client: DevinAcpClient) {
    this.unsubscribe = client.addEventListener((event) => {
      if (event.type === "session/update") {
        this.collected.push(event.notification);
      }
    });
  }

  detach(): void {
    this.unsubscribe?.();
    this.unsubscribe = undefined;
  }

  snapshot(sessionId?: string, baseTimestamp?: number): DevinSetupSnapshot {
    const notifications = sessionId
      ? this.collected.filter((notification) => notification.sessionId === sessionId)
      : this.collected;
    const messages = assembleReplayHistory(notifications, baseTimestamp);
    const commands = this.latestAvailableCommands(notifications);
    const currentModeState = this.latestModeState(notifications);
    const configOptions = this.latestConfigOptions(notifications) ?? [];
    return { messages, commands, currentModeState, configOptions };
  }

  private latestAvailableCommands(notifications: SessionNotification[]): AvailableCommand[] {
    let latest: AvailableCommand[] | undefined;
    for (const notification of notifications) {
      if (notification.update.sessionUpdate === "available_commands_update") {
        latest = notification.update.availableCommands;
      }
    }
    return latest ?? [];
  }

  private latestModeState(notifications: SessionNotification[]): SessionModeState | undefined {
    let latest: SessionModeState | undefined;
    for (const notification of notifications) {
      if (notification.update.sessionUpdate === "current_mode_update") {
        latest = { currentModeId: notification.update.currentModeId, availableModes: [] };
      }
    }
    return latest;
  }

  private latestConfigOptions(notifications: SessionNotification[]): SessionConfigOption[] | undefined {
    let latest: SessionConfigOption[] | undefined;
    for (const notification of notifications) {
      if (notification.update.sessionUpdate === "config_option_update") {
        latest = notification.update.configOptions;
      }
    }
    return latest;
  }
}

export function assembleReplayHistory(
  notifications: SessionNotification[],
  baseTimestamp = Date.now()
): ChatHistoryMessage[] {
  const chunks = notifications.filter(isReplayTextChunk);
  if (chunks.length === 0) {
    return [];
  }

  const messages: ChatHistoryMessage[] = [];
  let current: { id: string; role: "user" | "assistant"; text: string; messageId?: string | null } | undefined;

  for (let index = 0; index < chunks.length; index += 1) {
    const chunk = chunks[index]!;
    const role = chunkRole(chunk.update);
    const messageId = chunk.update.messageId;

    if (!current) {
      current = { id: messageId ?? `devin_${index}`, role, text: chunkText(chunk.update), messageId };
      continue;
    }

    const sameMessageId = current.messageId != null && messageId != null && current.messageId === messageId;
    const bothIdless = current.messageId == null && messageId == null;
    const sameRole = current.role === role;

    if (sameRole && (sameMessageId || bothIdless)) {
      current.text += chunkText(chunk.update);
    } else {
      messages.push({
        id: current.id,
        role: current.role,
        text: current.text,
        timestamp: baseTimestamp + messages.length
      });
      current = { id: messageId ?? `devin_${index}`, role, text: chunkText(chunk.update), messageId };
    }
  }

  if (current) {
    messages.push({
      id: current.id,
      role: current.role,
      text: current.text,
      timestamp: baseTimestamp + messages.length
    });
  }

  return messages;
}

export function chatCommandsFromAvailableCommands(commands: AvailableCommand[]): ChatCommandOption[] {
  return commands.map((command) => ({
    name: command.name,
    description: command.description,
    source: "devin",
    acceptsArgs: command.input != null,
    args: command.input
      ? [
          {
            name: "input",
            description: command.input.hint,
            type: "string",
            required: true
          }
        ]
      : undefined
  }));
}

interface ReplayTextChunk {
  readonly sessionUpdate: "user_message_chunk" | "agent_message_chunk";
  readonly messageId?: string | null;
  readonly content: { type: "text"; text: string };
}

function isReplayTextChunk(notification: SessionNotification): notification is SessionNotification & { update: ReplayTextChunk } {
  const update = notification.update;
  if (update.sessionUpdate !== "user_message_chunk" && update.sessionUpdate !== "agent_message_chunk") {
    return false;
  }
  if (update.content.type !== "text" || typeof update.content.text !== "string") {
    return false;
  }
  return true;
}

function chunkRole(chunk: ReplayTextChunk): "user" | "assistant" {
  return chunk.sessionUpdate === "user_message_chunk" ? "user" : "assistant";
}

function chunkText(chunk: ReplayTextChunk): string {
  return chunk.content.text;
}
