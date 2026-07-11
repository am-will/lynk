import { dirname, isAbsolute, join, relative, resolve, sep } from "node:path";
import { homedir, platform } from "node:os";
import { fileURLToPath } from "node:url";

export interface HostPaths {
  readonly installRoot: string;
  readonly dataRoot: string;
  readonly sessionsRoot: string;
  readonly cacheRoot: string;
  readonly auditRoot: string;
  readonly blobRoot: string;
  readonly tempRoot: string;
  readonly workspaceRoot?: string;
}

export interface HostPathPlatform {
  platform: NodeJS.Platform;
  homeDir: string;
  env: NodeJS.ProcessEnv;
  installRoot?: string;
  dataRoot?: string;
  workspaceRoot?: string;
}

const moduleDir = dirname(fileURLToPath(import.meta.url));
const packagedInstallRoot = resolve(moduleDir, "../..");

export function createHostPaths(options: Partial<HostPathPlatform> = {}): HostPaths {
  const targetPlatform = options.platform ?? platform();
  const homeDir = options.homeDir ?? homedir();
  const env = options.env ?? process.env;
  const installRoot = resolve(options.installRoot ?? packagedInstallRoot);
  const dataRoot = resolve(options.dataRoot ?? (env.PHONE_AGENT_DATA_DIR?.trim() || platformDataRoot(targetPlatform, homeDir, env)));
  const workspace = options.workspaceRoot?.trim() || env.PHONE_AGENT_WORKSPACE_ROOT?.trim();
  return Object.freeze({
    installRoot,
    dataRoot,
    sessionsRoot: join(dataRoot, "sessions"),
    cacheRoot: join(dataRoot, "cache"),
    auditRoot: join(dataRoot, "audit"),
    blobRoot: join(dataRoot, "blobs"),
    tempRoot: join(dataRoot, "tmp"),
    ...(workspace ? { workspaceRoot: resolve(workspace) } : {})
  });
}

export function hostPathsForConfigPath(configPath: string, installRoot?: string): HostPaths {
  return createHostPaths({ dataRoot: dirname(resolve(configPath)), installRoot });
}

export function ownedPath(root: string, ...segments: string[]): string {
  if (segments.length === 0 || segments.some((segment) => !segment || isAbsolute(segment))) {
    throw new Error("Owned path segments must be non-empty relative paths.");
  }
  const canonicalRoot = resolve(root);
  const candidate = resolve(canonicalRoot, ...segments);
  const relation = relative(canonicalRoot, candidate);
  if (!relation || relation === ".." || relation.startsWith(`..${sep}`) || isAbsolute(relation)) {
    throw new Error(`Path escapes its owned root: ${segments.join("/")}`);
  }
  return candidate;
}

function platformDataRoot(targetPlatform: NodeJS.Platform, homeDir: string, env: NodeJS.ProcessEnv): string {
  switch (targetPlatform) {
    case "darwin":
      return join(homeDir, "Library", "Application Support", "Android Agent Bridge");
    case "win32":
      return join(env.ProgramData?.trim() || join(homeDir, "AppData", "Roaming"), "AndroidAgentBridge");
    default:
      return join(env.XDG_CONFIG_HOME?.trim() || join(homeDir, ".config"), "android-agent-bridge");
  }
}
