import { createHash } from "node:crypto";
import {
  chmodSync,
  closeSync,
  createReadStream,
  existsSync,
  fsyncSync,
  mkdirSync,
  openSync,
  readFileSync,
  readdirSync,
  renameSync,
  rmSync,
  statfsSync,
  statSync,
  utimesSync,
  writeFileSync
} from "node:fs";
import { open } from "node:fs/promises";
import { basename, join } from "node:path";
import type { Readable } from "node:stream";

export const HOST_BLOB_MAX_ITEM_BYTES = 50 * 1024 * 1024;
export const HOST_BLOB_MAX_COUNT = 256;
export const HOST_BLOB_MAX_AGGREGATE_BYTES = 1024 * 1024 * 1024;
export const HOST_BLOB_FREE_SPACE_RESERVE_BYTES = 512 * 1024 * 1024;
export const HOST_BLOB_RETENTION_MS = 24 * 60 * 60 * 1000;

export interface HostBlobOwner {
  deviceId: string;
  sessionKey: string;
}

export interface HostBlobUploadRequest extends HostBlobOwner {
  id: string;
  displayName: string;
  mimeType: string;
  kind: "image" | "file";
  sha256: string;
  declaredSizeBytes?: number;
}

export interface HostBlobMetadata extends HostBlobOwner {
  version: 1;
  id: string;
  displayName: string;
  mimeType: string;
  kind: "image" | "file";
  sizeBytes: number;
  sha256: string;
  createdAt: number;
}

export interface ResolvedHostBlob extends HostBlobMetadata {
  path: string;
}

export interface HostBlobStoreOptions {
  maxItemBytes?: number;
  maxBlobCount?: number;
  maxAggregateBytes?: number;
  freeSpaceReserveBytes?: number;
  retentionMs?: number;
  now?: () => number;
  usableSpaceBytes?: (directory: string) => number;
}

export class HostBlobStoreError extends Error {
  constructor(message: string, readonly statusCode: number) {
    super(message);
    this.name = "HostBlobStoreError";
  }
}

export class HostBlobStore {
  private readonly maxItemBytes: number;
  private readonly maxBlobCount: number;
  private readonly maxAggregateBytes: number;
  private readonly freeSpaceReserveBytes: number;
  private readonly retentionMs: number;
  private readonly now: () => number;
  private readonly usableSpaceBytes: (directory: string) => number;
  private readonly reservations = new Map<string, number>();
  private readonly activePartialNames = new Set<string>();

  constructor(readonly directory: string, options: HostBlobStoreOptions = {}) {
    this.maxItemBytes = positiveInteger(options.maxItemBytes, HOST_BLOB_MAX_ITEM_BYTES, "maxItemBytes");
    this.maxBlobCount = positiveInteger(options.maxBlobCount, HOST_BLOB_MAX_COUNT, "maxBlobCount");
    this.maxAggregateBytes = positiveInteger(options.maxAggregateBytes, HOST_BLOB_MAX_AGGREGATE_BYTES, "maxAggregateBytes");
    this.freeSpaceReserveBytes = nonnegativeInteger(
      options.freeSpaceReserveBytes,
      HOST_BLOB_FREE_SPACE_RESERVE_BYTES,
      "freeSpaceReserveBytes"
    );
    this.retentionMs = positiveInteger(options.retentionMs, HOST_BLOB_RETENTION_MS, "retentionMs");
    if (this.maxAggregateBytes < this.maxItemBytes) {
      throw new Error("maxAggregateBytes must be at least maxItemBytes");
    }
    this.now = options.now ?? Date.now;
    this.usableSpaceBytes = options.usableSpaceBytes ?? availableBytes;
    mkdirSync(directory, { recursive: true, mode: 0o700 });
    try {
      chmodSync(directory, 0o700);
    } catch (error) {
      if (process.platform !== "win32") throw error;
    }
    this.cleanup();
  }

  async upload(input: Readable, request: HostBlobUploadRequest): Promise<HostBlobMetadata> {
    const normalized = this.normalizeUpload(request);
    const reservationBytes = normalized.declaredSizeBytes ?? this.maxItemBytes;
    const existing = this.reserve(normalized, reservationBytes);
    const partialName = `.${normalized.id}-${process.pid}-${Date.now()}.partial`;
    const partialPath = join(this.directory, partialName);
    this.activePartialNames.add(partialName);
    try {
      const { sizeBytes, sha256 } = await this.writeBounded(input, partialPath);
      if (normalized.declaredSizeBytes !== undefined && normalized.declaredSizeBytes !== sizeBytes) {
        throw new HostBlobStoreError(
          `${normalized.displayName} declared ${normalized.declaredSizeBytes} bytes but uploaded ${sizeBytes}`,
          400
        );
      }
      if (sha256 !== normalized.sha256) {
        throw new HostBlobStoreError(`Checksum mismatch for ${normalized.displayName}`, 422);
      }
      if (existing) return existing;
      const metadata: HostBlobMetadata = {
        version: 1,
        id: normalized.id,
        deviceId: normalized.deviceId,
        sessionKey: normalized.sessionKey,
        displayName: normalized.displayName,
        mimeType: normalized.mimeType,
        kind: normalized.kind,
        sizeBytes,
        sha256,
        createdAt: this.now()
      };
      this.publish(partialPath, metadata);
      return metadata;
    } finally {
      this.activePartialNames.delete(partialName);
      this.reservations.delete(normalized.id);
      rmSync(partialPath, { force: true });
    }
  }

  resolve(id: string, owner: HostBlobOwner, expectedSha256?: string): ResolvedHostBlob | undefined {
    if (!BLOB_ID.test(id)) return undefined;
    const normalizedOwner = normalizeOwner(owner);
    const metadata = this.readMetadata(id);
    if (!metadata || metadata.deviceId !== normalizedOwner.deviceId || metadata.sessionKey !== normalizedOwner.sessionKey) {
      return undefined;
    }
    if (expectedSha256 !== undefined && metadata.sha256 !== normalizeSha256(expectedSha256)) {
      return undefined;
    }
    const path = this.payloadPath(id);
    if (!existsSync(path) || statSync(path).size !== metadata.sizeBytes) {
      return undefined;
    }
    const accessed = new Date(this.now());
    utimesSync(path, accessed, accessed);
    return { ...metadata, path };
  }

  openDownload(id: string, owner: HostBlobOwner): { metadata: HostBlobMetadata; stream: Readable } | undefined {
    const resolved = this.resolve(id, owner);
    if (!resolved) return undefined;
    const { path, ...metadata } = resolved;
    return { metadata, stream: createReadStream(path) };
  }

  delete(id: string, owner: HostBlobOwner): boolean {
    const resolved = this.resolve(id, owner);
    if (!resolved || this.reservations.has(id)) return false;
    rmSync(resolved.path, { force: true });
    rmSync(this.metadataPath(id), { force: true });
    return true;
  }

  cleanup(): number {
    mkdirSync(this.directory, { recursive: true });
    let deleted = 0;
    for (const name of readdirSync(this.directory)) {
      if (name.endsWith(".partial") && !this.activePartialNames.has(name)) {
        rmSync(join(this.directory, name), { force: true });
        deleted += 1;
      }
    }

    const cutoff = this.now() - this.retentionMs;
    const knownPayloads = new Set<string>();
    for (const name of readdirSync(this.directory).filter((entry) => entry.endsWith(METADATA_SUFFIX))) {
      const id = name.slice(0, -METADATA_SUFFIX.length);
      const metadata = this.readMetadata(id);
      const payloadPath = this.payloadPath(id);
      const validPair = metadata !== undefined
        && basename(this.metadataPath(metadata.id)) === name
        && existsSync(payloadPath)
        && statSync(payloadPath).size === metadata.sizeBytes;
      if (!validPair) {
        rmSync(join(this.directory, name), { force: true });
        if (BLOB_ID.test(id)) rmSync(payloadPath, { force: true });
        deleted += 1;
        continue;
      }
      const payloadName = basename(payloadPath);
      knownPayloads.add(payloadName);
      if (statSync(payloadPath).mtimeMs < cutoff && !this.reservations.has(id)) {
        rmSync(payloadPath, { force: true });
        rmSync(join(this.directory, name), { force: true });
        knownPayloads.delete(payloadName);
        deleted += 2;
      }
    }
    for (const name of readdirSync(this.directory).filter((entry) => entry.endsWith(PAYLOAD_SUFFIX))) {
      if (!knownPayloads.has(name)) {
        rmSync(join(this.directory, name), { force: true });
        deleted += 1;
      }
    }
    return deleted;
  }

  private async writeBounded(input: Readable, partialPath: string): Promise<{ sizeBytes: number; sha256: string }> {
    const file = await open(partialPath, "wx", 0o600);
    const digest = createHash("sha256");
    let sizeBytes = 0;
    try {
      for await (const value of input) {
        const chunk = Buffer.isBuffer(value) ? value : Buffer.from(value as Uint8Array);
        if (chunk.byteLength > this.maxItemBytes - sizeBytes) {
          throw new HostBlobStoreError(`Blob exceeds the ${this.maxItemBytes}-byte item limit`, 413);
        }
        await writeAll(file, chunk);
        digest.update(chunk);
        sizeBytes += chunk.byteLength;
      }
      if (sizeBytes === 0) throw new HostBlobStoreError("Blob body is empty", 400);
      await file.sync();
      return { sizeBytes, sha256: digest.digest("hex") };
    } catch (error) {
      if (error instanceof HostBlobStoreError) throw error;
      throw new HostBlobStoreError(`Blob upload was interrupted: ${error instanceof Error ? error.message : String(error)}`, 400);
    } finally {
      await file.close();
    }
  }

  private reserve(request: HostBlobUploadRequest, reservationBytes: number): HostBlobMetadata | undefined {
    if (this.reservations.has(request.id)) {
      throw new HostBlobStoreError(`Blob ${request.id} is already being uploaded`, 409);
    }
    const existing = this.matchingPublishedBlob(request);
    if (existsSync(this.payloadPath(request.id)) || existsSync(this.metadataPath(request.id))) {
      if (!existing) throw new HostBlobStoreError(`Blob ${request.id} already exists`, 409);
      if (this.usableSpaceBytes(this.directory) < this.freeSpaceReserveBytes + reservationBytes) {
        throw new HostBlobStoreError("Insufficient free storage to verify blob retry", 507);
      }
      this.reservations.set(request.id, reservationBytes);
      return existing;
    }
    const payloads = this.publishedPayloads();
    if (payloads.length + this.reservations.size >= this.maxBlobCount) {
      throw new HostBlobStoreError(`Blob store contains the maximum of ${this.maxBlobCount} items`, 507);
    }
    const committedBytes = payloads.reduce((total, path) => total + statSync(path).size, 0);
    const reservedBytes = [...this.reservations.values()].reduce((total, size) => total + size, 0);
    if (committedBytes > this.maxAggregateBytes - reservedBytes - reservationBytes) {
      throw new HostBlobStoreError(`Blob store exceeds its ${this.maxAggregateBytes}-byte aggregate limit`, 507);
    }
    if (this.usableSpaceBytes(this.directory) < this.freeSpaceReserveBytes + reservationBytes) {
      throw new HostBlobStoreError("Insufficient free storage for blob upload", 507);
    }
    this.reservations.set(request.id, reservationBytes);
    return undefined;
  }

  private matchingPublishedBlob(request: HostBlobUploadRequest): HostBlobMetadata | undefined {
    const existing = this.resolve(request.id, request, request.sha256);
    if (!existing
      || existing.displayName !== request.displayName
      || existing.mimeType !== request.mimeType
      || existing.kind !== request.kind
      || (request.declaredSizeBytes !== undefined && existing.sizeBytes !== request.declaredSizeBytes)) {
      return undefined;
    }
    const { path: _path, ...metadata } = existing;
    return metadata;
  }

  private publish(partialPath: string, metadata: HostBlobMetadata): void {
    const payloadPath = this.payloadPath(metadata.id);
    const metadataPath = this.metadataPath(metadata.id);
    const partialMetadataPath = join(this.directory, `.${metadata.id}-${process.pid}.metadata.partial`);
    if (existsSync(payloadPath) || existsSync(metadataPath)) {
      throw new HostBlobStoreError(`Blob ${metadata.id} already exists`, 409);
    }
    try {
      renameSync(partialPath, payloadPath);
      writeDurableFile(partialMetadataPath, JSON.stringify(metadata));
      renameSync(partialMetadataPath, metadataPath);
      syncDirectory(this.directory);
    } catch (error) {
      rmSync(payloadPath, { force: true });
      rmSync(metadataPath, { force: true });
      throw error;
    } finally {
      rmSync(partialMetadataPath, { force: true });
    }
  }

  private normalizeUpload(request: HostBlobUploadRequest): HostBlobUploadRequest {
    if (!BLOB_ID.test(request.id)) throw new HostBlobStoreError("Invalid blob id", 400);
    const owner = normalizeOwner(request);
    const displayName = sanitizeDisplayName(request.displayName);
    const mimeType = normalizeMimeType(request.mimeType);
    if (request.kind !== "image" && request.kind !== "file") throw new HostBlobStoreError("Invalid blob kind", 400);
    const sha256 = normalizeSha256(request.sha256);
    const declaredSizeBytes = request.declaredSizeBytes === undefined
      ? undefined
      : nonnegativeInteger(request.declaredSizeBytes, 0, "declaredSizeBytes");
    if (declaredSizeBytes !== undefined && declaredSizeBytes > this.maxItemBytes) {
      throw new HostBlobStoreError(`${displayName} exceeds the ${this.maxItemBytes}-byte item limit`, 413);
    }
    return { ...request, ...owner, displayName, mimeType, sha256, declaredSizeBytes };
  }

  private readMetadata(id: string): HostBlobMetadata | undefined {
    if (!BLOB_ID.test(id)) return undefined;
    const path = this.metadataPath(id);
    if (!existsSync(path) || statSync(path).size > MAX_METADATA_BYTES) return undefined;
    try {
      const value = JSON.parse(readFileSync(path, "utf8")) as Partial<HostBlobMetadata>;
      if (value.version !== 1 || value.id !== id || !BLOB_ID.test(value.id)) return undefined;
      const owner = normalizeOwner({ deviceId: value.deviceId ?? "", sessionKey: value.sessionKey ?? "" });
      const displayName = sanitizeDisplayName(value.displayName ?? "");
      const mimeType = normalizeMimeType(value.mimeType ?? "");
      const sha256 = normalizeSha256(value.sha256 ?? "");
      if (value.kind !== "image" && value.kind !== "file") return undefined;
      if (!Number.isSafeInteger(value.sizeBytes) || (value.sizeBytes ?? 0) <= 0 || (value.sizeBytes ?? 0) > this.maxItemBytes) return undefined;
      if (!Number.isSafeInteger(value.createdAt) || (value.createdAt ?? 0) <= 0) return undefined;
      return {
        version: 1,
        id,
        ...owner,
        displayName,
        mimeType,
        kind: value.kind,
        sizeBytes: value.sizeBytes as number,
        sha256,
        createdAt: value.createdAt as number
      };
    } catch {
      return undefined;
    }
  }

  private publishedPayloads(): string[] {
    return readdirSync(this.directory)
      .filter((name) => name.endsWith(PAYLOAD_SUFFIX))
      .map((name) => join(this.directory, name));
  }

  private payloadPath(id: string): string {
    return join(this.directory, `${id}${PAYLOAD_SUFFIX}`);
  }

  private metadataPath(id: string): string {
    return join(this.directory, `${id}${METADATA_SUFFIX}`);
  }
}

const BLOB_ID = /^blob_[A-Za-z0-9-]{8,80}$/u;
const SHA256 = /^[a-f0-9]{64}$/u;
const MIME_TYPE = /^[a-z0-9!#$&^_.+-]+\/[a-z0-9!#$&^_.+-]+$/u;
const CONTROL_CHARACTERS = /[\u0000-\u001f\u007f]/u;
const PAYLOAD_SUFFIX = ".blob";
const METADATA_SUFFIX = ".json";
const MAX_METADATA_BYTES = 16 * 1024;

function normalizeOwner(owner: HostBlobOwner): HostBlobOwner {
  return {
    deviceId: boundedText(owner.deviceId, 160, "deviceId"),
    sessionKey: boundedText(owner.sessionKey, 512, "sessionKey")
  };
}

function boundedText(value: string, maxLength: number, field: string): string {
  const normalized = value.trim();
  if (!normalized || normalized.length > maxLength || CONTROL_CHARACTERS.test(normalized)) {
    throw new HostBlobStoreError(`${field} must be 1-${maxLength} characters without controls`, 400);
  }
  return normalized;
}

function sanitizeDisplayName(value: string): string {
  const normalized = value.replace(/[\u0000-\u001f\u007f]/gu, "").trim().slice(0, 120);
  if (!normalized) throw new HostBlobStoreError("displayName is required", 400);
  return normalized;
}

function normalizeMimeType(value: string): string {
  const normalized = value.trim().toLowerCase();
  if (!MIME_TYPE.test(normalized)) throw new HostBlobStoreError("Invalid mimeType", 400);
  return normalized;
}

function normalizeSha256(value: string): string {
  const normalized = value.trim().toLowerCase();
  if (!SHA256.test(normalized)) throw new HostBlobStoreError("Invalid SHA-256 checksum", 400);
  return normalized;
}

function positiveInteger(value: number | undefined, fallback: number, field: string): number {
  const resolved = value ?? fallback;
  if (!Number.isSafeInteger(resolved) || resolved <= 0) throw new Error(`${field} must be a positive safe integer`);
  return resolved;
}

function nonnegativeInteger(value: number | undefined, fallback: number, field: string): number {
  const resolved = value ?? fallback;
  if (!Number.isSafeInteger(resolved) || resolved < 0) throw new HostBlobStoreError(`${field} must be a nonnegative safe integer`, 400);
  return resolved;
}

function availableBytes(directory: string): number {
  const stats = statfsSync(directory);
  return Number(stats.bavail) * Number(stats.bsize);
}

async function writeAll(file: Awaited<ReturnType<typeof open>>, buffer: Buffer): Promise<void> {
  let offset = 0;
  while (offset < buffer.byteLength) {
    const { bytesWritten } = await file.write(buffer, offset, buffer.byteLength - offset);
    if (bytesWritten <= 0) throw new Error("Blob write made no progress");
    offset += bytesWritten;
  }
}

function writeDurableFile(path: string, contents: string): void {
  const descriptor = openSync(path, "wx", 0o600);
  try {
    writeFileSync(descriptor, contents, "utf8");
    fsyncSync(descriptor);
  } finally {
    closeSync(descriptor);
  }
}

function syncDirectory(path: string): void {
  if (process.platform === "win32") return;
  const descriptor = openSync(path, "r");
  try {
    fsyncSync(descriptor);
  } finally {
    closeSync(descriptor);
  }
}
