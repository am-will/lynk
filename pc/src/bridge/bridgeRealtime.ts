import type { AuditLog } from "./AuditLog.js";
import type { OpenAiRealtimeClient, OpenAiRealtimeSession } from "./OpenAiRealtimeClient.js";
import type { BridgeConfig } from "./config.js";
import type { PhoneHub } from "./PhoneHub.js";
import type {
  PhoneLocation,
  RealtimeOutboundMessage,
  RealtimeStartMessage,
  RealtimeStopMessage,
  RealtimeToolCallMessage
} from "../protocol/messages.js";

export interface BridgeRealtimeDependencies {
  config: Pick<BridgeConfig, "openAiRealtimeModel" | "openAiRealtimeVoice">;
  hub: Pick<PhoneHub, "sendRealtime">;
  audit: Pick<AuditLog, "record">;
  realtimeClient: Pick<OpenAiRealtimeClient, "start" | "stop">;
  stopAgentWork: (deviceId: string, voiceSessionId: string, reason: string) => Promise<void>;
  detachAgentWork: (deviceId: string, voiceSessionId: string) => void;
}

interface RealtimeOwner {
  voiceSessionId: string;
  generation: number;
  session?: OpenAiRealtimeSession;
}

export class BridgeRealtime {
  private readonly realtimeSessions = new Map<string, RealtimeOwner>();
  private nextGeneration = 0;

  constructor(private readonly deps: BridgeRealtimeDependencies) {}

  owns(deviceId: string, voiceSessionId: string): boolean {
    return this.realtimeSessions.get(deviceId)?.voiceSessionId === voiceSessionId;
  }

  getRealtimeApiKey(deviceId: string, voiceSessionId: string): string | undefined {
    const owner = this.owner(deviceId, voiceSessionId);
    return owner?.session?.apiKey;
  }

  getRealtimeLocation(deviceId: string, voiceSessionId: string): PhoneLocation | undefined {
    const owner = this.owner(deviceId, voiceSessionId);
    return owner?.session?.location;
  }

  sendRealtime(deviceId: string, message: RealtimeOutboundMessage): void {
    try {
      this.deps.hub.sendRealtime(deviceId, message);
    } catch (error) {
      console.warn(`[realtime] ${deviceId}: failed to send ${message.type}: ${error instanceof Error ? error.message : String(error)}`);
    }
  }

  sendRealtimeError(deviceId: string, voiceSessionId: string, message: string): void {
    this.sendRealtime(deviceId, { type: "realtime.error", deviceId, voiceSessionId, message });
  }

  async startRealtimeSession(message: RealtimeStartMessage, registeredDeviceId: string): Promise<void> {
    if (message.deviceId !== registeredDeviceId) {
      this.sendRealtimeError(registeredDeviceId, message.voiceSessionId, `realtime.start deviceId ${message.deviceId} does not match registered device ${registeredDeviceId}`);
      return;
    }

    const prior = this.realtimeSessions.get(message.deviceId);
    const owner: RealtimeOwner = { voiceSessionId: message.voiceSessionId, generation: ++this.nextGeneration };
    this.realtimeSessions.set(message.deviceId, owner);
    if (prior) await this.deps.stopAgentWork(message.deviceId, prior.voiceSessionId, "Replaced by newer realtime.start");
    if (prior?.session) {
      await this.deps.realtimeClient.stop(prior.session);
      this.sendRealtime(message.deviceId, {
        type: "realtime.closed",
        deviceId: message.deviceId,
        voiceSessionId: prior.voiceSessionId,
        reason: "Replaced by newer realtime.start"
      });
    }

    try {
      this.deps.audit.record("openai_realtime_starting", message.deviceId, {
        voiceSessionId: message.voiceSessionId,
        model: this.deps.config.openAiRealtimeModel,
        chatModel: message.model ?? null,
        reasoningEffort: message.reasoningEffort ?? null,
        voice: this.deps.config.openAiRealtimeVoice,
        sdpLength: message.sdp.length,
        systemPromptChars: message.systemPrompt?.length ?? 0
      });
      const started = await this.deps.realtimeClient.start({
        deviceId: message.deviceId,
        sdp: message.sdp,
        systemPrompt: message.systemPrompt,
        apiKey: message.openAiApiKey,
        location: message.location
      });
      if (!this.isOwner(message.deviceId, owner)) {
        await this.deps.realtimeClient.stop(started.session);
        return;
      }
      owner.session = started.session;
      this.deps.audit.record("openai_realtime_started", message.deviceId, {
        voiceSessionId: message.voiceSessionId,
        callId: started.session.callId ?? null,
        answerSdpLength: started.answerSdp.length
      });
      this.sendRealtime(message.deviceId, {
        type: "realtime.sdp",
        deviceId: message.deviceId,
        voiceSessionId: message.voiceSessionId,
        sdp: started.answerSdp
      });
    } catch (error) {
      if (!this.isOwner(message.deviceId, owner)) return;
      this.realtimeSessions.delete(message.deviceId);
      const errorMessage = error instanceof Error ? error.message : String(error);
      this.deps.audit.record("openai_realtime_error", message.deviceId, { voiceSessionId: message.voiceSessionId, message: errorMessage });
      this.sendRealtimeError(message.deviceId, message.voiceSessionId, errorMessage);
    }
  }

  async stopRealtimeSession(deviceId: string, voiceSessionId: string, reason = "Stopped by user"): Promise<boolean> {
    const owner = this.owner(deviceId, voiceSessionId);
    if (!owner) {
      this.sendRealtimeError(deviceId, voiceSessionId, "Realtime voice session is no longer active.");
      return false;
    }
    this.realtimeSessions.delete(deviceId);
    this.deps.detachAgentWork(deviceId, voiceSessionId);
    if (owner.session) await this.deps.realtimeClient.stop(owner.session);
    this.sendRealtime(deviceId, { type: "realtime.closed", deviceId, voiceSessionId, reason });
    return true;
  }

  async handleRealtimeStop(message: RealtimeStopMessage, registeredDeviceId: string): Promise<void> {
    if (message.deviceId !== registeredDeviceId) {
      this.sendRealtimeError(registeredDeviceId, message.voiceSessionId, `realtime.stop deviceId ${message.deviceId} does not match registered device ${registeredDeviceId}`);
      return;
    }
    await this.stopRealtimeSession(message.deviceId, message.voiceSessionId, message.reason ?? "Stopped by Android");
  }

  async handleRealtimeHangUpToolCall(message: RealtimeToolCallMessage, registeredDeviceId: string): Promise<void> {
    if (message.deviceId !== registeredDeviceId) {
      this.sendRealtimeError(registeredDeviceId, message.voiceSessionId, `hang_up_realtime deviceId ${message.deviceId} does not match registered device ${registeredDeviceId}`);
      return;
    }
    if (!this.owns(message.deviceId, message.voiceSessionId)) {
      this.sendRealtimeError(message.deviceId, message.voiceSessionId, "Realtime voice session is no longer active.");
      return;
    }
    const reason = typeof message.arguments.reason === "string" && message.arguments.reason.trim()
      ? message.arguments.reason.trim()
      : "Realtime voice hung up";
    if (message.arguments.stopPhoneTask === true) {
      await this.deps.stopAgentWork(message.deviceId, message.voiceSessionId, reason);
    }
    await this.stopRealtimeSession(message.deviceId, message.voiceSessionId, reason);
  }

  disconnectDevice(deviceId: string): void {
    const owner = this.realtimeSessions.get(deviceId);
    if (!owner) return;
    this.realtimeSessions.delete(deviceId);
    this.deps.detachAgentWork(deviceId, owner.voiceSessionId);
    if (owner.session) {
      this.deps.realtimeClient.stop(owner.session).catch((error) => {
        console.warn(`[realtime] ${deviceId}: failed to stop after disconnect: ${error instanceof Error ? error.message : String(error)}`);
      });
    }
  }

  private owner(deviceId: string, voiceSessionId: string): RealtimeOwner | undefined {
    const owner = this.realtimeSessions.get(deviceId);
    return owner?.voiceSessionId === voiceSessionId ? owner : undefined;
  }

  private isOwner(deviceId: string, candidate: RealtimeOwner): boolean {
    const owner = this.realtimeSessions.get(deviceId);
    return owner?.generation === candidate.generation && owner.voiceSessionId === candidate.voiceSessionId;
  }
}
