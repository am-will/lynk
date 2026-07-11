import { WebSocket as WebSocketState, type WebSocket } from "ws";
import { CHAT_ATTACHMENT_MAX_BYTES } from "../protocol/messages.js";

export const PHONE_WEBSOCKET_REGISTRATION_TIMEOUT_MS = 5_000;
export const PHONE_WEBSOCKET_HEARTBEAT_INTERVAL_MS = 30_000;
export const PHONE_WEBSOCKET_REGISTRATION_MAX_BYTES = 16 * 1024;
export const PHONE_WEBSOCKET_CONTROL_FRAME_MAX_BYTES = 256 * 1024;
export const PHONE_WEBSOCKET_MAX_PAYLOAD_BYTES = Math.ceil(CHAT_ATTACHMENT_MAX_BYTES / 3) * 4 + (256 * 1024);
export const PHONE_WEBSOCKET_MAX_CONNECTIONS = 32;

export interface PhoneWebSocketIngressOptions {
  maxPayloadBytes?: number;
  registrationMaxBytes?: number;
  controlFrameMaxBytes?: number;
  registrationTimeoutMs?: number;
  heartbeatIntervalMs?: number;
  maxConnections?: number;
  upgradeRateCapacity?: number;
  upgradeRateRefillMs?: number;
  messageRateCapacity?: number;
  messageRateRefillMs?: number;
  maxTrackedAddresses?: number;
  now?: () => number;
}

export interface ResolvedPhoneWebSocketIngressOptions {
  maxPayloadBytes: number;
  registrationMaxBytes: number;
  controlFrameMaxBytes: number;
  registrationTimeoutMs: number;
  heartbeatIntervalMs: number;
  maxConnections: number;
  upgradeRateCapacity: number;
  upgradeRateRefillMs: number;
  messageRateCapacity: number;
  messageRateRefillMs: number;
  maxTrackedAddresses: number;
  now: () => number;
}

export function resolvePhoneWebSocketIngressOptions(
  options: PhoneWebSocketIngressOptions = {}
): ResolvedPhoneWebSocketIngressOptions {
  const resolved: ResolvedPhoneWebSocketIngressOptions = {
    maxPayloadBytes: options.maxPayloadBytes ?? PHONE_WEBSOCKET_MAX_PAYLOAD_BYTES,
    registrationMaxBytes: options.registrationMaxBytes ?? PHONE_WEBSOCKET_REGISTRATION_MAX_BYTES,
    controlFrameMaxBytes: options.controlFrameMaxBytes ?? PHONE_WEBSOCKET_CONTROL_FRAME_MAX_BYTES,
    registrationTimeoutMs: options.registrationTimeoutMs ?? PHONE_WEBSOCKET_REGISTRATION_TIMEOUT_MS,
    heartbeatIntervalMs: options.heartbeatIntervalMs ?? PHONE_WEBSOCKET_HEARTBEAT_INTERVAL_MS,
    maxConnections: options.maxConnections ?? PHONE_WEBSOCKET_MAX_CONNECTIONS,
    upgradeRateCapacity: options.upgradeRateCapacity ?? 12,
    upgradeRateRefillMs: options.upgradeRateRefillMs ?? 5_000,
    messageRateCapacity: options.messageRateCapacity ?? 200,
    messageRateRefillMs: options.messageRateRefillMs ?? 50,
    maxTrackedAddresses: options.maxTrackedAddresses ?? 1_024,
    now: options.now ?? Date.now
  };

  for (const [name, value] of Object.entries(resolved)) {
    if (name !== "now" && (!Number.isSafeInteger(value) || (value as number) <= 0)) {
      throw new Error(`${name} must be a positive integer.`);
    }
  }
  if (resolved.registrationMaxBytes > resolved.maxPayloadBytes) {
    throw new Error("registrationMaxBytes cannot exceed maxPayloadBytes.");
  }
  if (resolved.controlFrameMaxBytes > resolved.maxPayloadBytes) {
    throw new Error("controlFrameMaxBytes cannot exceed maxPayloadBytes.");
  }
  return resolved;
}

export class WebSocketAdmissionBudget {
  private readonly buckets = new Map<string, AddressBucket>();

  constructor(private readonly options: ResolvedPhoneWebSocketIngressOptions) {}

  allow(remoteAddress: string | undefined, activeConnections: number): boolean {
    if (activeConnections >= this.options.maxConnections) {
      return false;
    }

    const key = remoteAddress || "<unknown>";
    const now = this.options.now();
    let bucket = this.buckets.get(key);
    if (!bucket) {
      this.makeAddressRoom();
      bucket = {
        tokens: this.options.upgradeRateCapacity,
        lastRefillAt: now,
        lastSeenAt: now
      };
      this.buckets.set(key, bucket);
    }
    refill(bucket, now, this.options.upgradeRateCapacity, this.options.upgradeRateRefillMs);
    bucket.lastSeenAt = now;
    return consume(bucket);
  }

  private makeAddressRoom(): void {
    if (this.buckets.size < this.options.maxTrackedAddresses) {
      return;
    }
    let oldestKey: string | undefined;
    let oldestSeenAt = Number.POSITIVE_INFINITY;
    for (const [key, bucket] of this.buckets) {
      if (bucket.lastSeenAt < oldestSeenAt) {
        oldestKey = key;
        oldestSeenAt = bucket.lastSeenAt;
      }
    }
    if (oldestKey) {
      this.buckets.delete(oldestKey);
    }
  }
}

export class WebSocketMessageBudget {
  private readonly bucket: AddressBucket;

  constructor(private readonly options: ResolvedPhoneWebSocketIngressOptions) {
    const now = options.now();
    this.bucket = {
      tokens: options.messageRateCapacity,
      lastRefillAt: now,
      lastSeenAt: now
    };
  }

  allow(): boolean {
    const now = this.options.now();
    refill(this.bucket, now, this.options.messageRateCapacity, this.options.messageRateRefillMs);
    this.bucket.lastSeenAt = now;
    return consume(this.bucket);
  }
}

interface HeartbeatSocket extends Pick<WebSocket, "on" | "ping" | "readyState" | "terminate"> {}

export class WebSocketHeartbeat {
  private readonly responsive = new WeakSet<HeartbeatSocket>();

  attach(socket: HeartbeatSocket): void {
    this.responsive.add(socket);
    socket.on("pong", () => this.responsive.add(socket));
  }

  sweep(sockets: Iterable<HeartbeatSocket>): void {
    for (const socket of sockets) {
      if (socket.readyState !== WebSocketState.OPEN) {
        continue;
      }
      if (!this.responsive.has(socket)) {
        safelyTerminate(socket);
        continue;
      }
      this.responsive.delete(socket);
      try {
        socket.ping();
      } catch {
        safelyTerminate(socket);
      }
    }
  }
}

interface AddressBucket {
  tokens: number;
  lastRefillAt: number;
  lastSeenAt: number;
}

function refill(bucket: AddressBucket, now: number, capacity: number, refillMs: number): void {
  const elapsed = Math.max(0, now - bucket.lastRefillAt);
  bucket.tokens = Math.min(capacity, bucket.tokens + (elapsed / refillMs));
  bucket.lastRefillAt = now;
}

function consume(bucket: AddressBucket): boolean {
  if (bucket.tokens < 1) {
    return false;
  }
  bucket.tokens -= 1;
  return true;
}

function safelyTerminate(socket: HeartbeatSocket): void {
  try {
    socket.terminate();
  } catch {
    // A concurrent close already owns cleanup.
  }
}
