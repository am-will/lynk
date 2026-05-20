import { mkdir, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { PhoneToolClient, screenshotDirectory } from "./phoneToolClient.js";

type ToolHandlerArgs = Record<string, unknown>;

function toolResult(value: unknown) {
  return {
    content: [
      {
        type: "text" as const,
        text: JSON.stringify(value, null, 2)
      }
    ]
  };
}

function toolError(error: unknown) {
  return {
    isError: true,
    content: [
      {
        type: "text" as const,
        text: error instanceof Error ? error.message : String(error)
      }
    ]
  };
}

function register(
  server: McpServer,
  client: PhoneToolClient,
  name: string,
  description: string,
  schema: z.ZodRawShape,
  command: Parameters<PhoneToolClient["command"]>[0],
  mapArgs: (args: ToolHandlerArgs) => Record<string, unknown> = (args) => args
): void {
  server.tool(name, description, schema, async (args) => {
    try {
      return toolResult(await client.command(command, mapArgs(args)));
    } catch (error) {
      return toolError(error);
    }
  });
}

export function registerPhoneTools(server: McpServer, client = new PhoneToolClient()): void {
  register(server, client, "phone_observe", "Observe the current Android screen.", {}, "observe_screen");

  register(
    server,
    client,
    "phone_open_app",
    "Open an Android app by package name or visible app label.",
    {
      packageName: z.string().optional(),
      appName: z.string().optional()
    },
    "open_app"
  );

  register(server, client, "phone_tap_node", "Tap an observed accessibility node. The result includes a fresh post-tap observation; do not call phone_observe again unless the result is ambiguous or stale.", { nodeId: z.string() }, "tap_node");
  register(server, client, "phone_tap_xy", "Tap screen coordinates. The result includes a fresh post-tap observation; do not call phone_observe again unless the result is ambiguous or stale.", { x: z.number(), y: z.number() }, "tap_xy");
  register(
    server,
    client,
    "phone_tap_normalized",
    "Tap normalized full-screen coordinates from 0 to 1. Use this for coordinates derived from a screenshot shown at a scaled size. The result includes a fresh post-tap observation.",
    { xPct: z.number().min(0).max(1), yPct: z.number().min(0).max(1) },
    "tap_normalized"
  );
  register(server, client, "phone_long_press_node", "Long press an observed accessibility node. The result includes a fresh post-action observation.", { nodeId: z.string() }, "long_press_node");
  register(
    server,
    client,
    "phone_type_text",
    "Type text into the focused field. The Android client first tries Accessibility ACTION_SET_TEXT and falls back to clipboard paste for apps that reject direct text setting. The result includes a fresh post-type observation.",
    { text: z.string() },
    "type_text"
  );
  register(
    server,
    client,
    "phone_scroll",
    "Scroll the active Android screen. The result includes a fresh post-scroll observation.",
    { direction: z.enum(["up", "down", "left", "right"]) },
    "scroll"
  );
  register(
    server,
    client,
    "phone_swipe",
    "Swipe between two screen coordinates.",
    {
      startX: z.number(),
      startY: z.number(),
      endX: z.number(),
      endY: z.number(),
      durationMs: z.number().optional()
    },
    "swipe"
  );
  register(server, client, "phone_press_back", "Press Android Back.", {}, "press_back");
  register(server, client, "phone_press_home", "Press Android Home.", {}, "press_home");
  register(server, client, "phone_open_recents", "Open Android recents.", {}, "open_recents");
  server.tool("phone_take_screenshot", "Take an Android screenshot if supported and save it as a local PNG file.", {}, async () => {
    try {
      const result = await client.command("take_screenshot", {});
      if (!result.screenshotBase64) {
        return toolResult(result);
      }

      const dir = screenshotDirectory();
      await mkdir(dir, { recursive: true });
      const filePath = join(dir, `phone-${Date.now()}.png`);
      await writeFile(filePath, Buffer.from(result.screenshotBase64, "base64"));
      return toolResult({
        ...result,
        screenshotBase64: undefined,
        screenshotPath: filePath
      });
    } catch (error) {
      return toolError(error);
    }
  });
  register(
    server,
    client,
    "phone_ask_user_confirmation",
    "Ask the user to confirm a sensitive phone action.",
    {
      message: z.string(),
      preview: z.string().optional()
    },
    "ask_user_confirmation"
  );
  register(server, client, "phone_wait", "Wait on the Android device, then observe. Use only for visible loading/animation; prefer 300-1000 ms and avoid multi-second waits unless the screen is clearly still changing.", { ms: z.number().int().min(0).max(120_000) }, "wait");
}
