# BYDMate private diagnostics receiver

This Node 22+ receiver validates protocol v1 before writing a batch. It has no npm dependencies. This revision provides local code and tests only; it does not configure Tailscale, launch a persistent receiver, or change the Mini.

## Local commands

From the repository root:

```sh
node --test tools/remote-diagnostics-receiver/test/receiver.test.mjs
```

The test module publishes `acceptedBatchJson`, `acceptedImportJson`, `rejectedBatchJson`, and `rejectedImportJson`. Step 2 must reuse these wire compatibility vectors. Export all vectors as a JSON document without running tests:

```sh
node tools/remote-diagnostics-receiver/test/receiver.test.mjs --fixtures
```

For a later Mini installation, run provisioning once per app install. Supply an absolute token-registry path in a private directory, outside the batch data directory:

```sh
node tools/remote-diagnostics-receiver/src/provision-install.mjs /private/receiver/tokens.json
```

The command generates one random 256-bit bearer token, adds only its SHA-256 hash to the registry, and prints the path of a temporary `import.json`. The temporary directory has mode `0700`; the import and registry have mode `0600`. Transfer the import privately and delete the temporary directory after importing it. The import contains the only plaintext token saved by provisioning. It has no automatic expiration. Do not commit it or include it in logs. Revocation means removing its hash from the registry with an atomic file replacement; each request reloads the registry. Provisioning refuses concurrent registry updates using a temporary lock directory. After a killed provisioning process, inspect and remove its stale `<registry>.lock` before retrying.

For a later launch (not performed by this step):

```sh
node tools/remote-diagnostics-receiver/src/server.mjs /private/receiver/batches /private/receiver/tokens.json 8787
```

The listener binds only `127.0.0.1`. The library also permits explicit `::1`, and rejects wildcard or implicit addresses. Run exactly one receiver process per data directory. The directory is dedicated to batch records and temporary writes. Do not place the registry, unrelated files, or symlinks there. The owner of this private directory is trusted.

The trusted Tailscale Serve route is the ingress boundary. Configure that route separately for the exact external endpoint `https://bobs-mac-mini-openclaw.tail3549c7.ts.net:9443`. Requests reaching loopback must preserve the exact Host value `bobs-mac-mini-openclaw.tail3549c7.ts.net:9443`. There is no localhost Host exception: local clients and tests must set this value explicitly. A different configured host is rejected at receiver construction. `Forwarded` and `X-Forwarded-*` never affect host validation, authentication, or routing. This code does not verify or configure the Serve route; keep the listener loopback-only.

## HTTP contract

`POST /v1/diagnostic-batches` requires exactly one each of:

- `Host: bobs-mac-mini-openclaw.tail3549c7.ts.net:9443`
- `Authorization: Bearer <43-character base64url token>`
- `Content-Type: application/json` (no content encoding)
- `X-BYDMate-Batch: <batchId>`
- `X-BYDMate-Digest: sha256=<64 lowercase hexadecimal SHA-256 digits>`

The digest covers the exact body bytes, including whitespace. The body must be valid UTF-8 JSON and at most 65,536 bytes (64 KiB). `batchId` must equal `X-BYDMate-Batch`. All validation completes before a body is persisted.

Success is exactly `200 {"batchId":"<batchId>","digest":"sha256=<hash>"}`. A repeat of the same bytes for the same token hash and batch ID returns the same acknowledgement. Reuse of that ID with a different digest returns `409`. Different tokens have separate ID namespaces. After retention evicts a record, that ID no longer has a stored duplicate check.

Errors have an empty JSON object body: `400` invalid schema/headers/digest, `401` missing/invalid/revoked bearer, `404` wrong method/path, `413` too large, `421` wrong Host, or `503` storage/registry/capacity failure. HTTP parser errors and connection timeouts may close the connection or use Node's HTTP parser response. Clients must retry uncertain writes using identical bytes and ID.

## Shared wire contract — protocol version 1

Reject additional keys at **every** object level. Reject missing required fields, null optional fields, invalid typed data, arbitrary text, messages, URLs, token-like values, coordinates, invalid enums, and out-of-range values. The only strings accepted are the exact constrained fields below. Integer timestamps and counters are in `0..9007199254740991`. UUIDs use lowercase canonical `8-4-4-4-12` hexadecimal syntax. Hashes use exactly 64 lowercase hexadecimal characters.

The batch has exactly these required keys:

- `schemaVersion`: integer `1`.
- `batchId`: UUID; equals `X-BYDMate-Batch`.
- `createdAtMs`: integer timestamp.
- `app`: exact fields below.
- `environment`: exact fields below.
- `logcat`: exact fields below.
- `events`: array of typed event objects below.
- `counters`: exact fields below.

`app` has exactly:

- `version`: 1–32 ASCII characters, digits, dots, and hyphens only.
- `versionCode`: positive integer, at most `9007199254740991`.
- `buildType`: `debug` or `release`.
- `installIdHash`: SHA-256 hex.

`environment` has exactly:

- `apiLevel`: integer `29..100`.
- `manufacturer`: `byd`, `google`, or `other`.
- `model`: `head_unit`, `emulator`, or `other`.
- `capabilities`: exactly the boolean keys `microphone`, `overlay`, `notificationListener`.

Manufacturer and model are mapped categories, never raw Android Build strings.

`logcat` has exactly:

- `entries`: ordered array of typed event objects below.
- `droppedUnrecognisedLines`: bounded integer counter.
- `truncatedLogEvents`: bounded integer counter.

`events` contains the same typed event objects. The app must produce these only through explicit approved TraceJournal mappings. Unknown journal entries are dropped, never serialized generically. The receiver validates the wire shape; it cannot verify which app mapping produced a valid event.

`counters` has exactly the bounded integer keys `captureDropped`, `queueDropped`, and `storageFailures`.

Every event has required `type` and `atMs` (bounded integer timestamp). Its remaining exact fields depend on its type. Optional fields may be absent, never null:

| Type | Required fields in addition to type/atMs | Optional fields |
| --- | --- | --- |
| `voice.lifecycle` | `state`, `durationMs` | `reason` |
| `voice.failure` | `stage`, `reason`, `exceptionClass` | `durationMs` |
| `connection.request` | `backend`, `operation`, `durationMs`, `outcome` | `httpStatus` |
| `connection.failure` | `backend`, `phase`, `reason`, `exceptionClass`, `retryable` | `httpStatus` |

- Voice `durationMs`: integer `0..300000`.
- Connection request `durationMs`: integer `0..120000`.
- `httpStatus`: integer `100..599`.
- `retryable`: boolean.
- Voice `state`/`stage`: `preparing`, `listening`, `processing`, `running_command`, `waiting_assistant`, `speaking`, `done`, `refused`, `failed`, `stopped`.
- `reason`: `none`, `cancelled`, `timeout`, `not_ready`, `permission`, `unavailable`, `transport`, `http`, `protocol`, `storage`, `rejected`, `unknown`.
- `backend`: `local_asr`, `local_tts`, `openrouter`, `openclaw`.
- `operation`: `load`, `recognize`, `synthesize`, `chat`, `prewarm`.
- `outcome`: `success`, `failure`, `cancelled`.
- `phase`: `connect`, `request`, `response`, `decode`.
- `exceptionClass`: `none`, `IOException`, `SocketTimeoutException`, `SSLException`, `IllegalStateException`, `CancellationException`, `Other`. The app must map every unrecognized exception class to `Other`; never send an exception message. The receiver rejects unrecognized class strings.

The import document has exactly these required fields:

```json
{
  "schemaVersion": 1,
  "endpoint": "https://bobs-mac-mini-openclaw.tail3549c7.ts.net:9443",
  "bearerToken": "<43-character base64url token>"
}
```

The placeholder is explanatory; provisioning supplies the real token. Reject any missing, unknown, or invalid field. The endpoint must match literally, with no trailing slash, alternate port, path, query, or other host.

## Persistence and retention

Each `(token hash, batch ID)` maps to a private `0600` `.batch.json` record. Its lookup metadata (hash, ID, digest, receiver timestamp) and base64-encoded exact body bytes are written together to a temporary file, synced, atomically renamed, and followed by directory sync before acknowledgement. Per-process writes are serialized. If a write fails, the receiver returns `503`; a committed rename can still be recovered by retry. Startup and maintenance discard interrupted `.tmp` files. Tests inject failure just before rename; they do not simulate a physical power loss.

Retention uses receiver acceptance time, not the caller's timestamp. The receiver evicts the oldest records to stay within 100 MiB of record bytes, including the base64 and index overhead, and removes records at age 14 days. Retention runs at startup, before requests/writes, after writes, and on a timer (at most 60 seconds between maintenance runs, scheduled earlier for the next expiry). Space is reserved by eviction before a temporary write. Filesystem block overhead and the separate small token registry are outside this record-byte limit. The clock must be correct; retention cannot run while the process or filesystem is unavailable, and startup performs overdue cleanup before listening.

Request logs contain only `tokenHashPrefix`, `batchId`, `bytes`, `outcome`, and fixed `reason` codes. Malformed IDs and bearer tokens are never logged. Bodies, exception messages, raw tokens, and forwarded headers are never logged. CLI failures emit only fixed error codes. Library test seams (`now`, a smaller `maxBytes`, `beforeRename`, and `logger`) do not change the production CLI limits.
