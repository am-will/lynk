import type { HarnessPermissionResponse } from "../chat/ChatTransportTypes.js";

export interface HarnessPermissionReply {
  permissionId: string;
  response: HarnessPermissionResponse;
  invalidStatus: string;
  successStatus: string;
}

interface PermissionCommandSpec {
  command: string;
  invalidStatus: string;
  parse(args: Record<string, unknown>): HarnessPermissionResponse | undefined;
  successStatus(response: HarnessPermissionResponse): string;
}

const PERMISSION_COMMANDS: PermissionCommandSpec[] = [
  {
    command: "opencode.permission",
    invalidStatus: "Invalid OpenCode permission reply.",
    parse: (args) => {
      const response = typeof args.response === "string" ? args.response.trim().toLowerCase() : "";
      return isOpenCodePermissionResponse(response) ? response : undefined;
    },
    successStatus: (response) => `OpenCode permission ${response === "reject" ? "rejected" : "approved"}.`
  },
  {
    command: "devin.permission",
    invalidStatus: "Invalid Devin permission reply.",
    parse: (args) => {
      const optionId = typeof args.optionId === "string" ? args.optionId : "";
      return optionId ? { kind: "acp_option", optionId } : undefined;
    },
    successStatus: () => "Devin permission answered."
  }
];

export function parseHarnessPermissionReply(command: string, args: Record<string, unknown>): HarnessPermissionReply | undefined {
  const spec = PERMISSION_COMMANDS.find((candidate) => candidate.command === command);
  if (!spec) {
    return undefined;
  }
  const permissionId = typeof args.permissionId === "string" ? args.permissionId.trim() : "";
  const response = spec.parse(args);
  if (!permissionId || !response) {
    return {
      permissionId: "",
      response: "reject",
      invalidStatus: spec.invalidStatus,
      successStatus: ""
    };
  }
  return {
    permissionId,
    response,
    invalidStatus: spec.invalidStatus,
    successStatus: spec.successStatus(response)
  };
}

function isOpenCodePermissionResponse(value: string): value is Extract<HarnessPermissionResponse, string> {
  return value === "once" || value === "always" || value === "reject";
}
