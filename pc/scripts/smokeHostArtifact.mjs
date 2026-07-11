import assert from "node:assert/strict";
import { access, mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { resolve } from "node:path";
import { spawn, spawnSync } from "node:child_process";
import { createServer } from "node:net";
import { fileURLToPath } from "node:url";

const scriptDirectory = resolve(fileURLToPath(new URL(".", import.meta.url)));
const pcRoot = resolve(scriptDirectory, "..");
const temporaryRoot = await mkdtemp(resolve(tmpdir(), "lynk-host-artifact-"));
const packageRootArgument = valueAfter("--package-root");

try {
  const packageRoot = packageRootArgument
    ? resolve(process.cwd(), packageRootArgument)
    : await installPackedArtifact();
  await smokePackageRoot(packageRoot);
  console.log(`Host artifact smoke test passed: ${packageRoot}`);
} finally {
  await rm(temporaryRoot, { recursive: true, force: true });
}

async function installPackedArtifact() {
  const pack = run("npm", ["pack", "--json", "--pack-destination", temporaryRoot], pcRoot);
  const packResult = JSON.parse(pack.stdout);
  assert.equal(packResult.length, 1, "npm pack should produce exactly one artifact");
  const tarball = resolve(temporaryRoot, packResult[0].filename);
  const consumerRoot = resolve(temporaryRoot, "consumer");
  await mkdir(consumerRoot);
  await writeFile(resolve(consumerRoot, "package.json"), "{\"private\":true}\n");
  run("npm", ["install", "--ignore-scripts", "--omit=dev", "--no-audit", "--no-fund", tarball], consumerRoot);
  return resolve(consumerRoot, "node_modules/lynk-bridge");
}

async function smokePackageRoot(packageRoot) {
  const manifest = JSON.parse(await readFile(resolve(packageRoot, "package.json"), "utf8"));
  await Promise.all([
    access(resolve(packageRoot, "README.md")),
    access(resolve(packageRoot, ".env.example"))
  ]);
  await assert.rejects(access(resolve(packageRoot, "android")), "artifact must not depend on the Android source tree");
  const expectedBins = ["lynk-bridge", "lynk-bridge-host", "lynk-bridge-mcp"];
  assert.deepEqual(Object.keys(manifest.bin).sort(), expectedBins.sort());

  const hostResult = run(process.execPath, [resolve(packageRoot, manifest.bin["lynk-bridge-host"]), "help"], temporaryRoot, smokeEnvironment());
  assert.match(hostResult.stdout, /lynk-bridge host commands/);

  await assertLongRunningBin(resolve(packageRoot, manifest.bin["lynk-bridge-mcp"]), smokeEnvironment());
  await assertBridgeHealth(resolve(packageRoot, manifest.bin["lynk-bridge"]));
}

async function assertLongRunningBin(binPath, environment) {
  const child = spawn(process.execPath, [binPath], {
    cwd: temporaryRoot,
    env: environment,
    stdio: ["pipe", "pipe", "pipe"]
  });
  const stderr = collect(child.stderr);
  await Promise.race([
    waitForExit(child).then(({ code, signal }) => {
      throw new Error(`MCP bin exited during startup (code=${code}, signal=${signal}): ${stderr()}`);
    }),
    delay(500)
  ]);
  await terminate(child);
}

async function assertBridgeHealth(binPath) {
  const port = await availablePort();
  const environment = smokeEnvironment({
    PHONE_AGENT_HOST: "127.0.0.1",
    PHONE_AGENT_PORT: String(port),
    PHONE_AGENT_BRIDGE_URL: `http://127.0.0.1:${port}`,
    PHONE_AGENT_TOKEN: "artifact-smoke-token-with-at-least-32-characters"
  });
  const child = spawn(process.execPath, [binPath], {
    cwd: temporaryRoot,
    env: environment,
    stdio: ["ignore", "pipe", "pipe"]
  });
  const stdout = collect(child.stdout);
  const stderr = collect(child.stderr);
  try {
    const response = await waitForHealth(`http://127.0.0.1:${port}/health`, child);
    assert.equal(response.status, 200);
    const body = await response.json();
    assert.equal(body.ok, true);
  } catch (error) {
    throw new Error(`Bridge health smoke failed: ${error}\nstdout:\n${stdout()}\nstderr:\n${stderr()}`);
  } finally {
    await terminate(child);
  }
}

function smokeEnvironment(overrides = {}) {
  return {
    ...process.env,
    HOME: temporaryRoot,
    XDG_CONFIG_HOME: resolve(temporaryRoot, "config"),
    PHONE_AGENT_CONFIG_DIR: resolve(temporaryRoot, "config"),
    LYNK_BRIDGE_SKIP_SERVICE_INSTALL: "1",
    ...overrides
  };
}

async function waitForHealth(url, child) {
  for (let attempt = 0; attempt < 80; attempt += 1) {
    if (child.exitCode !== null || child.signalCode !== null) {
      throw new Error(`bridge exited before health check (code=${child.exitCode}, signal=${child.signalCode})`);
    }
    try {
      return await fetch(url);
    } catch {
      await delay(100);
    }
  }
  throw new Error("bridge did not become healthy within 8 seconds");
}

async function availablePort() {
  const server = createServer();
  await new Promise((resolvePromise, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolvePromise);
  });
  const address = server.address();
  assert(address && typeof address === "object");
  await new Promise((resolvePromise, reject) => server.close((error) => error ? reject(error) : resolvePromise()));
  return address.port;
}

function run(command, args, cwd, env = process.env) {
  const result = spawnSync(command, args, { cwd, env, encoding: "utf8" });
  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(" ")} failed (${result.status})\nstdout:\n${result.stdout}\nstderr:\n${result.stderr}`);
  }
  return result;
}

function collect(stream) {
  let value = "";
  stream?.setEncoding("utf8");
  stream?.on("data", (chunk) => { value = (value + chunk).slice(-16_384); });
  return () => value;
}

async function terminate(child) {
  if (child.exitCode !== null || child.signalCode !== null) return;
  child.kill("SIGTERM");
  await Promise.race([waitForExit(child), delay(2_000)]);
  if (child.exitCode === null && child.signalCode === null) {
    child.kill("SIGKILL");
    await waitForExit(child);
  }
}

function waitForExit(child) {
  if (child.exitCode !== null || child.signalCode !== null) {
    return Promise.resolve({ code: child.exitCode, signal: child.signalCode });
  }
  return new Promise((resolvePromise) => child.once("exit", (code, signal) => resolvePromise({ code, signal })));
}

function delay(milliseconds) {
  return new Promise((resolvePromise) => setTimeout(resolvePromise, milliseconds));
}

function valueAfter(name) {
  const index = process.argv.indexOf(name);
  if (index === -1) return undefined;
  const value = process.argv[index + 1];
  assert(value, `${name} requires a path`);
  return value;
}
