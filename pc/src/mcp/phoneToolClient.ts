import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import type { PhoneCommand, PhoneCommandResult } from "../protocol/messages.js";

const pcRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");

function localEnvValue(name: string): string | undefined {
  const envPath = process.env.PHONE_AGENT_ENV_FILE?.trim() || join(pcRoot, ".env.local");
  try {
    const contents = readFileSync(envPath, "utf8");
    for (const line of contents.split(/\r?\n/)) {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith("#")) continue;
      const separator = trimmed.indexOf("=");
      if (separator <= 0) continue;
      if (trimmed.slice(0, separator).trim() !== name) continue;
      return trimmed.slice(separator + 1).trim().replace(/^["']|["']$/g, "");
    }
  } catch {
    return undefined;
  }
  return undefined;
}

function phoneAgentToken(): string | undefined {
  return process.env.PHONE_AGENT_TOKEN?.trim() || localEnvValue("PHONE_AGENT_TOKEN");
}

function phoneAgentBridgeUrl(): string {
  return process.env.PHONE_AGENT_BRIDGE_URL?.trim() || localEnvValue("PHONE_AGENT_BRIDGE_URL") || "http://127.0.0.1:8788";
}

export function bridgeAuthHeaders(token = phoneAgentToken()): Record<string, string> {
  const trimmed = token?.trim();
  if (!trimmed) {
    throw new Error("PHONE_AGENT_TOKEN is required to call protected bridge HTTP APIs.");
  }
  return { authorization: `Bearer ${trimmed}` };
}

export class PhoneToolClient {
  constructor(private readonly bridgeUrl = phoneAgentBridgeUrl()) {}

  async command(command: PhoneCommand, args: Record<string, unknown> = {}, timeoutMs = 30_000): Promise<PhoneCommandResult> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), timeoutMs + 5_000);
    const response = await fetch(`${this.bridgeUrl}/api/phone/default/command`, {
      method: "POST",
      headers: { "content-type": "application/json", ...bridgeAuthHeaders() },
      body: JSON.stringify({ command, args, timeoutMs }),
      signal: controller.signal
    }).finally(() => clearTimeout(timeout));
    try {
      const body = await response.json().catch(() => ({}));
      if (!response.ok) {
        const error = typeof body.error === "string" ? body.error : `Bridge returned HTTP ${response.status}`;
        throw new Error(error);
      }
      return body as PhoneCommandResult;
    } catch (error) {
      if (error instanceof Error && error.name === "AbortError") {
        throw new Error(`Timed out waiting for bridge response to ${command}`);
      }
      throw error;
    }
  }
}

export function screenshotDirectory(): string {
  return process.env.PHONE_AGENT_SCREENSHOT_DIR ?? join(process.cwd(), "..", "captures");
}
