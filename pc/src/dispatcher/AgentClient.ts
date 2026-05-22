export interface AgentStatusSink {
  info(text: string): void;
  working(text: string): void;
  tool(text: string): void;
  done(text: string): void;
  error(text: string): void;
}

export interface AgentRunResult {
  threadId?: string;
  turnId?: string;
  finalMessage?: string;
  error?: string;
}

export type AgentTaskKind = "general" | "phone";

export interface AgentRequestOptions {
  deviceId?: string;
  systemPrompt?: string;
  model?: string;
  reasoningEffort?: string;
  taskKind?: AgentTaskKind;
  threadId?: string;
}

export interface AgentClient {
  submitUserRequest(
    text: string,
    sink: AgentStatusSink,
    options?: AgentRequestOptions
  ): Promise<AgentRunResult>;
  steer?(text: string): Promise<void>;
  interrupt?(reason?: string): Promise<void>;
  close(): Promise<void>;
}
