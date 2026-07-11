import { randomUUID } from "node:crypto";
import { constants } from "node:fs";
import {
  chmodSync,
  closeSync,
  copyFileSync,
  existsSync,
  fsyncSync,
  mkdirSync,
  openSync,
  readFileSync,
  renameSync,
  rmSync,
  statSync,
  writeFileSync
} from "node:fs";
import {
  access,
  chmod,
  copyFile,
  mkdir,
  open,
  readFile,
  readdir,
  rename,
  rm,
  stat,
  lstat
} from "node:fs/promises";
import { basename, dirname, join } from "node:path";

const PRIVATE_DIRECTORY_MODE = 0o700;
const PRIVATE_FILE_MODE = 0o600;
const DEFAULT_STALE_TEMP_AGE_MS = 24 * 60 * 60 * 1_000;
const MAX_TEMP_SCAVENGE_ENTRIES = 256;

export interface AtomicWriteOptions {
  maxBytes?: number;
  keepBackup?: boolean;
  beforeRename?: (temporaryPath: string) => Promise<void> | void;
}

export interface AtomicWriteSyncOptions extends Pick<AtomicWriteOptions, "maxBytes" | "keepBackup"> {
  /** Test seam; production uses an fsync of the destination's parent directory. */
  directorySync?: (directory: string) => void;
}

export interface JsonRecovery<T> {
  value: T;
  source: "primary" | "backup" | "fallback";
  quarantinedPath?: string;
}

export function readJsonWithRecoverySync<T>(
  path: string,
  fallback: () => T,
  validate: (value: unknown) => value is T,
  maxBytes = 16 * 1024 * 1024
): JsonRecovery<T> {
  const primary = readBoundedJsonSync(path, validate, maxBytes);
  if (primary.ok) return { value: primary.value, source: "primary" };
  if (primary.missing) return { value: fallback(), source: "fallback" };
  const quarantinedPath = `${path}.corrupt-${Date.now()}`;
  try { renameSync(path, quarantinedPath); } catch { /* recovery remains best effort */ }
  const backup = readBoundedJsonSync(`${path}.bak`, validate, maxBytes);
  if (backup.ok) {
    atomicWritePrivateFileSync(path, `${JSON.stringify(backup.value, null, 2)}\n`, { maxBytes, keepBackup: false });
    return { value: backup.value, source: "backup", quarantinedPath };
  }
  return { value: fallback(), source: "fallback", quarantinedPath };
}

export function migrateLegacyFileSync(
  destination: string,
  legacyCandidates: readonly string[],
  markerPath: string,
  maxBytes = 16 * 1024 * 1024
): { migrated: boolean; source?: string } {
  if (existsSync(markerPath)) return { migrated: false };
  if (existsSync(destination)) {
    atomicWritePrivateFileSync(markerPath, `${JSON.stringify({ version: 1, state: "destination-present" })}\n`);
    return { migrated: false };
  }
  for (const source of legacyCandidates) {
    if (!existsSync(source)) continue;
    const sourceStat = statSync(source);
    if (!sourceStat.isFile() || sourceStat.size > maxBytes) continue;
    const contents = readFileSync(source);
    atomicWritePrivateFileSync(destination, contents, { maxBytes, keepBackup: false });
    if (!readFileSync(destination).equals(contents)) throw new Error(`Legacy migration verification failed for ${source}.`);
    atomicWritePrivateFileSync(markerPath, `${JSON.stringify({ version: 1, state: "copied", source, destination })}\n`);
    return { migrated: true, source };
  }
  atomicWritePrivateFileSync(markerPath, `${JSON.stringify({ version: 1, state: "no-source" })}\n`);
  return { migrated: false };
}

export function atomicWritePrivateFileSync(
  path: string,
  data: string | Uint8Array,
  options: AtomicWriteSyncOptions = {}
): void {
  const bytes = typeof data === "string" ? Buffer.byteLength(data) : data.byteLength;
  const maxBytes = options.maxBytes ?? 16 * 1024 * 1024;
  if (bytes > maxBytes) throw new Error(`Persistence payload exceeds ${maxBytes} bytes.`);
  const directory = dirname(path);
  mkdirSync(directory, { recursive: true, mode: PRIVATE_DIRECTORY_MODE });
  chmodPrivateSync(directory, PRIVATE_DIRECTORY_MODE);
  const temporaryPath = join(directory, `.${basename(path)}.${process.pid}.${randomUUID()}.tmp`);
  let descriptor: number | undefined;
  try {
    descriptor = openSync(temporaryPath, "wx", PRIVATE_FILE_MODE);
    writeFileSync(descriptor, data);
    fsyncSync(descriptor);
    closeSync(descriptor);
    descriptor = undefined;
    if (options.keepBackup !== false && existsSync(path)) {
      copyFileSync(path, `${path}.bak`);
      chmodPrivateSync(`${path}.bak`, PRIVATE_FILE_MODE);
    }
    renameSync(temporaryPath, path);
    chmodPrivateSync(path, PRIVATE_FILE_MODE);
    (options.directorySync ?? syncDirectorySync)(directory);
  } catch (error) {
    if (descriptor !== undefined) closeSync(descriptor);
    rmSync(temporaryPath, { force: true });
    throw error;
  }
}

export function enforcePrivateFileSync(path: string): void {
  const directory = dirname(path);
  mkdirSync(directory, { recursive: true, mode: PRIVATE_DIRECTORY_MODE });
  chmodPrivateSync(directory, PRIVATE_DIRECTORY_MODE);
  if (existsSync(path)) chmodPrivateSync(path, PRIVATE_FILE_MODE);
}

export async function ensurePrivateDirectory(path: string): Promise<void> {
  await mkdir(path, { recursive: true, mode: PRIVATE_DIRECTORY_MODE });
  await chmod(path, PRIVATE_DIRECTORY_MODE).catch(handleUnsupportedMode);
}

export async function atomicWritePrivateFile(
  path: string,
  data: string | Uint8Array,
  options: AtomicWriteOptions = {}
): Promise<void> {
  const bytes = typeof data === "string" ? Buffer.byteLength(data) : data.byteLength;
  const maxBytes = options.maxBytes ?? 16 * 1024 * 1024;
  if (bytes > maxBytes) throw new Error(`Persistence payload exceeds ${maxBytes} bytes.`);

  const directory = dirname(path);
  await ensurePrivateDirectory(directory);
  const temporaryPath = join(directory, `.${basename(path)}.${process.pid}.${randomUUID()}.tmp`);
  let handle;
  try {
    handle = await open(temporaryPath, "wx", PRIVATE_FILE_MODE);
    await handle.writeFile(data);
    await handle.sync();
    await handle.close();
    handle = undefined;
    await chmod(temporaryPath, PRIVATE_FILE_MODE).catch(handleUnsupportedMode);
    if (options.keepBackup !== false && await exists(path)) {
      await copyFile(path, `${path}.bak`);
      await chmod(`${path}.bak`, PRIVATE_FILE_MODE).catch(handleUnsupportedMode);
    }
    await options.beforeRename?.(temporaryPath);
    await rename(temporaryPath, path);
    await chmod(path, PRIVATE_FILE_MODE).catch(handleUnsupportedMode);
    await syncDirectory(directory);
  } catch (error) {
    await handle?.close().catch(() => undefined);
    await rm(temporaryPath, { force: true }).catch(() => undefined);
    throw error;
  }
}

export async function readJsonWithRecovery<T>(
  path: string,
  fallback: () => T,
  validate: (value: unknown) => value is T,
  maxBytes = 16 * 1024 * 1024
): Promise<JsonRecovery<T>> {
  const primary = await readBoundedJson(path, validate, maxBytes);
  if (primary.ok) return { value: primary.value, source: "primary" };
  if (primary.missing) return { value: fallback(), source: "fallback" };

  const quarantinedPath = `${path}.corrupt-${Date.now()}`;
  await rename(path, quarantinedPath).catch(() => undefined);
  const backup = await readBoundedJson(`${path}.bak`, validate, maxBytes);
  if (backup.ok) {
    await atomicWritePrivateFile(path, `${JSON.stringify(backup.value, null, 2)}\n`, { maxBytes, keepBackup: false });
    return { value: backup.value, source: "backup", quarantinedPath };
  }
  return { value: fallback(), source: "fallback", quarantinedPath };
}

export class DebouncedAtomicJsonWriter<T> {
  private timer?: NodeJS.Timeout;
  private pending?: T;
  private chain: Promise<void>;
  private closed = false;
  private lastError?: unknown;

  constructor(
    private readonly path: string,
    private readonly debounceMs = 25,
    private readonly maxBytes = 16 * 1024 * 1024,
    private readonly writeFile: typeof atomicWritePrivateFile = atomicWritePrivateFile
  ) {
    this.chain = cleanupAtomicLeftovers(path);
  }

  schedule(value: T): void {
    if (this.closed) throw new Error("Persistence writer is closed.");
    this.pending = value;
    if (this.timer) return;
    this.timer = setTimeout(() => {
      this.timer = undefined;
      this.enqueuePending();
    }, this.debounceMs);
    this.timer.unref?.();
  }

  async flush(): Promise<void> {
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = undefined;
    }
    this.enqueuePending();
    await this.chain;
    if (this.lastError) {
      const error = this.lastError;
      this.lastError = undefined;
      throw error;
    }
  }

  async close(): Promise<void> {
    await this.flush();
    this.closed = true;
  }

  private enqueuePending(): void {
    const value = this.pending;
    if (value === undefined) return;
    this.pending = undefined;
    const encoded = `${JSON.stringify(value, null, 2)}\n`;
    this.chain = this.chain
      .catch(() => undefined)
      .then(() => this.writeFile(this.path, encoded, { maxBytes: this.maxBytes }))
      .catch((error) => { this.lastError = error; });
  }
}

export async function migrateLegacyFile(
  destination: string,
  legacyCandidates: readonly string[],
  markerPath: string,
  maxBytes = 16 * 1024 * 1024
): Promise<{ migrated: boolean; source?: string }> {
  if (await exists(markerPath)) return { migrated: false };
  if (await exists(destination)) {
    await atomicWritePrivateFile(markerPath, `${JSON.stringify({ version: 1, state: "destination-present" })}\n`);
    return { migrated: false };
  }
  for (const source of legacyCandidates) {
    if (!await exists(source)) continue;
    const sourceStat = await stat(source);
    if (!sourceStat.isFile() || sourceStat.size > maxBytes) continue;
    const contents = await readFile(source);
    await atomicWritePrivateFile(destination, contents, { maxBytes, keepBackup: false });
    const verified = await readFile(destination);
    if (!verified.equals(contents)) throw new Error(`Legacy migration verification failed for ${source}.`);
    await atomicWritePrivateFile(markerPath, `${JSON.stringify({ version: 1, state: "copied", source, destination })}\n`);
    return { migrated: true, source };
  }
  await atomicWritePrivateFile(markerPath, `${JSON.stringify({ version: 1, state: "no-source" })}\n`);
  return { migrated: false };
}

/** Startup-only scavenging. Atomic writes deliberately never invoke this on their hot path. */
export async function cleanupAtomicLeftovers(
  path: string,
  options: { staleAfterMs?: number; now?: number; maxEntries?: number } = {}
): Promise<void> {
  const directory = dirname(path);
  const prefix = `.${basename(path)}.`;
  const staleAfterMs = Math.max(0, options.staleAfterMs ?? DEFAULT_STALE_TEMP_AGE_MS);
  const staleBefore = (options.now ?? Date.now()) - staleAfterMs;
  const maxEntries = Math.max(0, Math.min(options.maxEntries ?? MAX_TEMP_SCAVENGE_ENTRIES, MAX_TEMP_SCAVENGE_ENTRIES));
  const entries = await readdir(directory).catch(() => []);
  const candidates = entries
    .filter((entry) => entry.startsWith(prefix) && entry.endsWith(".tmp"))
    .slice(0, maxEntries);
  await Promise.all(candidates.map(async (entry) => {
    const temporaryPath = join(directory, entry);
    const info = await lstat(temporaryPath).catch(() => undefined);
    if (!info || info.isSymbolicLink() || !info.isFile() || info.mtimeMs > staleBefore) return;
    await rm(temporaryPath, { force: true }).catch(() => undefined);
  }));
}

async function readBoundedJson<T>(
  path: string,
  validate: (value: unknown) => value is T,
  maxBytes: number
): Promise<{ ok: true; value: T } | { ok: false; missing?: boolean }> {
  try {
    const info = await stat(path);
    if (!info.isFile() || info.size > maxBytes) return { ok: false };
    const parsed: unknown = JSON.parse(await readFile(path, "utf8"));
    return validate(parsed) ? { ok: true, value: parsed } : { ok: false };
  } catch (error) {
    return isMissing(error) ? { ok: false, missing: true } : { ok: false };
  }
}

async function exists(path: string): Promise<boolean> {
  return access(path, constants.F_OK).then(() => true, () => false);
}

async function syncDirectory(path: string): Promise<void> {
  if (process.platform === "win32") return;
  const handle = await open(path, "r");
  try {
    await handle.sync();
  } finally {
    await handle.close();
  }
}

function syncDirectorySync(path: string): void {
  let descriptor: number | undefined;
  try {
    descriptor = openSync(path, "r");
    fsyncSync(descriptor);
    closeSync(descriptor);
    descriptor = undefined;
  } catch (error) {
    if (descriptor !== undefined) closeSync(descriptor);
    if (process.platform !== "win32") throw error;
  }
}

function isMissing(error: unknown): boolean {
  return (error as NodeJS.ErrnoException | undefined)?.code === "ENOENT";
}

function handleUnsupportedMode(error: unknown): void {
  if (process.platform !== "win32") throw error;
}

function readBoundedJsonSync<T>(
  path: string,
  validate: (value: unknown) => value is T,
  maxBytes: number
): { ok: true; value: T } | { ok: false; missing?: boolean } {
  try {
    const info = statSync(path);
    if (!info.isFile() || info.size > maxBytes) return { ok: false };
    const parsed: unknown = JSON.parse(readFileSync(path, "utf8"));
    return validate(parsed) ? { ok: true, value: parsed } : { ok: false };
  } catch (error) {
    return isMissing(error) ? { ok: false, missing: true } : { ok: false };
  }
}

function chmodPrivateSync(path: string, mode: number): void {
  try {
    chmodSync(path, mode);
  } catch (error) {
    handleUnsupportedMode(error);
  }
}
