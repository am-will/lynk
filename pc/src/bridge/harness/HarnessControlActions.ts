export type HarnessPermissionResponse = "once" | "always" | "reject";

export interface HarnessPermissionReply {
  permissionId: string;
  response: HarnessPermissionResponse;
  invalidStatus: string;
  successStatus: string;
}

interface PermissionCommandSpec {
  command: string;
  invalidStatus: string;
  successStatus(response: HarnessPermissionResponse): string;
}

const PERMISSION_COMMANDS: PermissionCommandSpec[] = [
  {
    command: "opencode.permission",
    invalidStatus: "Invalid OpenCode permission reply.",
    successStatus: (response) => `OpenCode permission ${response === "reject" ? "rejected" : "approved"}.`
  }
];

export function parseHarnessPermissionReply(command: string, args: Record<string, unknown>): HarnessPermissionReply | undefined {
  const spec = PERMISSION_COMMANDS.find((candidate) => candidate.command === command);
  if (!spec) {
    return undefined;
  }
  const permissionId = typeof args.permissionId === "string" ? args.permissionId.trim() : "";
  const response = typeof args.response === "string" ? args.response.trim().toLowerCase() : "";
  if (!permissionId || !isPermissionResponse(response)) {
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

function isPermissionResponse(value: string): value is HarnessPermissionResponse {
  return value === "once" || value === "always" || value === "reject";
}
