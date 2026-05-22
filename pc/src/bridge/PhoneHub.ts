import { WebSocket } from "ws";
import {
  DEFAULT_TIMEOUT_MS,
  type AgentStatusMessage,
  type ChatOutboundMessage,
  type CommandMessage,
  type PhoneOutboundMessage,
  type PhoneCommandRequest,
  type PhoneCommandResult,
  type RegisterMessage,
  type RealtimeOutboundMessage,
  type ResultMessage,
  newCommandId,
  validatePhoneOutboundMessage
} from "../protocol/messages.js";
import type { AuditLog } from "./AuditLog.js";

interface ConnectedPhone {
  registration: RegisterMessage;
  socket: WebSocket;
  connectedAt: number;
}

interface PendingCommand {
  deviceId: string;
  resolve: (result: PhoneCommandResult) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
}

export class PhoneHub {
  private readonly phones = new Map<string, ConnectedPhone>();
  private readonly pending = new Map<string, PendingCommand>();

  constructor(
    private readonly defaultDeviceId: string,
    private readonly audit?: AuditLog
  ) {}

  register(registration: RegisterMessage, socket: WebSocket): void {
    const existing = this.phones.get(registration.deviceId);
    existing?.socket.close(4000, "replaced by newer connection");
    this.phones.set(registration.deviceId, {
      registration,
      socket,
      connectedAt: Date.now()
    });
  }

  unregister(socket: WebSocket): void {
    for (const [deviceId, phone] of this.phones) {
      if (phone.socket === socket) {
        this.phones.delete(deviceId);
        for (const [id, pending] of this.pending) {
          if (pending.deviceId === deviceId) {
            clearTimeout(pending.timer);
            pending.reject(new Error(`Phone ${deviceId} disconnected while command ${id} was pending`));
            this.pending.delete(id);
          }
        }
      }
    }
  }

  listPhones(): Array<RegisterMessage & { connectedAt: number }> {
    return [...this.phones.values()].map((phone) => ({
      ...phone.registration,
      connectedAt: phone.connectedAt
    }));
  }

  getDefaultDeviceId(deviceId?: string): string {
    return deviceId ?? this.defaultDeviceId;
  }

  sendMessage(deviceId: string, message: PhoneOutboundMessage): void {
    const phone = this.phones.get(deviceId);
    if (!phone || phone.socket.readyState !== WebSocket.OPEN) {
      throw new Error(`Phone ${deviceId} is not connected`);
    }
    validatePhoneOutboundMessage(message);
    phone.socket.send(JSON.stringify(message));
  }

  async sendCommand(request: PhoneCommandRequest): Promise<PhoneCommandResult> {
    const deviceId = this.getDefaultDeviceId(request.deviceId);
    const phone = this.phones.get(deviceId);
    if (!phone || phone.socket.readyState !== WebSocket.OPEN) {
      throw new Error(`Phone ${deviceId} is not connected`);
    }

    const id = newCommandId();
    const message: CommandMessage = {
      id,
      type: "command",
      command: request.command,
      args: request.args ?? {}
    };
    validatePhoneOutboundMessage(message);

    const timeoutMs = request.timeoutMs ?? DEFAULT_TIMEOUT_MS;
    this.audit?.record("phone_command_sent", deviceId, {
      id,
      command: request.command,
      args: request.args ?? {},
      timeoutMs
    });
    return await new Promise<PhoneCommandResult>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        this.audit?.record("phone_command_timeout", deviceId, { id, command: request.command, timeoutMs });
        reject(new Error(`Timed out waiting ${timeoutMs}ms for ${request.command} on ${deviceId}`));
      }, timeoutMs);

      this.pending.set(id, { deviceId, resolve, reject, timer });
      phone.socket.send(JSON.stringify(message), (error) => {
        if (error) {
          clearTimeout(timer);
          this.pending.delete(id);
          reject(error);
        }
      });
    });
  }

  cancelPendingCommands(deviceId: string, reason: string): void {
    for (const [id, pending] of this.pending) {
      if (pending.deviceId !== deviceId) {
        continue;
      }
      clearTimeout(pending.timer);
      this.pending.delete(id);
      this.audit?.record("phone_command_cancelled", deviceId, { id, reason });
      pending.reject(new Error(reason));
    }
  }

  sendRealtime(deviceId: string, message: RealtimeOutboundMessage): void {
    this.sendMessage(deviceId, message);
  }

  sendChat(deviceId: string, message: ChatOutboundMessage): void {
    this.sendMessage(deviceId, message);
  }

  handleResult(deviceId: string, result: ResultMessage): void {
    const pending = this.pending.get(result.id);
    if (!pending) {
      return;
    }
    clearTimeout(pending.timer);
    this.pending.delete(result.id);
    this.audit?.record("phone_command_result", deviceId, {
      id: result.id,
      ok: result.ok,
      observation: result.observation,
      screenshotBase64: result.screenshotBase64 ?? null,
      screenshot: result.screenshot ?? null,
      error: result.error ?? null
    });
    pending.resolve({
      id: result.id,
      deviceId,
      ok: result.ok,
      observation: result.observation,
      screenshotBase64: result.screenshotBase64 ?? null,
      screenshot: result.screenshot ?? null,
      error: result.error ?? null
    });
  }

  sendStatus(deviceId: string | undefined, message: Omit<AgentStatusMessage, "type">): void {
    const targets = deviceId ? [this.phones.get(deviceId)].filter(Boolean) : [...this.phones.values()];
    for (const phone of targets) {
      if (phone && phone.socket.readyState === WebSocket.OPEN) {
        const statusMessage = {
          type: "agent_status",
          ...message
        } satisfies AgentStatusMessage;
        validatePhoneOutboundMessage(statusMessage);
        phone.socket.send(
          JSON.stringify(statusMessage)
        );
      }
    }
  }
}
