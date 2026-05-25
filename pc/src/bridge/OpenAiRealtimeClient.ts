import type { BridgeConfig } from "./config.js";
import { REALTIME_TOOL_NAMES } from "../protocol/messages.js";
import type { PhoneLocation } from "../protocol/messages.js";

export interface OpenAiRealtimeStartOptions {
  deviceId: string;
  sdp: string;
  systemPrompt?: string;
  apiKey?: string;
  location?: PhoneLocation;
}

export interface OpenAiRealtimeSession {
  deviceId: string;
  callId?: string;
  apiKey?: string;
  location?: PhoneLocation;
  model?: string;
  reasoningEffort?: string;
}

const VOICE_PROMPT = `
You are OpenAgent in a live voice conversation from the user's Android phone. Keep responses short and conversational.
For normal remote-agent work on the PC, briefly acknowledge it and call ${REALTIME_TOOL_NAMES.delegateAgentTask}; the bridge routes that task to the currently selected harness.
Use ${REALTIME_TOOL_NAMES.runPhoneTask} only when the user asks to inspect/control the Android phone or the request clearly needs phone screen/app context.
Do not claim delegated work is complete until tool output is returned.
If the user interrupts, corrects, or adds information while a general agent task is running, use ${REALTIME_TOOL_NAMES.steerAgentTask}. If a phone task is running, use ${REALTIME_TOOL_NAMES.steerPhoneTask}.
If a follow-up can be handled from the current phone screen and no phone task is running, call ${REALTIME_TOOL_NAMES.runPhoneTask} with the follow-up as the instruction; the phone agent will observe the current screen first.
If the user asks to stop, pause, cancel, or leave the current task as-is, use ${REALTIME_TOOL_NAMES.stopAgentTask} for general work or ${REALTIME_TOOL_NAMES.stopPhoneTask} for phone work. Do not start a new task for stop requests.
If the user asks to hang up, end the call, or stop listening, call ${REALTIME_TOOL_NAMES.hangUpRealtime} with stopPhoneTask false so any running phone task can continue.
If the user asks to stop and hang up, call ${REALTIME_TOOL_NAMES.hangUpRealtime} with stopPhoneTask true.
If the user asks a current-events or factual lookup that does not require controlling the phone, use ${REALTIME_TOOL_NAMES.webSearch} and answer from its result instead of running a phone task.
Ask a short clarification question when the instruction is ambiguous.
Confirm only when an action is risky or irreversible, and never bypass Android or desktop-agent safety confirmations.
`.trim();

export function formatLocationContext(location: PhoneLocation | undefined): string | undefined {
  if (!location) {
    return undefined;
  }
  const parts = [
    `latitude ${location.latitude.toFixed(5)}`,
    `longitude ${location.longitude.toFixed(5)}`
  ];
  if (typeof location.accuracyMeters === "number") {
    parts.push(`accuracy about ${Math.round(location.accuracyMeters)} meters`);
  }
  if (location.provider) {
    parts.push(`provider ${location.provider}`);
  }
  return `User location context: ${parts.join(", ")}. Use this for weather, local time, nearby places, and other localized questions unless the user gives a different location.`;
}

const DELEGATE_AGENT_TASK_TOOL = {
  type: "function",
  name: REALTIME_TOOL_NAMES.delegateAgentTask,
  description: "Delegate a general task to the user's currently selected remote agent harness on the PC. Use this for coding, desktop, browser, research, file, and other non-phone work.",
  parameters: {
    type: "object",
    additionalProperties: false,
    properties: {
      instruction: {
        type: "string",
        description: "The concise task to delegate to the selected remote agent harness on the PC."
      },
      urgency: {
        type: "string",
        enum: ["normal", "interrupt"],
        description: "Use interrupt only when the user explicitly wants to stop the current task."
      }
    },
    required: ["instruction"]
  }
} as const;

const RUN_PHONE_TASK_TOOL = {
  type: "function",
  name: REALTIME_TOOL_NAMES.runPhoneTask,
  description: "Execute an actionable instruction on the connected Android phone using the phone automation agent.",
  parameters: {
    type: "object",
    additionalProperties: false,
    properties: {
      instruction: {
        type: "string",
        description: "The concise phone task to execute."
      },
      urgency: {
        type: "string",
        enum: ["normal", "interrupt"],
        description: "Use interrupt only when the user explicitly wants to stop the current phone task."
      }
    },
    required: ["instruction"]
  }
} as const;

const STEER_AGENT_TASK_TOOL = {
  type: "function",
  name: REALTIME_TOOL_NAMES.steerAgentTask,
  description: "Inject new user guidance into the currently running general remote-agent task.",
  parameters: {
    type: "object",
    additionalProperties: false,
    properties: {
      guidance: {
        type: "string",
        description: "The user's correction, updated goal, or extra context to steer the active remote-agent task."
      }
    },
    required: ["guidance"]
  }
} as const;

const STEER_PHONE_TASK_TOOL = {
  type: "function",
  name: REALTIME_TOOL_NAMES.steerPhoneTask,
  description: "Inject new user guidance into the currently running Android phone automation task without restarting it.",
  parameters: {
    type: "object",
    additionalProperties: false,
    properties: {
      guidance: {
        type: "string",
        description: "The user's correction, updated goal, or extra context to steer the active phone task."
      }
    },
    required: ["guidance"]
  }
} as const;

const STOP_AGENT_TASK_TOOL = {
  type: "function",
  name: REALTIME_TOOL_NAMES.stopAgentTask,
  description: "Stop the currently running general remote-agent task and clear queued realtime tasks.",
  parameters: {
    type: "object",
    additionalProperties: false,
    properties: {
      reason: {
        type: "string",
        description: "A short reason for stopping the active remote-agent task."
      }
    },
    required: []
  }
} as const;

const STOP_PHONE_TASK_TOOL = {
  type: "function",
  name: REALTIME_TOOL_NAMES.stopPhoneTask,
  description: "Stop the currently running Android phone automation task and clear any queued realtime phone tasks.",
  parameters: {
    type: "object",
    additionalProperties: false,
    properties: {
      reason: {
        type: "string",
        description: "A short reason for stopping the active phone task."
      }
    },
    required: []
  }
} as const;

const HANG_UP_REALTIME_TOOL = {
  type: "function",
  name: REALTIME_TOOL_NAMES.hangUpRealtime,
  description: "End the live realtime voice session. By default this only stops listening and lets any running phone task continue.",
  parameters: {
    type: "object",
    additionalProperties: false,
    properties: {
      reason: {
        type: "string",
        description: "A short reason for ending the realtime voice session."
      },
      stopPhoneTask: {
        type: "boolean",
        description: "Set true only when the user explicitly asks to stop the phone task and hang up."
      }
    },
    required: []
  }
} as const;

const WEB_SEARCH_TOOL = {
  type: "function",
  name: REALTIME_TOOL_NAMES.webSearch,
  description: "Search the web for current information when a question can be answered without using the Android phone.",
  parameters: {
    type: "object",
    additionalProperties: false,
    properties: {
      query: {
        type: "string",
        description: "The concise web search query."
      }
    },
    required: ["query"]
  }
} as const;

function callIdFromLocation(location: string | null): string | undefined {
  if (!location) {
    return undefined;
  }
  return location.split("/").filter(Boolean).at(-1);
}

export class OpenAiRealtimeClient {
  constructor(private readonly config: BridgeConfig) {}

  async start(options: OpenAiRealtimeStartOptions): Promise<{ answerSdp: string; session: OpenAiRealtimeSession }> {
    const apiKey = options.apiKey?.trim() || this.config.openAiApiKey?.trim();
    if (!apiKey) {
      throw new Error("OpenAI API key is required for realtime voice. Set it in the Android app settings or OPENAI_API_KEY on the PC bridge.");
    }

    const sessionConfig = {
      type: "realtime",
      model: this.config.openAiRealtimeModel,
      instructions: [options.systemPrompt?.trim(), formatLocationContext(options.location), VOICE_PROMPT].filter(Boolean).join("\n\n"),
      tools: [
        DELEGATE_AGENT_TASK_TOOL,
        RUN_PHONE_TASK_TOOL,
        STEER_AGENT_TASK_TOOL,
        STEER_PHONE_TASK_TOOL,
        STOP_AGENT_TASK_TOOL,
        STOP_PHONE_TASK_TOOL,
        HANG_UP_REALTIME_TOOL,
        WEB_SEARCH_TOOL
      ],
      tool_choice: "auto",
      audio: {
        output: {
          voice: this.config.openAiRealtimeVoice
        }
      }
    };

    const formData = new FormData();
    formData.set("sdp", options.sdp);
    formData.set("session", JSON.stringify(sessionConfig));

    const response = await fetch("https://api.openai.com/v1/realtime/calls", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${apiKey}`,
        "OpenAI-Safety-Identifier": options.deviceId
      },
      body: formData
    });

    const body = await response.text();
    if (!response.ok) {
      throw new Error(`OpenAI realtime call failed: ${response.status} ${response.statusText}: ${body}`);
    }

    return {
      answerSdp: body,
      session: {
        deviceId: options.deviceId,
        callId: callIdFromLocation(response.headers.get("location")),
        apiKey,
        location: options.location
      }
    };
  }

  async stop(session: OpenAiRealtimeSession): Promise<void> {
    const apiKey = session.apiKey?.trim() || this.config.openAiApiKey?.trim();
    if (!apiKey || !session.callId) {
      return;
    }

    await fetch(`https://api.openai.com/v1/realtime/calls/${encodeURIComponent(session.callId)}`, {
      method: "DELETE",
      headers: {
        Authorization: `Bearer ${apiKey}`
      }
    }).catch(() => undefined);
  }
}
