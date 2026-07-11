import { timingSafeEqual } from "node:crypto";
import type { IncomingHttpHeaders } from "node:http";

export const PHONE_AGENT_TOKEN_HEADER = "x-phone-agent-token";

function firstHeader(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

export function tokenEquals(candidate: string | undefined, expected: string): boolean {
  if (!candidate || !expected) {
    return false;
  }
  const candidateBuffer = Buffer.from(candidate, "utf8");
  const expectedBuffer = Buffer.from(expected, "utf8");
  return candidateBuffer.length === expectedBuffer.length && timingSafeEqual(candidateBuffer, expectedBuffer);
}

export function bearerToken(authorization: string | string[] | undefined): string | undefined {
  const header = firstHeader(authorization)?.trim();
  if (!header) {
    return undefined;
  }
  const [scheme, ...rest] = header.split(/\s+/);
  if (scheme?.toLowerCase() !== "bearer" || rest.length !== 1) {
    return undefined;
  }
  return rest[0];
}

export function isAuthorizedHttpRequest(headers: IncomingHttpHeaders, expectedToken: string): boolean {
  return tokenEquals(bearerToken(headers.authorization), expectedToken) ||
    tokenEquals(firstHeader(headers[PHONE_AGENT_TOKEN_HEADER]), expectedToken);
}
