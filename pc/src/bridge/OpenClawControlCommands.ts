import type {
  ChatControlCommandMessage,
  ChatNewSessionMessage,
  ChatSendMessage
} from "../protocol/messages.js";
import type { DeviceChatState } from "./OpenClawChatTypes.js";

interface ControlCommandRouterOptions {
  stateFor(deviceId: string): DeviceChatState;
  newSession(message: ChatNewSessionMessage): Promise<void>;
  sendStatusReport(deviceId: string): Promise<void>;
  sendHelp(deviceId: string): Promise<void>;
  sendCommandList(deviceId: string): Promise<void>;
  sendToolList(deviceId: string, mode?: string): Promise<void>;
  sendTaskList(deviceId: string): void;
  sendSlashCommand(
    deviceId: string,
    text: string,
    sessionKey: string,
    status: string,
    successMessage?: string
  ): Promise<void>;
  send(message: ChatSendMessage): Promise<void>;
}

export class OpenClawControlCommandRouter {
  constructor(private readonly options: ControlCommandRouterOptions) {}

  async controlCommand(message: ChatControlCommandMessage): Promise<void> {
    const state = this.options.stateFor(message.deviceId);
    const command = message.command.trim();
    if (!command) {
      return;
    }
    const normalized = command.startsWith("/") ? command.slice(1).trim() : command;
    const [rawName = "", ...parts] = normalized.split(/\s+/);
    const name = rawName.toLowerCase();
    const firstArg = parts[0];

    if (name === "new") {
      await this.options.newSession({
        type: "chat.new_session",
        deviceId: message.deviceId
      });
      return;
    }

    if (name === "status") {
      await this.options.sendStatusReport(message.deviceId);
      return;
    }

    if (name === "help") {
      await this.options.sendHelp(message.deviceId);
      return;
    }

    if (name === "commands") {
      await this.options.sendCommandList(message.deviceId);
      return;
    }

    if (name === "tools") {
      await this.options.sendToolList(message.deviceId, firstArg);
      return;
    }

    if (name === "tasks") {
      this.options.sendTaskList(message.deviceId);
      return;
    }

    if (name === "fast") {
      const enabled = typeof message.args.enabled === "boolean"
        ? message.args.enabled
        : firstArg === "off"
          ? false
          : firstArg === "on"
            ? true
            : undefined;
      await this.options.sendSlashCommand(
        message.deviceId,
        `/fast ${enabled === false ? "off" : "on"}`,
        state.sessionKey,
        "Updating fast mode",
        `Fast mode ${enabled === false ? "disabled" : "enabled"}`
      );
      return;
    }

    if (name === "verbose") {
      const level = typeof message.args.level === "string" && message.args.level.trim()
        ? message.args.level.trim()
        : firstArg && ["on", "off", "full"].includes(firstArg)
          ? firstArg
          : "on";
      await this.options.sendSlashCommand(message.deviceId, `/verbose ${level}`, state.sessionKey, "Updating verbosity", `Verbose mode set to ${level}`);
      return;
    }

    if (name === "reasoning") {
      const level = typeof message.args.level === "string" && message.args.level.trim() === "stream"
        ? "stream"
        : firstArg === "stream"
          ? "stream"
          : "off";
      state.reasoningStream = level === "stream";
      await this.options.sendSlashCommand(
        message.deviceId,
        `/reasoning ${level}`,
        state.sessionKey,
        `Reasoning Stream: ${state.reasoningStream ? "On" : "Off"}`,
        `Reasoning Stream ${state.reasoningStream ? "enabled" : "disabled"}`
      );
      return;
    }

    const slashText = command.startsWith("/") ? command : `/${command}`;
    await this.options.send({
      type: "chat.send",
      deviceId: message.deviceId,
      text: slashText,
      sessionKey: state.sessionKey
    });
  }

  async handleVisibleSlashCommand(deviceId: string, text: string, sessionKey: string): Promise<boolean> {
    const normalized = text.trim();
    if (!normalized.startsWith("/")) {
      return false;
    }
    const [rawName, ...parts] = normalized.slice(1).trim().split(/\s+/);
    const name = rawName?.toLowerCase();
    const firstArg = parts[0]?.toLowerCase();
    if (name !== "reasoning" && name !== "reason") {
      return false;
    }

    const state = this.options.stateFor(deviceId);
    const currentEnabled = state.reasoningStream === true;
    const level = firstArg === "stream" || firstArg === "on"
      ? "stream"
      : firstArg === "off"
        ? "off"
        : currentEnabled
          ? "stream"
          : "off";
    const nextEnabled = level === "stream";
    state.reasoningStream = nextEnabled;
    await this.options.sendSlashCommand(
      deviceId,
      firstArg ? `/reasoning ${level}` : "/reasoning",
      sessionKey,
      `Reasoning Stream: ${nextEnabled ? "On" : "Off"}`,
      `Reasoning Stream ${nextEnabled ? "enabled" : "disabled"}`
    );
    return true;
  }
}
