import type { AuditLog } from "./AuditLog.js";
import type { OpenAiRealtimeClient, OpenAiRealtimeSession } from "./OpenAiRealtimeClient.js";
import type { BridgeConfig } from "./config.js";
import type { PhoneHub } from "./PhoneHub.js";
import type {
  PhoneLocation,
  RealtimeOutboundMessage,
  RealtimeStartMessage,
  RealtimeStopMessage
} from "../protocol/messages.js";

export interface BridgeRealtimeDependencies {
  config: Pick<BridgeConfig, "openAiRealtimeModel" | "openAiRealtimeVoice">;
  hub: Pick<PhoneHub, "sendRealtime">;
  audit: Pick<AuditLog, "record">;
  realtimeClient: Pick<OpenAiRealtimeClient, "start" | "stop">;
  syncRealtimeChatContext?: (message: RealtimeStartMessage) => void | Promise<void>;
  stopAgentWork: (deviceId: string, reason: string) => Promise<void>;
}

export interface RealtimeChatRoutingContext {
  model?: string;
  reasoningEffort?: string;
}

export class BridgeRealtime {
  private readonly realtimeSessions = new Map<string, OpenAiRealtimeSession>();

  constructor(private readonly deps: BridgeRealtimeDependencies) {}

  getRealtimeApiKey(deviceId: string): string | undefined {
    return this.realtimeSessions.get(deviceId)?.apiKey;
  }

  getRealtimeLocation(deviceId: string): PhoneLocation | undefined {
    return this.realtimeSessions.get(deviceId)?.location;
  }

  getRealtimeRoutingContext(deviceId: string): RealtimeChatRoutingContext | undefined {
    const session = this.realtimeSessions.get(deviceId);
    if (!session) {
      return undefined;
    }
    return {
      model: session.model,
      reasoningEffort: session.reasoningEffort
    };
  }

  sendRealtime(deviceId: string, message: RealtimeOutboundMessage): void {
    try {
      this.deps.hub.sendRealtime(deviceId, message);
    } catch (error) {
      console.warn(`[realtime] ${deviceId}: failed to send ${message.type}: ${error instanceof Error ? error.message : String(error)}`);
    }
  }

  sendRealtimeError(deviceId: string, message: string): void {
    this.sendRealtime(deviceId, {
      type: "realtime.error",
      deviceId,
      message
    });
  }

  async startRealtimeSession(message: RealtimeStartMessage, registeredDeviceId: string): Promise<void> {
    if (message.deviceId !== registeredDeviceId) {
      this.sendRealtimeError(registeredDeviceId, `realtime.start deviceId ${message.deviceId} does not match registered device ${registeredDeviceId}`);
      return;
    }

    const existing = this.realtimeSessions.get(message.deviceId);
    if (existing) {
      await this.stopRealtimeSession(message.deviceId, "Replaced by newer realtime.start");
    }

    try {
      await this.deps.syncRealtimeChatContext?.(message);
      this.deps.audit.record("openai_realtime_starting", message.deviceId, {
        model: this.deps.config.openAiRealtimeModel,
        chatModel: message.model ?? null,
        reasoningEffort: message.reasoningEffort ?? null,
        voice: this.deps.config.openAiRealtimeVoice,
        sdpLength: message.sdp.length,
        systemPromptChars: message.systemPrompt?.length ?? 0
      });
      const { answerSdp, session } = await this.deps.realtimeClient.start({
        deviceId: message.deviceId,
        sdp: message.sdp,
        systemPrompt: message.systemPrompt,
        apiKey: message.openAiApiKey,
        location: message.location
      });
      this.realtimeSessions.set(message.deviceId, {
        ...session,
        model: trimmedOrUndefined(message.model),
        reasoningEffort: trimmedOrUndefined(message.reasoningEffort)
      });
      this.deps.audit.record("openai_realtime_started", message.deviceId, {
        callId: session.callId ?? null,
        answerSdpLength: answerSdp.length
      });
      this.sendRealtime(message.deviceId, { type: "realtime.sdp", deviceId: message.deviceId, sdp: answerSdp });
    } catch (error) {
      this.realtimeSessions.delete(message.deviceId);
      const errorMessage = error instanceof Error ? error.message : String(error);
      this.deps.audit.record("openai_realtime_error", message.deviceId, { message: errorMessage });
      this.sendRealtimeError(message.deviceId, errorMessage);
    }
  }

  async stopRealtimeSession(deviceId: string, reason = "Stopped by user"): Promise<void> {
    const session = this.realtimeSessions.get(deviceId);
    if (!session) {
      this.sendRealtime(deviceId, { type: "realtime.closed", deviceId, reason });
      return;
    }
    this.realtimeSessions.delete(deviceId);
    await this.deps.realtimeClient.stop(session);
    this.sendRealtime(deviceId, { type: "realtime.closed", deviceId, reason });
  }

  async handleRealtimeStop(message: RealtimeStopMessage, registeredDeviceId: string): Promise<void> {
    if (message.deviceId !== registeredDeviceId) {
      this.sendRealtimeError(registeredDeviceId, `realtime.stop deviceId ${message.deviceId} does not match registered device ${registeredDeviceId}`);
      return;
    }
    await this.stopRealtimeSession(message.deviceId, message.reason ?? "Stopped by Android");
  }

  async handleRealtimeHangUpToolCall(
    message: { deviceId: string; arguments: Record<string, unknown> },
    registeredDeviceId: string
  ): Promise<void> {
    if (message.deviceId !== registeredDeviceId) {
      this.sendRealtimeError(registeredDeviceId, `hang_up_realtime deviceId ${message.deviceId} does not match registered device ${registeredDeviceId}`);
      return;
    }

    const reason = typeof message.arguments.reason === "string" && message.arguments.reason.trim()
      ? message.arguments.reason.trim()
      : "Realtime voice hung up";
    if (message.arguments.stopPhoneTask === true) {
      await this.deps.stopAgentWork(message.deviceId, reason);
    }
    await this.stopRealtimeSession(message.deviceId, reason);
  }

  disconnectDevice(deviceId: string): void {
    const session = this.realtimeSessions.get(deviceId);
    if (!session) {
      return;
    }
    this.realtimeSessions.delete(deviceId);
    this.deps.realtimeClient.stop(session).catch((error) => {
      console.warn(`[realtime] ${deviceId}: failed to stop after disconnect: ${error instanceof Error ? error.message : String(error)}`);
    });
  }
}

function trimmedOrUndefined(value: string | undefined): string | undefined {
  return value?.trim() || undefined;
}
