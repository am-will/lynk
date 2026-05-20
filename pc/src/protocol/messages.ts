import { z } from "zod";

export const PHONE_COMMANDS = [
  "observe_screen",
  "open_app",
  "tap_node",
  "tap_xy",
  "tap_normalized",
  "long_press_node",
  "type_text",
  "scroll",
  "swipe",
  "press_back",
  "press_home",
  "open_recents",
  "take_screenshot",
  "ask_user_confirmation",
  "wait"
] as const;

export const phoneCommandSchema = z.enum(PHONE_COMMANDS);
export type PhoneCommand = z.infer<typeof phoneCommandSchema>;

export const AGENT_MODEL_IDS = ["gpt-5.5", "gpt-5.4", "gpt-5.4-mini", "gpt-5.3-codex", "gpt-5.2"] as const;
export const REASONING_EFFORTS = ["low", "medium", "high", "xhigh"] as const;

export const registerMessageSchema = z.object({
  type: z.literal("register"),
  deviceId: z.string().min(1),
  token: z.string().min(1),
  capabilities: z.array(z.string())
});

export const commandMessageSchema = z.object({
  id: z.string().min(1),
  type: z.literal("command"),
  command: phoneCommandSchema,
  args: z.record(z.string(), z.unknown()).default({})
});

export const resultMessageSchema = z.object({
  id: z.string().min(1),
  type: z.literal("result"),
  ok: z.boolean(),
  observation: z.unknown().optional().nullable(),
  screenshotBase64: z.string().optional().nullable(),
  screenshot: z
    .object({
      widthPx: z.number().int().positive(),
      heightPx: z.number().int().positive()
    })
    .optional()
    .nullable(),
  error: z.string().optional().nullable()
});

export const userRequestMessageSchema = z.object({
  type: z.literal("user_request"),
  deviceId: z.string().min(1),
  inputType: z.literal("text"),
  text: z.string().min(1),
  systemPrompt: z.string().optional(),
  model: z.enum(AGENT_MODEL_IDS).optional(),
  reasoningEffort: z.enum(REASONING_EFFORTS).optional()
});

export const phoneLocationSchema = z.object({
  latitude: z.number().min(-90).max(90),
  longitude: z.number().min(-180).max(180),
  accuracyMeters: z.number().nonnegative().optional(),
  provider: z.string().optional(),
  capturedAtMs: z.number().int().positive().optional()
});

export const realtimeStartMessageSchema = z.object({
  type: z.literal("realtime.start"),
  deviceId: z.string().min(1),
  sdp: z.string().min(1),
  systemPrompt: z.string().optional(),
  model: z.enum(AGENT_MODEL_IDS).optional(),
  reasoningEffort: z.enum(REASONING_EFFORTS).optional(),
  openAiApiKey: z.string().optional(),
  location: phoneLocationSchema.optional()
});

export const realtimeStopMessageSchema = z.object({
  type: z.literal("realtime.stop"),
  deviceId: z.string().min(1),
  reason: z.string().optional()
});

export const realtimeToolCallMessageSchema = z.object({
  type: z.literal("realtime.tool_call"),
  deviceId: z.string().min(1),
  callId: z.string().min(1),
  itemId: z.string().optional().nullable(),
  name: z.string().min(1),
  arguments: z.record(z.string(), z.unknown()).default({})
});

export const agentStatusMessageSchema = z.object({
  type: z.literal("agent_status"),
  deviceId: z.string().optional(),
  status: z.enum(["info", "working", "tool", "done", "error"]),
  text: z.string()
});

export const agentControlMessageSchema = z.object({
  type: z.literal("agent_control"),
  deviceId: z.string().min(1),
  action: z.literal("stop"),
  reason: z.string().optional()
});

export const chatOpenMessageSchema = z.object({
  type: z.literal("chat.open"),
  deviceId: z.string().min(1),
  sessionKey: z.string().min(1).optional()
});

export const chatSendMessageSchema = z.object({
  type: z.literal("chat.send"),
  deviceId: z.string().min(1),
  text: z.string().min(1),
  sessionKey: z.string().min(1).optional(),
  sessionId: z.string().min(1).optional(),
  model: z.string().min(1).optional(),
  reasoningEffort: z.string().min(1).optional(),
  idempotencyKey: z.string().min(1).optional()
});

export const chatStopMessageSchema = z.object({
  type: z.literal("chat.stop"),
  deviceId: z.string().min(1),
  sessionKey: z.string().min(1).optional(),
  runId: z.string().min(1).optional(),
  reason: z.string().optional()
});

export const chatSelectSessionMessageSchema = z.object({
  type: z.literal("chat.select_session"),
  deviceId: z.string().min(1),
  sessionKey: z.string().min(1)
});

export const chatNewSessionMessageSchema = z.object({
  type: z.literal("chat.new_session"),
  deviceId: z.string().min(1),
  key: z.string().min(1).optional(),
  label: z.string().min(1).optional(),
  model: z.string().min(1).optional()
});

export const chatSetModelMessageSchema = z.object({
  type: z.literal("chat.set_model"),
  deviceId: z.string().min(1),
  sessionKey: z.string().min(1).optional(),
  model: z.string().min(1)
});

export const chatSetReasoningMessageSchema = z.object({
  type: z.literal("chat.set_reasoning"),
  deviceId: z.string().min(1),
  sessionKey: z.string().min(1).optional(),
  reasoningEffort: z.string().min(1)
});

export const chatControlCommandMessageSchema = z.object({
  type: z.literal("chat.control_command"),
  deviceId: z.string().min(1),
  command: z.string().min(1),
  args: z.record(z.string(), z.unknown()).default({})
});

export const inboundPhoneMessageSchema = z.discriminatedUnion("type", [
  registerMessageSchema,
  resultMessageSchema,
  userRequestMessageSchema,
  agentControlMessageSchema,
  chatOpenMessageSchema,
  chatSendMessageSchema,
  chatStopMessageSchema,
  chatSelectSessionMessageSchema,
  chatNewSessionMessageSchema,
  chatSetModelMessageSchema,
  chatSetReasoningMessageSchema,
  chatControlCommandMessageSchema,
  realtimeStartMessageSchema,
  realtimeStopMessageSchema,
  realtimeToolCallMessageSchema
]);

export type RegisterMessage = z.infer<typeof registerMessageSchema>;
export type CommandMessage = z.infer<typeof commandMessageSchema>;
export type ResultMessage = z.infer<typeof resultMessageSchema>;
export type UserRequestMessage = z.infer<typeof userRequestMessageSchema>;
export type PhoneLocation = z.infer<typeof phoneLocationSchema>;
export type RealtimeStartMessage = z.infer<typeof realtimeStartMessageSchema>;
export type RealtimeStopMessage = z.infer<typeof realtimeStopMessageSchema>;
export type RealtimeToolCallMessage = z.infer<typeof realtimeToolCallMessageSchema>;
export type AgentStatusMessage = z.infer<typeof agentStatusMessageSchema>;
export type AgentControlMessage = z.infer<typeof agentControlMessageSchema>;
export type ChatOpenMessage = z.infer<typeof chatOpenMessageSchema>;
export type ChatSendMessage = z.infer<typeof chatSendMessageSchema>;
export type ChatStopMessage = z.infer<typeof chatStopMessageSchema>;
export type ChatSelectSessionMessage = z.infer<typeof chatSelectSessionMessageSchema>;
export type ChatNewSessionMessage = z.infer<typeof chatNewSessionMessageSchema>;
export type ChatSetModelMessage = z.infer<typeof chatSetModelMessageSchema>;
export type ChatSetReasoningMessage = z.infer<typeof chatSetReasoningMessageSchema>;
export type ChatControlCommandMessage = z.infer<typeof chatControlCommandMessageSchema>;

export interface RealtimeSdpMessage {
  type: "realtime.sdp";
  deviceId: string;
  sdp: string;
}

export interface RealtimeTranscriptDeltaMessage {
  type: "realtime.transcript_delta";
  deviceId: string;
  role: string;
  delta: string;
  text?: string;
  isFinal: boolean;
  itemId?: string | null;
}

export interface RealtimeItemAddedMessage {
  type: "realtime.item_added";
  deviceId: string;
  item: unknown;
}

export interface RealtimeSpeechStartedMessage {
  type: "realtime.speech_started";
  deviceId: string;
  role?: string;
  itemId?: string | null;
}

export interface RealtimeErrorMessage {
  type: "realtime.error";
  deviceId: string;
  message: string;
}

export interface RealtimeClosedMessage {
  type: "realtime.closed";
  deviceId: string;
  reason: string | null;
}

export interface RealtimeToolResultMessage {
  type: "realtime.tool_result";
  deviceId: string;
  callId: string;
  ok: boolean;
  output?: string;
  error?: string;
  status: "completed" | "failed" | "timeout" | "cancelled";
  createResponse?: boolean;
}

export interface RealtimeTaskStatusMessage {
  type: "realtime.task_status";
  deviceId: string;
  running: boolean;
  queued: number;
  currentTask?: string | null;
  completed?: number;
  failed?: number;
}

export type RealtimeOutboundMessage =
  | RealtimeSdpMessage
  | RealtimeTranscriptDeltaMessage
  | RealtimeItemAddedMessage
  | RealtimeSpeechStartedMessage
  | RealtimeErrorMessage
  | RealtimeClosedMessage
  | RealtimeToolResultMessage
  | RealtimeTaskStatusMessage;

export interface ChatSessionSummary {
  key: string;
  sessionId?: string | null;
  label?: string | null;
  displayName?: string | null;
  updatedAt?: number | null;
  model?: string | null;
  modelProvider?: string | null;
  contextTokens?: number | null;
  inputTokens?: number | null;
  outputTokens?: number | null;
  totalTokens?: number | null;
  estimatedCostUsd?: number | null;
  fastMode?: boolean | null;
  hasActiveRun?: boolean | null;
  thinkingLevel?: string | null;
  reasoningLevel?: string | null;
  verboseLevel?: string | null;
}

export interface ChatHistoryMessage {
  id?: string | null;
  role: string;
  text: string;
  timestamp?: number | null;
}

export interface ChatModelOption {
  id: string;
  label: string;
  provider?: string | null;
  contextWindow?: number | null;
  available?: boolean | null;
}

export interface ChatReasoningOption {
  id: string;
  label: string;
}

export interface ChatCommandOption {
  name: string;
  description?: string | null;
  category?: string | null;
  textAliases?: string[];
  source?: string | null;
  acceptsArgs?: boolean;
  args?: Array<{
    name: string;
    description?: string | null;
    type?: string | null;
    required?: boolean | null;
  }>;
}

export interface ChatToolSummary {
  id: string;
  label?: string | null;
  description?: string | null;
  source?: string | null;
  group?: string | null;
}

export interface ChatUsageSummary {
  inputTokens?: number | null;
  outputTokens?: number | null;
  totalTokens?: number | null;
  contextTokens?: number | null;
  estimatedCostUsd?: number | null;
}

export interface ChatStateMessage {
  type: "chat.state";
  deviceId: string;
  sessionKey: string;
  sessionId?: string | null;
  runId?: string | null;
  isRunning: boolean;
  status?: string | null;
  model?: string | null;
  reasoningEffort?: string | null;
  reasoningStream?: boolean | null;
  fastMode?: boolean | null;
  verboseLevel?: string | null;
}

export interface ChatHistoryOutboundMessage {
  type: "chat.history";
  deviceId: string;
  sessionKey: string;
  sessionId?: string | null;
  messages: ChatHistoryMessage[];
}

export interface ChatMessageOutboundMessage {
  type: "chat.message";
  deviceId: string;
  sessionKey: string;
  sessionId?: string | null;
  message: ChatHistoryMessage;
}

export interface ChatDeltaMessage {
  type: "chat.delta";
  deviceId: string;
  sessionKey: string;
  runId: string;
  delta: string;
  replace?: boolean;
}

export interface ChatReasoningDeltaMessage {
  type: "chat.reasoning_delta";
  deviceId: string;
  sessionKey: string;
  runId: string;
  delta: string;
  replace?: boolean;
}

export interface ChatReasoningClearMessage {
  type: "chat.reasoning_clear";
  deviceId: string;
  sessionKey: string;
  runId?: string | null;
}

export interface ChatFinalMessage {
  type: "chat.final";
  deviceId: string;
  sessionKey: string;
  runId: string;
  text: string;
  usage?: unknown;
}

export interface ChatErrorMessage {
  type: "chat.error";
  deviceId: string;
  sessionKey?: string;
  runId?: string;
  message: string;
}

export interface ChatReplyAvailableMessage {
  type: "chat.reply_available";
  deviceId: string;
  sessionKey: string;
  runId: string;
  status: "completed" | "failed";
  textPreview?: string | null;
  sessionId?: string | null;
  sessionLabel?: string | null;
  sessionDisplayName?: string | null;
}

export interface ChatToolEventMessage {
  type: "chat.tool_event";
  deviceId: string;
  sessionKey: string;
  runId?: string | null;
  eventId: string;
  toolName: string;
  title: string;
  status: "running" | "completed" | "failed" | "blocked" | "info";
  summary?: string | null;
  args?: unknown;
  output?: unknown;
  error?: string | null;
  raw?: unknown;
}

export interface ChatModelsMessage {
  type: "chat.models";
  deviceId: string;
  models: ChatModelOption[];
  reasoningOptions: ChatReasoningOption[];
}

export interface ChatCommandsMessage {
  type: "chat.commands";
  deviceId: string;
  commands: ChatCommandOption[];
}

export interface ChatToolsMessage {
  type: "chat.tools";
  deviceId: string;
  sessionKey: string;
  tools: ChatToolSummary[];
}

export interface ChatSessionsMessage {
  type: "chat.sessions";
  deviceId: string;
  sessions: ChatSessionSummary[];
  selectedSessionKey: string;
}

export interface ChatUsageMessage {
  type: "chat.usage";
  deviceId: string;
  sessionKey: string;
  usage: ChatUsageSummary;
}

export type ChatOutboundMessage =
  | ChatStateMessage
  | ChatHistoryOutboundMessage
  | ChatMessageOutboundMessage
  | ChatDeltaMessage
  | ChatReasoningDeltaMessage
  | ChatReasoningClearMessage
  | ChatFinalMessage
  | ChatErrorMessage
  | ChatReplyAvailableMessage
  | ChatToolEventMessage
  | ChatModelsMessage
  | ChatCommandsMessage
  | ChatToolsMessage
  | ChatSessionsMessage
  | ChatUsageMessage;

export type PhoneOutboundMessage = CommandMessage | AgentStatusMessage | RealtimeOutboundMessage | ChatOutboundMessage;

export interface PhoneCommandRequest {
  deviceId?: string;
  command: PhoneCommand;
  args?: Record<string, unknown>;
  timeoutMs?: number;
}

export interface PhoneCommandResult {
  id: string;
  deviceId: string;
  ok: boolean;
  observation?: unknown;
  screenshotBase64?: string | null;
  screenshot?: {
    widthPx: number;
    heightPx: number;
  } | null;
  error?: string | null;
}

export const DEFAULT_TIMEOUT_MS = 30_000;

export function newCommandId(): string {
  return `cmd_${Date.now()}_${Math.random().toString(16).slice(2)}`;
}
