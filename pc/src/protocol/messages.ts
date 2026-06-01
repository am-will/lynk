import { z } from "zod";

export const PHONE_COMMANDS = [
  "observe_screen",
  "open_app",
  "tap_node",
  "tap_xy",
  "tap_normalized",
  "long_press_node",
  "type_text",
  "submit_text",
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

export const MCP_PHONE_TOOL_NAMES = {
  observe: "phone_observe",
  openApp: "phone_open_app",
  tapNode: "phone_tap_node",
  tapXy: "phone_tap_xy",
  tapNormalized: "phone_tap_normalized",
  longPressNode: "phone_long_press_node",
  typeText: "phone_type_text",
  submitText: "phone_submit_text",
  scroll: "phone_scroll",
  swipe: "phone_swipe",
  pressBack: "phone_press_back",
  pressHome: "phone_press_home",
  openRecents: "phone_open_recents",
  takeScreenshot: "phone_take_screenshot",
  askUserConfirmation: "phone_ask_user_confirmation",
  wait: "phone_wait"
} as const;

export const MCP_PHONE_TOOL_NAME_BY_COMMAND = {
  observe_screen: MCP_PHONE_TOOL_NAMES.observe,
  open_app: MCP_PHONE_TOOL_NAMES.openApp,
  tap_node: MCP_PHONE_TOOL_NAMES.tapNode,
  tap_xy: MCP_PHONE_TOOL_NAMES.tapXy,
  tap_normalized: MCP_PHONE_TOOL_NAMES.tapNormalized,
  long_press_node: MCP_PHONE_TOOL_NAMES.longPressNode,
  type_text: MCP_PHONE_TOOL_NAMES.typeText,
  submit_text: MCP_PHONE_TOOL_NAMES.submitText,
  scroll: MCP_PHONE_TOOL_NAMES.scroll,
  swipe: MCP_PHONE_TOOL_NAMES.swipe,
  press_back: MCP_PHONE_TOOL_NAMES.pressBack,
  press_home: MCP_PHONE_TOOL_NAMES.pressHome,
  open_recents: MCP_PHONE_TOOL_NAMES.openRecents,
  take_screenshot: MCP_PHONE_TOOL_NAMES.takeScreenshot,
  ask_user_confirmation: MCP_PHONE_TOOL_NAMES.askUserConfirmation,
  wait: MCP_PHONE_TOOL_NAMES.wait
} as const satisfies Record<PhoneCommand, string>;

export const REALTIME_TOOL_NAMES = {
  delegateAgentTask: "delegate_agent_task",
  delegateOpenClawTask: "delegate_openclaw_task",
  runPhoneTask: "run_phone_task",
  steerAgentTask: "steer_agent_task",
  steerOpenClawTask: "steer_openclaw_task",
  steerPhoneTask: "steer_phone_task",
  stopAgentTask: "stop_agent_task",
  stopOpenClawTask: "stop_openclaw_task",
  stopPhoneTask: "stop_phone_task",
  hangUpRealtime: "hang_up_realtime",
  webSearch: "web_search"
} as const;

export type RealtimeToolName = typeof REALTIME_TOOL_NAMES[keyof typeof REALTIME_TOOL_NAMES];

export const AGENT_MODEL_IDS = ["gpt-5.5", "gpt-5.4", "gpt-5.4-mini", "gpt-5.3-codex", "gpt-5.2"] as const;
export const REASONING_EFFORTS = ["none", "minimal", "low", "medium", "high", "xhigh"] as const;
export const CHAT_SEND_DELIVERIES = ["normal", "queue", "steer"] as const;
export const CHAT_TASK_KINDS = ["general", "phone"] as const;
export const CHAT_ATTACHMENT_KINDS = ["image", "file"] as const;
export const CHAT_ATTACHMENT_MAX_BYTES = 50 * 1024 * 1024;
const CHAT_ATTACHMENT_MAX_BASE64_CHARS = Math.ceil(CHAT_ATTACHMENT_MAX_BYTES / 3) * 4;
export type ChatTaskKind = typeof CHAT_TASK_KINDS[number];

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

export const selectedChatBackendModelSchema = z.string().min(1).refine((value) => {
  const trimmed = value.trim();
  if (trimmed !== value) {
    return false;
  }
  if ((AGENT_MODEL_IDS as readonly string[]).includes(trimmed) || trimmed === "local-litertlm") {
    return true;
  }
  return /^(hermes|codex|opencode):\S+$/.test(trimmed);
}, "Expected a bare OpenClaw model id, a Hermes/Codex/OpenCode namespaced model id, or local-litertlm");

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
  model: selectedChatBackendModelSchema.optional(),
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
  model: selectedChatBackendModelSchema.optional(),
  reasoningEffort: z.enum(REASONING_EFFORTS).optional(),
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

const chatAttachmentContentBase64Schema = z.string()
  .min(1)
  .max(CHAT_ATTACHMENT_MAX_BASE64_CHARS)
  .refine((value) => /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value), {
    message: "Attachment content must be valid base64."
  })
  .refine((value) => Buffer.from(value, "base64").byteLength <= CHAT_ATTACHMENT_MAX_BYTES, {
    message: `Attachment content must be ${CHAT_ATTACHMENT_MAX_BYTES} bytes or smaller.`
  });

export const chatAttachmentSchema = z.object({
  id: z.string().min(1),
  kind: z.enum(CHAT_ATTACHMENT_KINDS),
  displayName: z.string().min(1),
  mimeType: z.string().min(1),
  sizeBytes: z.number().int().nonnegative().max(CHAT_ATTACHMENT_MAX_BYTES),
  contentBase64: chatAttachmentContentBase64Schema.optional()
});

export const chatSendMessageSchema = z.object({
  type: z.literal("chat.send"),
  deviceId: z.string().min(1),
  text: z.string().default(""),
  sessionKey: z.string().min(1).optional(),
  sessionId: z.string().min(1).optional(),
  model: z.string().min(1).optional(),
  reasoningEffort: z.string().min(1).optional(),
  idempotencyKey: z.string().min(1).optional(),
  delivery: z.enum(CHAT_SEND_DELIVERIES).optional(),
  attachments: z.array(chatAttachmentSchema).optional()
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
  model: z.string().min(1).optional(),
  workspacePath: z.string().min(1).optional(),
  createWorkspaceIfMissing: z.boolean().optional()
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
export type ChatAttachment = z.infer<typeof chatAttachmentSchema>;
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
  harnessId?: string | null;
  harnessLabel?: string | null;
  workspacePath?: string | null;
  workspaceName?: string | null;
  threadPath?: string | null;
  preview?: string | null;
  source?: string | null;
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
  attachments?: ChatAttachment[];
  timestamp?: number | null;
}

export interface ChatModelOption {
  id: string;
  label: string;
  provider?: string | null;
  harnessId?: string | null;
  harnessLabel?: string | null;
  modelId?: string | null;
  contextWindow?: number | null;
  available?: boolean | null;
  reasoningOptions?: ChatReasoningOption[] | null;
  defaultReasoningEffort?: string | null;
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
  harnessId?: string | null;
  harnessLabel?: string | null;
  runId?: string | null;
  isRunning: boolean;
  status?: string | null;
  taskKind?: ChatTaskKind | null;
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
  code?: string | null;
  workspacePath?: string | null;
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
  harnessId?: string | null;
  harnessLabel?: string | null;
  model?: string | null;
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
  actions?: ChatToolAction[];
  raw?: unknown;
}

export interface ChatToolAction {
  id: string;
  label: string;
  command: string;
  args?: Record<string, unknown>;
  style?: "primary" | "secondary" | "danger";
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

export const realtimeSdpMessageSchema = z.object({
  type: z.literal("realtime.sdp"),
  deviceId: z.string().min(1),
  sdp: z.string().min(1)
});

export const realtimeTranscriptDeltaMessageSchema = z.object({
  type: z.literal("realtime.transcript_delta"),
  deviceId: z.string().min(1),
  role: z.string().min(1),
  delta: z.string(),
  text: z.string().optional(),
  isFinal: z.boolean(),
  itemId: z.string().optional().nullable()
});

export const realtimeItemAddedMessageSchema = z.object({
  type: z.literal("realtime.item_added"),
  deviceId: z.string().min(1),
  item: z.unknown()
});

export const realtimeSpeechStartedMessageSchema = z.object({
  type: z.literal("realtime.speech_started"),
  deviceId: z.string().min(1),
  role: z.string().optional(),
  itemId: z.string().optional().nullable()
});

export const realtimeErrorMessageSchema = z.object({
  type: z.literal("realtime.error"),
  deviceId: z.string().min(1),
  message: z.string()
});

export const realtimeClosedMessageSchema = z.object({
  type: z.literal("realtime.closed"),
  deviceId: z.string().min(1),
  reason: z.string().nullable()
});

export const realtimeToolResultMessageSchema = z.object({
  type: z.literal("realtime.tool_result"),
  deviceId: z.string().min(1),
  callId: z.string().min(1),
  ok: z.boolean(),
  output: z.string().optional(),
  error: z.string().optional(),
  status: z.enum(["completed", "failed", "timeout", "cancelled"]),
  createResponse: z.boolean().optional()
});

export const realtimeTaskStatusMessageSchema = z.object({
  type: z.literal("realtime.task_status"),
  deviceId: z.string().min(1),
  running: z.boolean(),
  queued: z.number().int().nonnegative(),
  currentTask: z.string().optional().nullable(),
  completed: z.number().int().nonnegative().optional(),
  failed: z.number().int().nonnegative().optional()
});

export const realtimeOutboundMessageSchema = z.discriminatedUnion("type", [
  realtimeSdpMessageSchema,
  realtimeTranscriptDeltaMessageSchema,
  realtimeItemAddedMessageSchema,
  realtimeSpeechStartedMessageSchema,
  realtimeErrorMessageSchema,
  realtimeClosedMessageSchema,
  realtimeToolResultMessageSchema,
  realtimeTaskStatusMessageSchema
]);

export const chatHistoryMessageSchema = z.object({
  id: z.string().optional().nullable(),
  role: z.string().min(1),
  text: z.string(),
  attachments: z.array(chatAttachmentSchema).optional(),
  timestamp: z.number().optional().nullable()
});

export const chatSessionSummarySchema = z.object({
  key: z.string().min(1),
  sessionId: z.string().optional().nullable(),
  label: z.string().optional().nullable(),
  displayName: z.string().optional().nullable(),
  harnessId: z.string().optional().nullable(),
  harnessLabel: z.string().optional().nullable(),
  workspacePath: z.string().optional().nullable(),
  workspaceName: z.string().optional().nullable(),
  threadPath: z.string().optional().nullable(),
  preview: z.string().optional().nullable(),
  source: z.string().optional().nullable(),
  updatedAt: z.number().optional().nullable(),
  model: z.string().optional().nullable(),
  modelProvider: z.string().optional().nullable(),
  contextTokens: z.number().optional().nullable(),
  inputTokens: z.number().optional().nullable(),
  outputTokens: z.number().optional().nullable(),
  totalTokens: z.number().optional().nullable(),
  estimatedCostUsd: z.number().optional().nullable(),
  fastMode: z.boolean().optional().nullable(),
  hasActiveRun: z.boolean().optional().nullable(),
  thinkingLevel: z.string().optional().nullable(),
  reasoningLevel: z.string().optional().nullable(),
  verboseLevel: z.string().optional().nullable()
});

export const chatReasoningOptionSchema = z.object({
  id: z.string().min(1),
  label: z.string().min(1)
});

export const chatModelOptionSchema = z.object({
  id: z.string().min(1),
  label: z.string().min(1),
  provider: z.string().optional().nullable(),
  harnessId: z.string().optional().nullable(),
  harnessLabel: z.string().optional().nullable(),
  modelId: z.string().optional().nullable(),
  contextWindow: z.number().optional().nullable(),
  available: z.boolean().optional().nullable(),
  reasoningOptions: z.array(chatReasoningOptionSchema).optional().nullable(),
  defaultReasoningEffort: z.string().optional().nullable()
});

export const chatCommandOptionSchema = z.object({
  name: z.string().min(1),
  description: z.string().optional().nullable(),
  category: z.string().optional().nullable(),
  textAliases: z.array(z.string()).optional(),
  source: z.string().optional().nullable(),
  acceptsArgs: z.boolean().optional(),
  args: z.array(z.object({
    name: z.string().min(1),
    description: z.string().optional().nullable(),
    type: z.string().optional().nullable(),
    required: z.boolean().optional().nullable()
  })).optional()
});

export const chatToolSummarySchema = z.object({
  id: z.string().min(1),
  label: z.string().optional().nullable(),
  description: z.string().optional().nullable(),
  source: z.string().optional().nullable(),
  group: z.string().optional().nullable()
});

export const chatUsageSummarySchema = z.object({
  inputTokens: z.number().optional().nullable(),
  outputTokens: z.number().optional().nullable(),
  totalTokens: z.number().optional().nullable(),
  contextTokens: z.number().optional().nullable(),
  estimatedCostUsd: z.number().optional().nullable()
});

export const chatStateMessageSchema = z.object({
  type: z.literal("chat.state"),
  deviceId: z.string().min(1),
  sessionKey: z.string().min(1),
  sessionId: z.string().optional().nullable(),
  harnessId: z.string().optional().nullable(),
  harnessLabel: z.string().optional().nullable(),
  runId: z.string().optional().nullable(),
  isRunning: z.boolean(),
  status: z.string().optional().nullable(),
  taskKind: z.enum(CHAT_TASK_KINDS).optional().nullable(),
  model: z.string().optional().nullable(),
  reasoningEffort: z.string().optional().nullable(),
  reasoningStream: z.boolean().optional().nullable(),
  fastMode: z.boolean().optional().nullable(),
  verboseLevel: z.string().optional().nullable()
});

export const chatHistoryOutboundMessageSchema = z.object({
  type: z.literal("chat.history"),
  deviceId: z.string().min(1),
  sessionKey: z.string().min(1),
  sessionId: z.string().optional().nullable(),
  messages: z.array(chatHistoryMessageSchema)
});

export const chatMessageOutboundMessageSchema = z.object({
  type: z.literal("chat.message"),
  deviceId: z.string().min(1),
  sessionKey: z.string().min(1),
  sessionId: z.string().optional().nullable(),
  message: chatHistoryMessageSchema
});

export const chatDeltaMessageSchema = z.object({
  type: z.literal("chat.delta"),
  deviceId: z.string().min(1),
  sessionKey: z.string().min(1),
  runId: z.string().min(1),
  delta: z.string(),
  replace: z.boolean().optional()
});

export const chatReasoningDeltaMessageSchema = z.object({
  type: z.literal("chat.reasoning_delta"),
  deviceId: z.string().min(1),
  sessionKey: z.string().min(1),
  runId: z.string().min(1),
  delta: z.string(),
  replace: z.boolean().optional()
});

export const chatReasoningClearMessageSchema = z.object({
  type: z.literal("chat.reasoning_clear"),
  deviceId: z.string().min(1),
  sessionKey: z.string().min(1),
  runId: z.string().optional().nullable()
});

export const chatFinalMessageSchema = z.object({
  type: z.literal("chat.final"),
  deviceId: z.string().min(1),
  sessionKey: z.string().min(1),
  runId: z.string().min(1),
  text: z.string(),
  usage: z.unknown().optional()
});

export const chatErrorMessageSchema = z.object({
  type: z.literal("chat.error"),
  deviceId: z.string().min(1),
  sessionKey: z.string().optional(),
  runId: z.string().optional(),
  message: z.string(),
  code: z.string().optional().nullable(),
  workspacePath: z.string().optional().nullable()
});

export const chatReplyAvailableMessageSchema = z.object({
  type: z.literal("chat.reply_available"),
  deviceId: z.string().min(1),
  sessionKey: z.string().min(1),
  runId: z.string().min(1),
  status: z.enum(["completed", "failed"]),
  textPreview: z.string().optional().nullable(),
  sessionId: z.string().optional().nullable(),
  sessionLabel: z.string().optional().nullable(),
  sessionDisplayName: z.string().optional().nullable(),
  harnessId: z.string().optional().nullable(),
  harnessLabel: z.string().optional().nullable(),
  model: z.string().optional().nullable()
});

export const chatToolEventMessageSchema = z.object({
  type: z.literal("chat.tool_event"),
  deviceId: z.string().min(1),
  sessionKey: z.string().min(1),
  runId: z.string().optional().nullable(),
  eventId: z.string().min(1),
  toolName: z.string().min(1),
  title: z.string(),
  status: z.enum(["running", "completed", "failed", "blocked", "info"]),
  summary: z.string().optional().nullable(),
  args: z.unknown().optional(),
  output: z.unknown().optional(),
  error: z.string().optional().nullable(),
  actions: z.array(z.object({
    id: z.string().min(1),
    label: z.string().min(1),
    command: z.string().min(1),
    args: z.record(z.string(), z.unknown()).optional(),
    style: z.enum(["primary", "secondary", "danger"]).optional()
  })).optional(),
  raw: z.unknown().optional()
});

export const chatModelsMessageSchema = z.object({
  type: z.literal("chat.models"),
  deviceId: z.string().min(1),
  models: z.array(chatModelOptionSchema),
  reasoningOptions: z.array(chatReasoningOptionSchema)
});

export const chatCommandsMessageSchema = z.object({
  type: z.literal("chat.commands"),
  deviceId: z.string().min(1),
  commands: z.array(chatCommandOptionSchema)
});

export const chatToolsMessageSchema = z.object({
  type: z.literal("chat.tools"),
  deviceId: z.string().min(1),
  sessionKey: z.string().min(1),
  tools: z.array(chatToolSummarySchema)
});

export const chatSessionsMessageSchema = z.object({
  type: z.literal("chat.sessions"),
  deviceId: z.string().min(1),
  sessions: z.array(chatSessionSummarySchema),
  selectedSessionKey: z.string().min(1)
});

export const chatUsageMessageSchema = z.object({
  type: z.literal("chat.usage"),
  deviceId: z.string().min(1),
  sessionKey: z.string().min(1),
  usage: chatUsageSummarySchema
});

export const chatOutboundMessageSchema = z.discriminatedUnion("type", [
  chatStateMessageSchema,
  chatHistoryOutboundMessageSchema,
  chatMessageOutboundMessageSchema,
  chatDeltaMessageSchema,
  chatReasoningDeltaMessageSchema,
  chatReasoningClearMessageSchema,
  chatFinalMessageSchema,
  chatErrorMessageSchema,
  chatReplyAvailableMessageSchema,
  chatToolEventMessageSchema,
  chatModelsMessageSchema,
  chatCommandsMessageSchema,
  chatToolsMessageSchema,
  chatSessionsMessageSchema,
  chatUsageMessageSchema
]);

export const phoneOutboundMessageSchema = z.union([
  commandMessageSchema,
  agentStatusMessageSchema,
  realtimeOutboundMessageSchema,
  chatOutboundMessageSchema
]);

export function validatePhoneOutboundMessage(message: PhoneOutboundMessage): void {
  if (process.env.NODE_ENV === "production" || process.env.PHONE_AGENT_VALIDATE_OUTBOUND === "0") {
    return;
  }
  const result = phoneOutboundMessageSchema.safeParse(message);
  if (!result.success) {
    const issues = result.error.issues.map((issue) => `${issue.path.join(".") || "<root>"}: ${issue.message}`).join("; ");
    throw new Error(`Invalid outbound phone message: ${issues}`);
  }
}

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
