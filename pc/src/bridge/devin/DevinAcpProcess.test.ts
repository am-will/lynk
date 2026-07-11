import { mkdtempSync, rmSync, writeFileSync, chmodSync, constants } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { DevinAcpError } from "./DevinAcpTypes.js";
import { createDefaultDevinAcpProcessFactory, resolveDevinAcpCommand } from "./DevinAcpProcess.js";
import { buildClient, createFakeDevinProcess } from "./DevinAcpFixtures.js";

const MAX_STDERR_BYTES = 8192;

describe("DevinAcpClient process lifecycle", () => {
  describe("diagnostics and stderr", () => {
    it("captures bounded and sanitized stderr in health snapshot", async () => {
      const { process, agent, pushStderr } = createFakeDevinProcess();
      const client = buildClient({ process });
      await client.ensureStarted();

      pushStderr("token=secret123 first line");
      pushStderr("password=hidden second line");
      for (let i = 0; i < 30; i += 1) {
        pushStderr(`noise line ${i}`);
      }
      await new Promise((resolve) => setTimeout(resolve, 20));

      const midHealth = client.health();
      assert.ok(midHealth.stderr.includes("first line"));
      assert.ok((midHealth.stderr.match(/noise line/g) ?? []).length === 30);

      for (let i = 30; i < 60; i += 1) {
        pushStderr(`noise line ${i}`);
      }
      pushStderr("final line");
      await new Promise((resolve) => setTimeout(resolve, 20));

      const health = client.health();
      assert.ok(!health.stderr.includes("secret123"));
      assert.ok(!health.stderr.includes("hidden"));
      assert.ok(health.stderr.includes("final line"));
      assert.ok((health.stderr.match(/noise line/g) ?? []).length <= 50);

      await client.close();
      void agent;
    });

    it("does not capture stdout into health snapshot", async () => {
      const { process, agent } = createFakeDevinProcess();
      const client = buildClient({ process });
      await client.ensureStarted();

      const health = client.health();
      assert.ok(!health.stderr.includes("stdout"));

      await client.close();
      void agent;
    });

    it("bounds a single long stderr line by UTF-8 bytes and sanitizes it", async () => {
      const { process, agent, pushStderr } = createFakeDevinProcess();
      const client = buildClient({ process });
      await client.ensureStarted();

      const prefix = "LONG_LINE_PREFIX";
      const suffix = "END_OF_LONG_LINE";
      const filler = "x".repeat(MAX_STDERR_BYTES + 200);
      pushStderr(`${prefix}${filler}${suffix}`);
      await new Promise((resolve) => setTimeout(resolve, 20));

      const health = client.health();
      assert.ok(!health.stderr.includes(prefix));
      assert.ok(health.stderr.includes(suffix));
      assert.ok(Buffer.byteLength(health.stderr, "utf8") <= MAX_STDERR_BYTES);

      await client.close();
      void agent;
    });

    it("redacts a long credential split across chunks and resumes after newline", async () => {
      const { process, agent, pushStderrRaw } = createFakeDevinProcess();
      const client = buildClient({ process });
      await client.ensureStarted();

      const secret = "SECRET".repeat(1400);
      const normalLine = "normal diagnostic line";

      pushStderrRaw("tok");
      pushStderrRaw(`en=${secret}`);
      pushStderrRaw(secret.slice(0, secret.length / 2));
      pushStderrRaw(`${secret.slice(secret.length / 2)}\n${normalLine}\n`);
      await new Promise((resolve) => setTimeout(resolve, 50));

      const health = client.health();
      assert.ok(!health.stderr.includes(secret));
      assert.ok(!health.stderr.includes("SECRET"));
      assert.ok(health.stderr.includes("[sensitive stderr line redacted]"));
      assert.ok(health.stderr.includes(normalLine));
      assert.ok(Buffer.byteLength(health.stderr, "utf8") <= MAX_STDERR_BYTES);

      await client.close();
      void agent;
    });
  });

  describe("process lifecycle", () => {
    it("closes process and settles to stopped without reporting unexpected exit", async () => {
      const { process, agent } = createFakeDevinProcess();
      const client = buildClient({ process });
      await client.ensureStarted();

      await client.close();

      const health = client.health();
      assert.equal(health.state, "stopped");
      assert.notEqual(health.error?.code, "unexpected_exit");
      void agent;
    });

    it("resolves command, args, and cwd through the default factory without shell", async () => {
      const tempDir = mkdtempSync(join(tmpdir(), "devin-acp-"));
      const fakeBin = join(tempDir, "devin");
      writeFileSync(fakeBin, "#!/bin/sh\nexit 0\n");
      chmodSync(fakeBin, constants.S_IXUSR | constants.S_IRUSR | constants.S_IWUSR);

      const factory = createDefaultDevinAcpProcessFactory();
      const proc = await factory.create({
        command: fakeBin,
        cwd: tempDir
      });

      assert.equal(proc.executable, fakeBin);
      assert.deepEqual(proc.args, []);
      assert.equal(proc.cwd, tempDir);

      proc.kill();
      await proc.exited;
      rmSync(tempDir, { recursive: true, force: true });
    });

    it("handles a quoted executable path with spaces and no shell", async () => {
      const tempDir = mkdtempSync(join(tmpdir(), "devin acp "));
      const fakeBin = join(tempDir, "devin acp");
      writeFileSync(fakeBin, "#!/bin/sh\nexit 0\n");
      chmodSync(fakeBin, constants.S_IXUSR | constants.S_IRUSR | constants.S_IWUSR);

      const resolved = resolveDevinAcpCommand(`"${fakeBin}" arg1 arg2`);
      assert.equal(resolved.resolvedPath, fakeBin);
      assert.deepEqual(resolved.args, ["arg1", "arg2"]);

      rmSync(tempDir, { recursive: true, force: true });
    });

    it("throws missing_executable when the command cannot be resolved", () => {
      try {
        resolveDevinAcpCommand("/nonexistent/devin acp");
        assert.fail("expected missing_executable");
      } catch (error) {
        assert.ok(error instanceof DevinAcpError);
        assert.equal((error as DevinAcpError).code, "missing_executable");
      }
    });

    it("sanitizes secrets in missing_executable messages", () => {
      const secret = "token=super-secret-value";
      try {
        resolveDevinAcpCommand(`/nonexistent/devin acp --token super-secret-value ${secret}`);
        assert.fail("expected missing_executable");
      } catch (error) {
        assert.ok(error instanceof DevinAcpError);
        const message = (error as DevinAcpError).message;
        assert.ok(!message.includes("super-secret-value"));
        assert.ok(message.includes("[redacted]"));
        assert.ok(message.includes("/nonexistent/devin"));
      }
    });

    it("reports spawn failure through exited when the child cannot be executed", async () => {
      const tempDir = mkdtempSync(join(tmpdir(), "devin-acp-"));
      const fakeBin = join(tempDir, "devin");
      writeFileSync(fakeBin, "#!/bin/sh\nexit 0\n");
      chmodSync(fakeBin, constants.S_IRUSR | constants.S_IWUSR);

      const factory = createDefaultDevinAcpProcessFactory();
      const proc = await factory.create({
        command: fakeBin,
        cwd: tempDir
      });

      const exit = await proc.exited;
      assert.equal(exit.code, null);
      assert.equal(exit.signal, null);
      assert.ok(exit.spawnError);

      rmSync(tempDir, { recursive: true, force: true });
    });
  });
});
