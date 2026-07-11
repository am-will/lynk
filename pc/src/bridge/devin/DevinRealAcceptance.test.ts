import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { randomUUID } from "node:crypto";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { it } from "node:test";
import { promisify } from "node:util";

import { DevinAcpClient } from "./DevinAcpClient.js";
import { DevinSessionUpdateCollector } from "./DevinHistoryReplay.js";

const enabled = process.env.LYNK_DEVIN_REAL_ACCEPTANCE === "1" || process.env.npm_lifecycle_event === "test:devin:real";
const REAL_TIMEOUT_MS = 15 * 60_000;
const execFileAsync = promisify(execFile);

it(
  "recovers a real authenticated Devin session through list/load/history/context after process restart",
  { skip: enabled ? false : "set LYNK_DEVIN_REAL_ACCEPTANCE=1 to run authenticated Devin ACP acceptance", timeout: REAL_TIMEOUT_MS * 2 },
  async () => {
    const workspace = mkdtempSync(join(tmpdir(), "lynk-devin-real-acceptance-"));
    const marker = `LYNK_REAL_RESTART_${randomUUID().replaceAll("-", "")}`;
    let sessionId = "not-created";
    let first: DevinAcpClient | undefined;
    let second: DevinAcpClient | undefined;

    console.log(`[devin-real] stage=start workspace=${workspace}`);
    try {
      const version = await execFileAsync("devin", ["--version"], { timeout: 10_000, maxBuffer: 16_384 });
      assert.match(version.stdout, /^devin 3000\.1\.27\b/);
      console.log("[devin-real] stage=cli-version version=3000.1.27");
      first = new DevinAcpClient({
        command: "devin acp",
        cwd: workspace,
        startupTimeoutMs: 60_000,
        requestTimeoutMs: REAL_TIMEOUT_MS
      });
      const firstCapabilities = await first.ensureStarted();
      assert.equal(firstCapabilities.listSessions, true);
      assert.equal(firstCapabilities.loadSession, true);
      console.log(
        `[devin-real] stage=negotiated list=${firstCapabilities.listSessions} load=${firstCapabilities.loadSession}`
      );

      const created = await first.sessionNew({ cwd: workspace, mcpServers: [] });
      sessionId = created.sessionId;
      console.log(`[devin-real] stage=created session=${sessionId}`);
      const firstReplay = new DevinSessionUpdateCollector(first);
      await first.sessionPrompt(
        {
          sessionId,
          prompt: [{
            type: "text",
            text: `Remember this exact marker for a process-restart test: ${marker}. Reply only with MARKER_STORED.`
          }]
        },
        { timeoutMs: REAL_TIMEOUT_MS }
      );
      firstReplay.detach();
      await first.close();
      first = undefined;
      console.log(`[devin-real] stage=first-process-closed session=${sessionId}`);

      second = new DevinAcpClient({
        command: "devin acp",
        cwd: workspace,
        startupTimeoutMs: 60_000,
        requestTimeoutMs: REAL_TIMEOUT_MS
      });
      const secondCapabilities = await second.ensureStarted();
      assert.equal(secondCapabilities.listSessions, true);
      assert.equal(secondCapabilities.loadSession, true);

      let cursor: string | null | undefined;
      let found = false;
      do {
        const page = await second.sessionList(cursor ? { cursor } : {});
        found ||= page.sessions.some((session) => session.sessionId === sessionId);
        cursor = page.nextCursor;
      } while (!found && cursor);
      assert.equal(found, true, `session/list did not return ${sessionId}`);
      console.log(`[devin-real] stage=listed session=${sessionId}`);

      const replay = new DevinSessionUpdateCollector(second);
      await second.sessionLoad(
        { sessionId, cwd: workspace, mcpServers: [] },
        { timeoutMs: REAL_TIMEOUT_MS }
      );
      const loadedHistory = replay.snapshot(sessionId).messages;
      assert.ok(
        loadedHistory.some((message) => message.role === "user" && message.text.includes(marker)),
        "session/load replay did not contain the unique marker"
      );
      console.log(`[devin-real] stage=loaded historyMessages=${loadedHistory.length} session=${sessionId}`);

      await second.sessionPrompt(
        {
          sessionId,
          prompt: [{
            type: "text",
            text: "What exact process-restart marker did I ask you to remember? Reply with only that marker."
          }]
        },
        { timeoutMs: REAL_TIMEOUT_MS }
      );
      const recovered = replay.snapshot(sessionId).messages
        .filter((message) => message.role === "assistant")
        .at(-1)?.text ?? "";
      assert.ok(recovered.includes(marker), "fresh-process response did not recover the marker from session context");
      replay.detach();
      console.log(`[devin-real] stage=pass session=${sessionId} workspace=${workspace}`);
    } finally {
      await first?.close().catch(() => undefined);
      await second?.close().catch(() => undefined);
      rmSync(workspace, { recursive: true, force: true });
      console.log(`[devin-real] stage=local-cleanup session=${sessionId} workspace=${workspace}`);
    }
  }
);
