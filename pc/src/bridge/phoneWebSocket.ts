import { WebSocketServer, type WebSocket } from "ws";
import type { AuditLog } from "./AuditLog.js";
import type { OpenClawChatBridge } from "./OpenClawChatBridge.js";
import type { RealtimeTaskManager } from "./RealtimeTaskManager.js";
import type { Dispatcher } from "../dispatcher/dispatcher.js";
import type { BridgeConfig } from "./config.js";
import type { PhoneHub } from "./PhoneHub.js";
import { REALTIME_TOOL_NAMES, inboundPhoneMessageSchema } from "../protocol/messages.js";
import type { BridgeRealtime } from "./bridgeRealtime.js";
import { buildChatErrorMessage } from "./chat/ChatErrors.js";

export interface PhoneWebSocketDependencies {
  config: Pick<BridgeConfig, "token">;
  hub: Pick<PhoneHub, "register" | "unregister" | "handleResult" | "sendStatus" | "sendChat">;
  audit: Pick<AuditLog, "record">;
  dispatcher: Pick<Dispatcher, "handleUserRequest">;
  chatBridge: Pick<
    OpenClawChatBridge,
    "open" | "send" | "stop" | "selectSession" | "newSession" | "setModel" | "setReasoning" | "controlCommand"
  >;
  realtimeTaskManager: Pick<RealtimeTaskManager, "handleToolCall" | "failDevice">;
  realtime: Pick<
    BridgeRealtime,
    "sendRealtimeError" | "startRealtimeSession" | "handleRealtimeStop" | "handleRealtimeHangUpToolCall" | "disconnectDevice"
  >;
  stopAgentWork: (deviceId: string, reason: string) => Promise<void>;
}

export function createPhoneWebSocketServer(deps: PhoneWebSocketDependencies): WebSocketServer {
  const wss = new WebSocketServer({ noServer: true });
  wss.on("connection", (socket) => bindPhoneSocket(socket, deps));
  return wss;
}

export function bindPhoneSocket(socket: WebSocket, deps: PhoneWebSocketDependencies): void {
  let deviceId: string | undefined;

  socket.on("message", (data) => {
    let rawMessage: unknown;
    try {
      rawMessage = JSON.parse(data.toString());
      const message = inboundPhoneMessageSchema.parse(rawMessage);
      if (message.type === "register") {
        if (message.token !== deps.config.token) {
          socket.close(4001, "invalid token");
          return;
        }
        deviceId = message.deviceId;
        deps.hub.register(message, socket);
        deps.audit.record("phone_registered", deviceId, {
          capabilities: message.capabilities,
          connectedAt: Date.now()
        });
        deps.hub.sendStatus(deviceId, { deviceId, status: "info", text: `Registered ${deviceId}` });
        return;
      }

      if (!deviceId) {
        socket.close(4002, "register first");
        return;
      }

      if (message.type === "result") {
        deps.hub.handleResult(deviceId, message);
        return;
      }

      if (message.type === "user_request") {
        deps.dispatcher.handleUserRequest(message).catch((error) => {
          deps.hub.sendStatus(message.deviceId, {
            deviceId: message.deviceId,
            status: "error",
            text: error instanceof Error ? error.message : String(error)
          });
        });
        return;
      }

      if (message.type === "agent_control") {
        if (message.action === "stop") {
          deps.stopAgentWork(message.deviceId, message.reason ?? "Stopped from Android").catch((error) => {
            deps.hub.sendStatus(message.deviceId, {
              deviceId: message.deviceId,
              status: "error",
              text: error instanceof Error ? error.message : String(error)
            });
          });
        }
        return;
      }

      if (message.type === "chat.open") {
        deps.chatBridge.open(message).catch((error) => {
          deps.hub.sendChat(message.deviceId, buildChatErrorMessage({
            deviceId: message.deviceId,
            sessionKey: message.sessionKey,
            error
          }));
        });
        return;
      }

      if (message.type === "chat.send") {
        deps.chatBridge.send(message).catch((error) => {
          deps.hub.sendChat(message.deviceId, buildChatErrorMessage({
            deviceId: message.deviceId,
            sessionKey: message.sessionKey,
            error
          }));
        });
        return;
      }

      if (message.type === "chat.stop") {
        deps.chatBridge.stop(message).catch((error) => {
          deps.hub.sendChat(message.deviceId, buildChatErrorMessage({
            deviceId: message.deviceId,
            sessionKey: message.sessionKey,
            runId: message.runId,
            error
          }));
        });
        return;
      }

      if (message.type === "chat.select_session") {
        deps.chatBridge.selectSession(message).catch((error) => {
          deps.hub.sendChat(message.deviceId, buildChatErrorMessage({
            deviceId: message.deviceId,
            sessionKey: message.sessionKey,
            error
          }));
        });
        return;
      }

      if (message.type === "chat.new_session") {
        deps.chatBridge.newSession(message).catch((error) => {
          deps.hub.sendChat(message.deviceId, buildChatErrorMessage({
            deviceId: message.deviceId,
            error
          }));
        });
        return;
      }

      if (message.type === "chat.set_model") {
        deps.chatBridge.setModel(message).catch((error) => {
          deps.hub.sendChat(message.deviceId, buildChatErrorMessage({
            deviceId: message.deviceId,
            sessionKey: message.sessionKey,
            error
          }));
        });
        return;
      }

      if (message.type === "chat.set_reasoning") {
        deps.chatBridge.setReasoning(message).catch((error) => {
          deps.hub.sendChat(message.deviceId, buildChatErrorMessage({
            deviceId: message.deviceId,
            sessionKey: message.sessionKey,
            error
          }));
        });
        return;
      }

      if (message.type === "chat.control_command") {
        deps.chatBridge.controlCommand(message).catch((error) => {
          deps.hub.sendChat(message.deviceId, buildChatErrorMessage({
            deviceId: message.deviceId,
            error
          }));
        });
        return;
      }

      if (message.type === "realtime.tool_call") {
        if (message.deviceId !== deviceId) {
          deps.realtime.sendRealtimeError(deviceId, `realtime.tool_call deviceId ${message.deviceId} does not match registered device ${deviceId}`);
          return;
        }
        if (message.name === REALTIME_TOOL_NAMES.hangUpRealtime) {
          deps.realtime.handleRealtimeHangUpToolCall(message, deviceId).catch((error) => {
            deps.realtime.sendRealtimeError(message.deviceId, error instanceof Error ? error.message : String(error));
          });
          return;
        }
        deps.realtimeTaskManager.handleToolCall(message).catch((error) => {
          deps.realtime.sendRealtimeError(message.deviceId, error instanceof Error ? error.message : String(error));
        });
        return;
      }

      if (message.type === "realtime.start") {
        deps.realtime.startRealtimeSession(message, deviceId).catch((error) => {
          deps.realtime.sendRealtimeError(message.deviceId, error instanceof Error ? error.message : String(error));
        });
        return;
      }

      if (message.type === "realtime.stop") {
        deps.realtime.handleRealtimeStop(message, deviceId).catch((error) => {
          deps.realtime.sendRealtimeError(message.deviceId, error instanceof Error ? error.message : String(error));
        });
      }
    } catch (error) {
      const parsedDeviceId = rawMessage && typeof rawMessage === "object" && typeof (rawMessage as { deviceId?: unknown }).deviceId === "string"
        ? (rawMessage as { deviceId: string }).deviceId
        : deviceId;
      const parsedType = rawMessage && typeof rawMessage === "object" && typeof (rawMessage as { type?: unknown }).type === "string"
        ? (rawMessage as { type: string }).type
        : undefined;
      if (parsedDeviceId && parsedType?.startsWith("realtime.")) {
        deps.realtime.sendRealtimeError(parsedDeviceId, error instanceof Error ? error.message : String(error));
        return;
      }
      socket.send(JSON.stringify({
        type: "agent_status",
        status: "error",
        text: error instanceof Error ? error.message : String(error)
      }));
    }
  });

  socket.on("close", () => {
    const disconnectedDeviceId = deviceId;
    deps.hub.unregister(socket);
    if (disconnectedDeviceId) {
      void Promise.resolve(deps.realtimeTaskManager.failDevice(disconnectedDeviceId, "Phone WebSocket disconnected")).catch((error) => {
        deps.realtime.sendRealtimeError(disconnectedDeviceId, error instanceof Error ? error.message : String(error));
      });
      deps.realtime.disconnectDevice(disconnectedDeviceId);
    }
  });
}
