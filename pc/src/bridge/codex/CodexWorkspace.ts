import { existsSync, mkdirSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { ChatClientError, CODEX_WORKSPACE_NOT_FOUND_CODE } from "../chat/ChatErrors.js";

export function prepareCodexWorkspace(path: string | null | undefined, createIfMissing: boolean): string | undefined {
  const workspacePath = expandHomePath(path);
  ensureWorkspaceDirectory(workspacePath, path, createIfMissing);
  return workspacePath;
}

function expandHomePath(path: string | null | undefined): string | undefined {
  const trimmed = path?.trim();
  if (!trimmed) {
    return undefined;
  }
  if (trimmed === "~") {
    return homedir();
  }
  if (trimmed.startsWith("~/")) {
    return join(homedir(), trimmed.slice(2));
  }
  return trimmed;
}

function ensureWorkspaceDirectory(path: string | undefined, displayPath: string | null | undefined, createIfMissing: boolean): void {
  if (!path) {
    return;
  }
  if (!existsSync(path)) {
    if (!createIfMissing) {
      const workspacePath = displayPath?.trim() || path;
      throw new ChatClientError(`Codex workspace folder not found: ${workspacePath}`, {
        code: CODEX_WORKSPACE_NOT_FOUND_CODE,
        workspacePath
      });
    }
    mkdirSync(path, { recursive: true });
  }
  if (!statSync(path).isDirectory()) {
    throw new Error(`Codex workspace path is not a folder: ${displayPath?.trim() || path}`);
  }
}
