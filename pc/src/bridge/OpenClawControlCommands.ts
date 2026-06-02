import type {
  ChatControlCommandMessage,
  ChatNewSessionMessage,
  ChatSendMessage
} from "../protocol/messages.js";
import { parseHarnessPermissionReply } from "./harness/HarnessControlActions.js";
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
    successMessage?: string,
    options?: { ignoreRunEvents?: boolean }
  ): Promise<void>;
  patchSession(deviceId: string, sessionKey: string, patch: Record<string, unknown>, status?: string): Promise<void>;
  sendState(deviceId: string, status?: string): void;
  send(message: ChatSendMessage): Promise<void>;
  respondToPermission(sessionKey: string, permissionId: string, response: "once" | "always" | "reject"): Promise<void>;
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
    const firstArg = parts[0]?.toLowerCase();

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

    const permissionReply = parseHarnessPermissionReply(name, message.args);
    if (permissionReply) {
      if (!permissionReply.permissionId) {
        this.options.sendState(message.deviceId, permissionReply.invalidStatus);
        return;
      }
      await this.options.respondToPermission(state.sessionKey, permissionReply.permissionId, permissionReply.response);
      this.options.sendState(message.deviceId, permissionReply.successStatus);
      return;
    }

    const deliveryOverride = parseDeliveryOverride(name, parts.join(" "));
    if (deliveryOverride) {
      await this.options.send({
        type: "chat.send",
        deviceId: message.deviceId,
        text: deliveryOverride.text,
        sessionKey: state.sessionKey,
        delivery: deliveryOverride.delivery
      });
      return;
    }

    if (name === "fast") {
      const enabled = typeof message.args.enabled === "boolean"
        ? message.args.enabled
        : firstArg === "off" || firstArg === "normal"
          ? false
          : firstArg === "on" || firstArg === "fast"
            ? true
            : undefined;
      if (firstArg === "status") {
        this.options.sendState(message.deviceId, `Fast mode ${state.fastMode === true ? "enabled" : "disabled"}`);
        return;
      }
      const nextEnabled = enabled ?? (state.fastMode !== true);
      if (state.harnessId !== "openclaw") {
        state.fastMode = nextEnabled;
        await this.options.patchSession(
          message.deviceId,
          state.sessionKey,
          { fastMode: nextEnabled },
          `Fast mode ${nextEnabled ? "enabled" : "disabled"}`
        );
        return;
      }
      await this.options.sendSlashCommand(
        message.deviceId,
        `/fast ${nextEnabled ? "on" : "off"}`,
        state.sessionKey,
        "Updating fast mode",
        `Fast mode ${nextEnabled ? "enabled" : "disabled"}`,
        { ignoreRunEvents: Boolean(state.runId) }
      );
      return;
    }

    if (name === "verbose") {
      const level = typeof message.args.level === "string" && message.args.level.trim()
        ? message.args.level.trim()
        : firstArg && ["on", "off", "full"].includes(firstArg)
          ? firstArg
          : "on";
      await this.options.sendSlashCommand(
        message.deviceId,
        `/verbose ${level}`,
        state.sessionKey,
        "Updating verbosity",
        `Verbose mode set to ${level}`,
        { ignoreRunEvents: Boolean(state.runId) }
      );
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
        `Reasoning Stream ${state.reasoningStream ? "enabled" : "disabled"}`,
        { ignoreRunEvents: Boolean(state.runId) }
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
    const deliveryOverride = parseDeliveryOverride(name, parts.join(" "));
    if (deliveryOverride) {
      await this.options.send({
        type: "chat.send",
        deviceId,
        text: deliveryOverride.text,
        sessionKey,
        delivery: deliveryOverride.delivery
      });
      return true;
    }
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


function parseDeliveryOverride(name: string | undefined, rawText: string): { delivery: "queue" | "steer"; text: string } | undefined {
  const delivery = name === "queue" || name === "steer" ? name : undefined;
  if (!delivery) {
    return undefined;
  }
  const text = unquote(rawText.trim());
  if (!text) {
    return undefined;
  }
  return { delivery, text };
}

function unquote(text: string): string {
  if (text.length < 2) {
    return text;
  }
  const first = text.at(0);
  const last = text.at(-1);
  if ((first === "\"" && last === "\"") || (first === "'" && last === "'")) {
    return text.slice(1, -1).trim();
  }
  return text;
}
