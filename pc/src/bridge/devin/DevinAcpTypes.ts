import type {
  RequestPermissionRequest,
  RequestPermissionResponse,
  SessionNotification
} from "@agentclientprotocol/sdk";

export type DevinAcpState = "stopped" | "starting" | "ready" | "failed" | "closing";

export type DevinAcpErrorCode =
  | "missing_executable"
  | "spawn_failure"
  | "startup_timeout"
  | "protocol_mismatch"
  | "auth_required"
  | "malformed_transport"
  | "unexpected_exit"
  | "explicit_close"
  | "request_timeout"
  | "capability_unavailable"
  | "not_ready";

export class DevinAcpError extends Error {
  readonly code: DevinAcpErrorCode;

  constructor(code: DevinAcpErrorCode, message: string) {
    super(message);
    this.code = code;
    this.name = "DevinAcpError";
  }
}

export interface DevinAcpAuthMethod {
  readonly id: string;
  readonly name: string;
}

export interface DevinAcpCapabilities {
  readonly protocolVersion: number;
  readonly agentName: string | null;
  readonly agentVersion: string | null;
  readonly loadSession: boolean;
  readonly listSessions: boolean;
  readonly deleteSessions: boolean;
  readonly additionalDirectories: boolean;
  readonly resumeSession: boolean;
  readonly closeSession: boolean;
  readonly forkSession: boolean;
  readonly promptImage: boolean;
  readonly promptAudio: boolean;
  readonly promptEmbeddedContext: boolean;
  readonly authLogout: boolean;
  readonly providers: boolean;
  readonly authMethods: readonly DevinAcpAuthMethod[];
}

export type DevinAcpEvent =
  | { readonly type: "session/update"; readonly notification: SessionNotification }
  | { readonly type: "lifecycle"; readonly state: DevinAcpState; readonly error?: DevinAcpError | null };

export type DevinAcpPermissionHandler = (
  request: RequestPermissionRequest
) => Promise<RequestPermissionResponse>;

export interface DevinAcpProcessExit {
  readonly code: number | null;
  readonly signal: NodeJS.Signals | null;
  readonly spawnError?: Error;
}

export interface DevinAcpProcess {
  readonly command: string;
  readonly executable: string;
  readonly args: readonly string[];
  readonly cwd: string;
  readonly stdin: WritableStream<Uint8Array>;
  readonly stdout: ReadableStream<Uint8Array>;
  readonly stderr: ReadableStream<Uint8Array>;
  readonly exited: Promise<DevinAcpProcessExit>;
  kill(signal?: NodeJS.Signals): void;
}

export interface DevinAcpProcessFactory {
  create(options: {
    command: string;
    executable: string;
    args: readonly string[];
    cwd: string;
  }): DevinAcpProcess | Promise<DevinAcpProcess>;
}

export interface DevinAcpClientOptions {
  command?: string;
  cwd?: string;
  startupTimeoutMs?: number;
  requestTimeoutMs?: number;
  teardownGraceMs?: number;
  processFactory?: DevinAcpProcessFactory;
  onEvent?: (event: DevinAcpEvent) => void;
  onPermission?: DevinAcpPermissionHandler;
}

export interface DevinAcpHealth {
  readonly state: DevinAcpState;
  readonly error: { readonly code: DevinAcpErrorCode; readonly message: string } | null;
  readonly command: string;
  readonly cwd: string;
  readonly agentName: string | null;
  readonly agentVersion: string | null;
  readonly capabilities: DevinAcpCapabilities | null;
  readonly stderr: string;
}
