import { sanitizeDiagnosticText } from "./DevinAcpClientSupport.js";

const MAX_STDERR_BYTES = 8192;
const MAX_STDERR_LINES = 50;
const SENSITIVE_LINE_PLACEHOLDER = "[sensitive stderr line redacted]";

function byteLength(s: string): number {
  return Buffer.byteLength(s, "utf8");
}

function tailBytes(s: string, max: number): string {
  const buf = Buffer.from(s, "utf8");
  if (buf.length <= max) {
    return s;
  }
  return buf.subarray(buf.length - max).toString("utf8");
}

function containsSensitiveMarker(text: string): boolean {
  return sanitizeDiagnosticText(text) !== text;
}

export class DevinAcpStderrCapture {
  private lines: string[] = [];
  private byteLength = 0;
  private readonly decoder = new TextDecoder();
  private pending = "";
  private redactingLine = false;

  append(chunk: Uint8Array): void {
    let text = this.decoder.decode(chunk, { stream: true });

    if (this.redactingLine) {
      const match = text.match(/\r?\n/);
      if (!match) {
        return;
      }
      this.pushLine(SENSITIVE_LINE_PLACEHOLDER);
      text = text.slice(match.index! + match[0].length);
      this.redactingLine = false;
    }

    this.pending += text;

    let newline: RegExpMatchArray | null;
    while ((newline = this.pending.match(/\r?\n/))) {
      let line = this.pending.slice(0, newline.index);
      this.pending = this.pending.slice(newline.index! + newline[0].length);
      if (this.redactingLine) {
        this.redactingLine = false;
        line = SENSITIVE_LINE_PLACEHOLDER;
      }
      this.pushLine(line);
    }

    if (containsSensitiveMarker(this.pending)) {
      this.redactingLine = true;
      this.pending = "";
    } else if (byteLength(this.pending) > MAX_STDERR_BYTES) {
      this.pending = tailBytes(this.pending, MAX_STDERR_BYTES);
    }
  }

  flush(): void {
    if (this.redactingLine) {
      this.pushLine(SENSITIVE_LINE_PLACEHOLDER);
      this.redactingLine = false;
    } else if (this.pending) {
      this.pushLine(this.pending);
    }
    this.pending = "";
  }

  snapshot(): string {
    return this.lines.join("\n");
  }

  private pushLine(raw: string): void {
    const sanitized = sanitizeDiagnosticText(raw);
    if (!sanitized) {
      return;
    }

    let line = sanitized;
    let lineBytes = byteLength(line);
    if (lineBytes > MAX_STDERR_BYTES) {
      this.lines = [];
      this.byteLength = 0;
      line = tailBytes(line, MAX_STDERR_BYTES);
      lineBytes = byteLength(line);
    }

    this.lines.push(line);
    this.byteLength += lineBytes;
    while (this.lines.length > MAX_STDERR_LINES || this.byteLength > MAX_STDERR_BYTES) {
      const removed = this.lines.shift();
      if (removed) {
        this.byteLength -= byteLength(removed);
      }
    }
  }
}
