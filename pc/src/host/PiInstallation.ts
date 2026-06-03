import { existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const defaultPcRoot = resolve(scriptDir, "../..");

export function piSdkPackagePath(pcRoot = defaultPcRoot): string {
  return resolve(pcRoot, "node_modules", "@earendil-works", "pi-coding-agent", "package.json");
}

export function isPiSdkInstalled(pcRoot = defaultPcRoot): boolean {
  return existsSync(piSdkPackagePath(pcRoot));
}
