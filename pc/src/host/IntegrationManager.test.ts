import assert from "node:assert/strict";
import test from "node:test";
import { buildDevinIntegrationStatus } from "./IntegrationManager.js";

test("buildDevinIntegrationStatus marks installed commands configured even when auth times out or spawn fails", () => {
  const resolution = { available: true, resolvedPath: "/opt/devin/bin/devin", command: "devin acp" };

  const notAuthenticated = buildDevinIntegrationStatus(resolution, { status: "not_authenticated", path: "/opt/devin/bin/devin" });
  assert.equal(notAuthenticated.installed, true);
  assert.equal(notAuthenticated.configured, true);
  assert.equal(notAuthenticated.ready, false);

  const timeout = buildDevinIntegrationStatus(resolution, { status: "timeout", path: "/opt/devin/bin/devin" });
  assert.equal(timeout.installed, true);
  assert.equal(timeout.configured, true);
  assert.equal(timeout.ready, false);

  const spawnError = buildDevinIntegrationStatus(resolution, { status: "spawn_error", path: "/opt/devin/bin/devin" });
  assert.equal(spawnError.installed, true);
  assert.equal(spawnError.configured, true);
  assert.equal(spawnError.ready, false);

  const notInstalled = buildDevinIntegrationStatus({ available: false, command: "devin acp" }, { status: "not_installed" });
  assert.equal(notInstalled.installed, false);
  assert.equal(notInstalled.configured, false);
  assert.equal(notInstalled.ready, false);
});

test("buildDevinIntegrationStatus marks ready only when authenticated", () => {
  const authenticated = buildDevinIntegrationStatus(
    { available: true, resolvedPath: "/opt/devin/bin/devin", command: "devin acp" },
    { status: "authenticated", path: "/opt/devin/bin/devin" }
  );
  assert.equal(authenticated.installed, true);
  assert.equal(authenticated.configured, true);
  assert.equal(authenticated.ready, true);
});
