import { existsSync } from "node:fs";
import { homedir } from "node:os";
import { delimiter, isAbsolute, join } from "node:path";

export interface CommandResolution {
  command: string;
  executable: string;
  resolvedPath?: string;
  available: boolean;
}

export function resolveCommand(command: string | undefined): CommandResolution {
  const trimmed = command?.trim() || "";
  const executable = commandExecutable(trimmed);
  if (!executable) {
    return { command: trimmed, executable: "", available: false };
  }
  const resolvedPath = resolveExecutable(executable);
  return {
    command: trimmed,
    executable,
    resolvedPath,
    available: Boolean(resolvedPath)
  };
}

export function commandExecutable(command: string): string {
  const trimmed = command.trim();
  if (!trimmed) {
    return "";
  }
  if (trimmed.startsWith("\"")) {
    const end = trimmed.indexOf("\"", 1);
    return end > 1 ? trimmed.slice(1, end) : trimmed.slice(1);
  }
  if (trimmed.startsWith("'")) {
    const end = trimmed.indexOf("'", 1);
    return end > 1 ? trimmed.slice(1, end) : trimmed.slice(1);
  }
  return trimmed.split(/\s+/, 1)[0] ?? "";
}

export function commandArgs(command: string): string[] {
  const trimmed = command.trim();
  if (!trimmed) {
    return [];
  }
  if (trimmed.startsWith("\"")) {
    const end = trimmed.indexOf("\"", 1);
    const rest = end > 1 ? trimmed.slice(end + 1) : "";
    return rest.trim().split(/\s+/).filter(Boolean);
  }
  if (trimmed.startsWith("'")) {
    const end = trimmed.indexOf("'", 1);
    const rest = end > 1 ? trimmed.slice(end + 1) : "";
    return rest.trim().split(/\s+/).filter(Boolean);
  }
  const parts = trimmed.split(/\s+/).filter(Boolean);
  return parts.slice(1);
}

export function resolvedCommandWithArgs(command: string, resolvedExecutable: string): string {
  const args = commandArgs(command);
  const executable = quoteExecutableIfNeeded(resolvedExecutable);
  return args.length > 0 ? `${executable} ${args.join(" ")}` : executable;
}

function quoteExecutableIfNeeded(executable: string): string {
  if (!/[\s"']/.test(executable)) {
    return executable;
  }
  if (!executable.includes("\"")) {
    return `"${executable}"`;
  }
  if (!executable.includes("'")) {
    return `'${executable}'`;
  }
  return `"${executable.replace(/"/g, "\\\"")}"`;
}

export function resolveExecutable(executable: string): string | undefined {
  if (!executable.trim()) {
    return undefined;
  }
  if (isAbsolute(executable) || executable.includes("/") || executable.includes("\\")) {
    return existsSync(executable) ? executable : undefined;
  }

  const extensions = process.platform === "win32"
    ? (process.env.PATHEXT?.split(";").filter(Boolean) ?? [".EXE", ".CMD", ".BAT", ".COM"])
    : [""];
  for (const dir of (process.env.PATH ?? "").split(delimiter)) {
    if (!dir) {
      continue;
    }
    for (const extension of extensions) {
      const candidate = join(dir, process.platform === "win32" && executable.toLowerCase().endsWith(extension.toLowerCase())
        ? executable
        : `${executable}${extension}`);
      if (existsSync(candidate)) {
        return candidate;
      }
    }
  }
  for (const candidate of bundledCommandFallbacks(executable)) {
    if (existsSync(candidate)) {
      return candidate;
    }
  }
  return undefined;
}

function bundledCommandFallbacks(executable: string): string[] {
  const home = process.env.HOME || process.env.USERPROFILE || homedir();
  if (!home) {
    return [];
  }
  switch (executable) {
    case "opencode":
      return [join(home, ".opencode", "bin", "opencode")];
    case "devin":
      return [join(home, ".local", "bin", "devin")];
    default:
      return [];
  }
}
