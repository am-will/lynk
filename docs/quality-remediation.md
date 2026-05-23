# Quality Remediation Checklist

This checklist tracks the maintainability cleanup needed after the multi-harness, app shell, local-model, and phone-control work.

## Structural Targets

- Keep new feature behavior out of files already over 1k lines unless the edit is part of an extraction.
- Move shared PC chat contracts out of OpenClaw-specific modules before adding more Hermes or Codex behavior.
- Keep model metadata in one catalog per platform boundary instead of duplicating context windows, reasoning defaults, or harness prefixes.
- Keep Android services and controllers as lifecycle or UI adapters; put policy and state transitions in pure reducers or focused coordinators.
- Keep local model demo behavior behind explicit demo mode, not in the generic runtime path.

## Current Hotspots

- `android/app/src/main/java/dev/androidagent/AgentForegroundService.kt`
- `android/app/src/main/java/dev/androidagent/OverlayController.kt`
- `android/app/src/main/java/dev/androidagent/settings/SettingsComponents.kt`
- `android/app/src/main/java/dev/androidagent/localmodel/LocalAgentController.kt`
- `pc/src/bridge/OpenClawGatewayNormalizers.ts`
- `pc/src/bridge/harness/InMemoryHarnessSessionStore.ts`
- `pc/src/bridge/HermesModelDiscovery.ts`

## Validation

- PC bridge changes: `cd pc && npm run check && npm test`
- Android reducer, overlay, local model, settings, or protocol changes: `cd android && ./gradlew :app:testDebugUnitTest`
- Android build-impacting changes: `cd android && ./gradlew :app:assembleDebug`
