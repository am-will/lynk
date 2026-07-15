import { WebSocket as WebSocketState, WebSocketServer, type RawData, type WebSocket } from "ws";
import type { AuditLog } from "./AuditLog.js";
import type { OpenClawChatBridge } from "./OpenClawChatBridge.js";
import type { RealtimeTaskManager } from "./RealtimeTaskManager.js";
import type { Dispatcher } from "../dispatcher/dispatcher.js";
import type { BridgeConfig } from "./config.js";
import type { PhoneHub } from "./PhoneHub.js";
import { REALTIME_TOOL_NAMES, inboundPhoneMessageSchema, type ClientPlatform } from "../protocol/messages.js";
import type { BridgeRealtime } from "./bridgeRealtime.js";
import { buildChatErrorMessage } from "./chat/ChatErrors.js";
import { tokenEquals } from "./httpAuth.js";
import {
  WebSocketAdmissionBudget,
  WebSocketHeartbeat,
  WebSocketMessageBudget,
  resolvePhoneWebSocketIngressOptions,
  type PhoneWebSocketIngressOptions,
  type ResolvedPhoneWebSocketIngressOptions
} from "./webSocketIngress.js";

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

export function createPhoneWebSocketServer(
  deps: PhoneWebSocketDependencies,
  options: PhoneWebSocketIngressOptions = {}
): WebSocketServer {
  const ingress = resolvePhoneWebSocketIngressOptions(options);
  const admission = new WebSocketAdmissionBudget(ingress);
  const heartbeat = new WebSocketHeartbeat();
  let wss: WebSocketServer;
  wss = new WebSocketServer({
    noServer: true,
    clientTracking: true,
    perMessageDeflate: false,
    maxPayload: ingress.maxPayloadBytes,
    verifyClient: (info, accept) => {
      const allowed = admission.allow(info.req.socket.remoteAddress, wss.clients.size);
      accept(
        allowed,
        allowed ? undefined : 503,
        allowed ? undefined : "WebSocket admission limit reached",
        allowed ? undefined : { "retry-after": "5" }
      );
    }
  });
  wss.on("connection", (socket) => {
    heartbeat.attach(socket);
    bindPhoneSocket(socket, deps, ingress);
  });
  const heartbeatTimer = setInterval(() => heartbeat.sweep(wss.clients), ingress.heartbeatIntervalMs);
  heartbeatTimer.unref();
  wss.once("close", () => clearInterval(heartbeatTimer));
  return wss;
}

export function bindPhoneSocket(
  socket: WebSocket,
  deps: PhoneWebSocketDependencies,
  options: PhoneWebSocketIngressOptions | ResolvedPhoneWebSocketIngressOptions = {}
): void {
  const ingress = resolvePhoneWebSocketIngressOptions(options);
  const messageBudget = new WebSocketMessageBudget(ingress);
  let deviceId: string | undefined;
  let clientPlatform: ClientPlatform = "android";
  const registrationTimer = setTimeout(() => {
    if (!deviceId) {
      closeSocket(socket, 4002, "registration timeout");
    }
  }, ingress.registrationTimeoutMs);
  registrationTimer.unref();

  socket.on("message", (data, isBinary = false) => {
    if (socket.readyState !== WebSocketState.OPEN) {
      return;
    }
    if (isBinary) {
      closeSocket(socket, 1003, "text messages only");
      return;
    }
    if (!messageBudget.allow()) {
      closeSocket(socket, 4008, "message rate limit");
      return;
    }

    const frameBytes = rawDataByteLength(data);
    if (!deviceId && frameBytes > ingress.registrationMaxBytes) {
      closeSocket(socket, 1009, "registration payload too large");
      return;
    }

    let rawMessage: unknown;
    try {
      rawMessage = JSON.parse(rawDataToString(data));
      if (deviceId && frameBytes > ingress.controlFrameMaxBytes) {
        closeSocket(socket, 1009, "control payload too large");
        return;
      }
      const message = inboundPhoneMessageSchema.parse(rawMessage);
      if (message.type === "register") {
        if (deviceId) {
          closeSocket(socket, 4004, "already registered");
          return;
        }
        if (!tokenEquals(message.token, deps.config.token)) {
          socket.close(4001, "invalid token");
          return;
        }
        deviceId = message.deviceId;
        clientPlatform = message.platform ?? "android";
        clearTimeout(registrationTimer);
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

      const claimedDeviceId = "deviceId" in message ? message.deviceId : undefined;
      if (claimedDeviceId !== undefined && claimedDeviceId !== deviceId) {
        deps.audit.record("phone_device_identity_rejected", deviceId, {
          claimedDeviceId,
          messageType: message.type
        });
        closeSocket(socket, 4004, "device identity mismatch");
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
        if (message.name === REALTIME_TOOL_NAMES.hangUpRealtime) {
          deps.realtime.handleRealtimeHangUpToolCall(message, deviceId).catch((error) => {
            deps.realtime.sendRealtimeError(message.deviceId, message.voiceSessionId, error instanceof Error ? error.message : String(error));
          });
          return;
        }
        if (clientPlatform === "ios" && isPhoneControlRealtimeTool(message.name)) {
          deps.realtime.sendRealtimeError(message.deviceId, message.voiceSessionId, `Realtime tool ${message.name} is unavailable on iOS.`);
          return;
        }
        deps.realtimeTaskManager.handleToolCall(message).catch((error) => {
          deps.realtime.sendRealtimeError(message.deviceId, message.voiceSessionId, error instanceof Error ? error.message : String(error));
        });
        return;
      }

      if (message.type === "realtime.start") {
        deps.realtime.startRealtimeSession(message, deviceId).catch((error) => {
          deps.realtime.sendRealtimeError(message.deviceId, message.voiceSessionId, error instanceof Error ? error.message : String(error));
        });
        return;
      }

      if (message.type === "realtime.stop") {
        deps.realtime.handleRealtimeStop(message, deviceId).catch((error) => {
          deps.realtime.sendRealtimeError(message.deviceId, message.voiceSessionId, error instanceof Error ? error.message : String(error));
        });
      }
    } catch (error) {
      const parsedDeviceId = rawMessage && typeof rawMessage === "object" && typeof (rawMessage as { deviceId?: unknown }).deviceId === "string"
        ? (rawMessage as { deviceId: string }).deviceId
        : deviceId;
      const parsedType = rawMessage && typeof rawMessage === "object" && typeof (rawMessage as { type?: unknown }).type === "string"
        ? (rawMessage as { type: string }).type
        : undefined;
      const parsedVoiceSessionId = rawMessage && typeof rawMessage === "object" && typeof (rawMessage as { voiceSessionId?: unknown }).voiceSessionId === "string"
        ? (rawMessage as { voiceSessionId: string }).voiceSessionId
        : undefined;
      if (parsedDeviceId && parsedVoiceSessionId && parsedType?.startsWith("realtime.")) {
        deps.realtime.sendRealtimeError(parsedDeviceId, parsedVoiceSessionId, error instanceof Error ? error.message : String(error));
        return;
      }
      if (socket.readyState === WebSocketState.OPEN) {
        socket.send(JSON.stringify({
          type: "agent_status",
          status: "error",
          text: error instanceof Error ? error.message : String(error)
        }));
      }
    }
  });

  socket.on("close", () => {
    clearTimeout(registrationTimer);
    const disconnectedDeviceId = deviceId;
    deps.hub.unregister(socket);
    if (disconnectedDeviceId) {
      void Promise.resolve(deps.realtimeTaskManager.failDevice(disconnectedDeviceId, "Phone WebSocket disconnected")).catch((error) => {
        console.warn(`[realtime] ${disconnectedDeviceId}: failed to clear tasks after disconnect: ${error instanceof Error ? error.message : String(error)}`);
      });
      deps.realtime.disconnectDevice(disconnectedDeviceId);
    }
  });

  socket.on("error", () => {
    deps.audit.record("phone_socket_error", deviceId);
  });
}

function isPhoneControlRealtimeTool(name: string): boolean {
  return name === REALTIME_TOOL_NAMES.runPhoneTask ||
    name === REALTIME_TOOL_NAMES.steerPhoneTask ||
    name === REALTIME_TOOL_NAMES.stopPhoneTask;
}

function closeSocket(socket: WebSocket, code: number, reason: string): void {
  if (socket.readyState === WebSocketState.OPEN) {
    socket.close(code, reason);
  }
}

function rawDataByteLength(data: RawData): number {
  if (Array.isArray(data)) {
    return data.reduce((total, chunk) => total + chunk.byteLength, 0);
  }
  return data.byteLength;
}

function rawDataToString(data: RawData): string {
  if (Array.isArray(data)) {
    return Buffer.concat(data).toString("utf8");
  }
  if (Buffer.isBuffer(data)) {
    return data.toString("utf8");
  }
  return Buffer.from(data).toString("utf8");
}
