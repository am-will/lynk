import type { IncomingMessage } from "node:http";
import type { Duplex } from "node:stream";
import type { WebSocketServer } from "ws";

const PHONE_PATH = "/phone";
const BAD_REQUEST_RESPONSE = "HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n";
const NOT_FOUND_RESPONSE = "HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n";

type UpgradeRequestResult =
  | { accepted: true }
  | { accepted: false; response: string };

export function createPhoneUpgradeHandler(wss: WebSocketServer) {
  return (request: IncomingMessage, socket: Duplex, head: Buffer): void => {
    const result = validatePhoneUpgradeRequest(request);
    if (!result.accepted) {
      rejectUpgrade(socket, result.response);
      return;
    }

    try {
      wss.handleUpgrade(request, socket, head, (webSocket) => {
        wss.emit("connection", webSocket, request);
      });
    } catch {
      rejectUpgrade(socket, BAD_REQUEST_RESPONSE);
    }
  };
}

export function validatePhoneUpgradeRequest(
  request: Pick<IncomingMessage, "headers" | "rawHeaders" | "url">
): UpgradeRequestResult {
  if (!hasOneValidHost(request.headers.host, request.rawHeaders)) {
    return { accepted: false, response: BAD_REQUEST_RESPONSE };
  }

  const target = request.url;
  if (!target || !target.startsWith("/") || target.startsWith("//") || target.includes("\\")) {
    return { accepted: false, response: BAD_REQUEST_RESPONSE };
  }

  try {
    const parsed = new URL(target, "http://localhost");
    if (parsed.origin !== "http://localhost") {
      return { accepted: false, response: BAD_REQUEST_RESPONSE };
    }
    return parsed.pathname === PHONE_PATH
      ? { accepted: true }
      : { accepted: false, response: NOT_FOUND_RESPONSE };
  } catch {
    return { accepted: false, response: BAD_REQUEST_RESPONSE };
  }
}

function hasOneValidHost(host: string | undefined, rawHeaders: readonly string[]): boolean {
  let hostHeaderCount = 0;
  for (let index = 0; index < rawHeaders.length; index += 2) {
    if (rawHeaders[index]?.toLowerCase() === "host") {
      hostHeaderCount += 1;
    }
  }
  if (hostHeaderCount !== 1 || !host) {
    return false;
  }

  try {
    const parsed = new URL(`http://${host}`);
    return parsed.protocol === "http:"
      && parsed.hostname.length > 0
      && !parsed.username
      && !parsed.password
      && parsed.pathname === "/"
      && !parsed.search
      && !parsed.hash;
  } catch {
    return false;
  }
}

function rejectUpgrade(socket: Duplex, response: string): void {
  if (!socket.destroyed) {
    socket.end(response);
  }
}
