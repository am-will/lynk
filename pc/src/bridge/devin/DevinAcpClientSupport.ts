import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { RequestError } from "@agentclientprotocol/sdk";
import { DevinAcpError, type DevinAcpErrorCode } from "./DevinAcpTypes.js";

export function classifyAcpError(error: unknown): DevinAcpError {
  if (error instanceof DevinAcpError) {
    return new DevinAcpError(error.code, sanitizeDiagnosticText(error.message));
  }
  if (error instanceof RequestError) {
    if (error.code === -32000) {
      return new DevinAcpError("auth_required", "Devin ACP requires authentication.");
    }
    const message = sanitizeDiagnosticText(`ACP request failed: ${error.message}`);
    if (/\bsession\b.*\bnot found\b/i.test(error.message)) {
      return new DevinAcpError("not_found", message);
    }
    return new DevinAcpError("malformed_transport", message);
  }
  if (error instanceof Error) {
    const code = classifyErrorCode(error.message);
    return new DevinAcpError(code, sanitizeDiagnosticText(error.message));
  }
  return new DevinAcpError("malformed_transport", sanitizeDiagnosticText(String(error)));
}

function classifyErrorCode(message: string): DevinAcpErrorCode {
  const lower = message.toLowerCase();
  if (lower.includes("protocol version") || lower.includes("protocolversion")) {
    return "protocol_mismatch";
  }
  if (
    lower.includes("parse") ||
    lower.includes("invalid json") ||
    lower.includes("unexpected token") ||
    lower.includes("schema") ||
    lower.includes("invalid request")
  ) {
    return "malformed_transport";
  }
  if (/\bsession\b.*\bnot found\b/.test(lower)) {
    return "not_found";
  }
  if (lower.includes("enoent") || lower.includes("eacces") || lower.includes("spawn")) {
    return "spawn_failure";
  }
  return "malformed_transport";
}

export function withTimeout<T>(promise: Promise<T>, timeoutMs: number, onTimeout: () => void): Promise<T> {
  return new Promise((resolve, reject) => {
    let settled = false;
    const timer = setTimeout(() => {
      if (settled) {
        return;
      }
      settled = true;
      onTimeout();
      promise.catch(() => {});
      reject(new DevinAcpError("request_timeout", `Devin ACP operation timed out after ${timeoutMs}ms.`));
    }, timeoutMs);

    promise.then(
      (value) => {
        if (settled) {
          return;
        }
        settled = true;
        clearTimeout(timer);
        resolve(value);
      },
      (error) => {
        if (settled) {
          return;
        }
        settled = true;
        clearTimeout(timer);
        reject(error);
      }
    );
  });
}

export function positiveMs(value: number | undefined, fallback: number): number {
  return typeof value === "number" && Number.isFinite(value) && value > 0 ? value : fallback;
}

export function readPackageVersion(): string {
  try {
    const url = new URL("../../../package.json", import.meta.url);
    const contents = readFileSync(fileURLToPath(url), "utf8");
    const parsed = JSON.parse(contents) as { version?: string };
    return parsed.version?.trim() || "0.1.0";
  } catch {
    return "0.1.0";
  }
}

export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export function sanitizeDevinCommand(command: string): string {
  return sanitizeDiagnosticText(command);
}

export function sanitizeDiagnosticText(text: string): string {
  const patterns: Array<[RegExp, string]> = [
    [/--(api[_-]?key|token|secret|password)(?:=|\s+)([^\s"'<>]+)/gi, "--$1 [redacted]"],
    [/-(api[_-]?key|token|secret|password)\s+([^\s"'<>]+)/gi, "-$1 [redacted]"],
    [/\b(bearer)\s+[^\s"'<>]+/gi, "$1 [redacted]"],
    [/\b(authorization)\s*:\s*[^\s"'<>]+/gi, "$1: [redacted]"],
    [/\b(authorization)\s*=\s*[^\s"'<>]+/gi, "$1=[redacted]"],
    [/([\w\-]*(?:api[_\-]?key|token|secret|password))\s*=\s*[^\s"'<>]+/gi, "$1=[redacted]"],
    [/([\w\-]*(?:api[_\-]?key|token|secret|password))\s*:\s*[^\s"'<>]+/gi, "$1: [redacted]"]
  ];

  let sanitized = text;
  for (const [pattern, replacement] of patterns) {
    sanitized = sanitized.replace(pattern, replacement);
  }
  return sanitized;
}
