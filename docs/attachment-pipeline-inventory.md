# Attachment and model import pipeline inventory

This inventory records the pre-R12 data paths and the ownership boundaries the
replacement pipeline enforces. The baseline section intentionally describes the
old implementation so the completed R12 behavior can be reviewed against
concrete failure modes instead of inferred intent.

## Baseline data paths

### Android chat attachments

1. `AppShellActivity` receives a document-provider `Uri` on the main thread.
2. `ChatAttachmentStore.importUri` copies the provider stream directly into
   `filesDir/chat-attachments/<attachment id>-<display name>`.
3. `StoredChatAttachment` exposes the absolute `localPath`; the foreground
   service keeps that object in the composer tray.
4. Host sends call `File.readBytes()`, then create a second full-size Base64
   string, then put that string in the WebSocket JSON frame.
5. Local LiteRT-LM sends read the same absolute path. Local session history
   already stores only `ChatAttachmentPreview`, which is the desired history
   shape.

The nominal per-item limit is 50 MiB. It is checked only after the provider has
already been copied. There is no attachment-count limit, aggregate quota,
free-space reserve, retention policy, cancellation hook, checksum, or startup
cleanup. A provider that omits `OpenableColumns.SIZE` can therefore stream until
storage exhaustion. Direct destination writes leave a plausible final file
after cancellation or I/O failure.

### Android local model import

`MainActivity` and `AppShellActivity` call `LocalModelStore.importModel`
directly from activity-result callbacks. The copy therefore runs on the main
thread and writes directly over `filesDir/local-models/<provider display name>`.
There is no size or aggregate limit, extension validation, checksum, progress,
cancellation, free-space reserve, partial cleanup, or atomic publication. A
failed replacement can destroy the previously working model.

### PC bridge ingress and storage

At baseline, `chat.send.attachments` accepted an optional `contentBase64`
property. The phone WebSocket admitted an approximately 67 MiB frame when it was
present, bypassing the normal control-frame bound. Zod validated a 50 MiB
decoded item, but the JSON parser and Base64 decoder had already materialized
large copies by then.

The baseline bridge had no attachment/blob storage owner. OpenClaw forwarded
the inline object, while Hermes and Codex constructed inline data URLs.
`InMemoryHarnessSessionStore`, pending-run history, fallback history, and
normalized gateway history all used the same payload-capable attachment type,
so Base64 could be retained in process state and persisted in session JSON.

All `/api/*` routes already shared bearer-token authentication. R12 therefore
added a protected streaming blob route without creating a second authentication
mechanism, and additionally bound each blob to the uploading Android `deviceId`
and target chat `sessionKey`.

## Replacement ownership and limits

| Boundary | Owner | Item limit | Count / aggregate | Retention and publication |
| --- | --- | ---: | ---: | --- |
| Android chat blob | app-private blob store | 50 MiB | 32 blobs / 256 MiB | 7 days; temp-to-final rename; 128 MiB free-space reserve |
| Android LiteRT-LM model | app-private model store | 4 GiB | 3 models / 8 GiB | old model retained until new model is completely verified and atomically published; 512 MiB reserve |
| PC uploaded chat blob | bridge blob store | 50 MiB | 256 blobs / 1 GiB | 24 hours; temp-to-final rename; 512 MiB reserve |
| Legacy inline adapter payload | backend compatibility boundary only | 8 MiB | one turn only | never accepted from the phone and never stored in history |

Every streaming writer must enforce its cap from bytes actually read, not only
from a declared length. Declared oversize is rejected before opening a target;
unknown length is stopped at the same hard cap. Cancellation, disconnect,
checksum mismatch, short bodies, and write failures remove the temporary file.
Startup cleanup removes stale partial files, orphaned metadata/payload pairs,
expired blobs, and traversal-shaped names.

## Implemented contracts

- Android composer and local-model imports run on `Dispatchers.IO` and expose
  byte progress plus coroutine cancellation.
- Android chat wire messages contain sanitized metadata, a content/blob ID, and
  a SHA-256 checksum only. They never contain an absolute path or Base64.
- Android uploads stream the app-private file to an authenticated HTTP route.
- The PC blob metadata records `deviceId` and `sessionKey`. Resolution and
  protected download require both to match; a shared bearer token alone is not
  sufficient authorization for a blob.
- The bridge resolves a blob reference only at the selected harness boundary.
  Codex receives a local image path. A backend that has no file-path transport
  may use the explicitly bounded 8 MiB compatibility encoder.
- Host and local histories store only attachment metadata/content IDs. Runtime
  paths and payload-bearing compatibility objects are separate types and must
  never enter history serialization.

## Characterization matrix

The baseline guards are captured in protocol and Android policy tests. The
streaming-store commits extend the matrix with the cases that the baseline has
no safe abstraction for:

| Case | Expected result |
| --- | --- |
| Declared item over cap | rejected before a temp file is created |
| Unknown-length stream over cap | copy stops at the cap and temp is deleted |
| Cancellation / disconnected upload | temp is deleted and no metadata is published |
| Short declared body / checksum mismatch | rejected and temp is deleted |
| Existing model plus failed replacement | existing model path and bytes remain intact |
| Cross-device or cross-session blob lookup | not found/forbidden without revealing metadata |
| Restart with partial/orphan/expired files | bounded cleanup removes them |
| Persisted harness history | contains metadata/reference only, never Base64 or local paths |
