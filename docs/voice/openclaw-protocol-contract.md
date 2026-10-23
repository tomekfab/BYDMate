# OpenClaw Gateway transport contract (BYDMate chat client)

Status: transport evidence frozen for step 2 on 2026-10-20; issue-4 action addendum added on 2026-10-22. Each item is marked **verified** (observed against the
live Gateway) or **documented** (read in the installed docs or code, not exercised).

- Gateway: OpenClaw `2026.10.1 (e178c39)`, wire protocol `4`, on the Mac Mini.
- Sources: installed package `/opt/homebrew/lib/node_modules/openclaw` (docs `gateway/protocol/*`,
  `gateway/clients.md`, `web/webchat.md`, `cli/devices.md`, `cli/qr.md`, and the `dist` modules for
  device auth, client ids, bootstrap profiles, and chat schemas).
- Verified by: a Node reference client run from the MacBook over Tailscale (17 of 18 checks passed;
  the one miss was `config.get`, see [Scope limits](#scope-limits)), then the Android smoke test
  `OpenClawPairingSmokeTest` on an arm64 **API 34 emulator** (agent `main`) and an arm64 **API 29
  (Android 10) emulator** (agent `tomek`): 11 of 11 passed on each. Setup, commands, and
  evidence: [openclaw-setup.md](openclaw-setup.md).
- Not verified: a physical phone, the car, SpeedCN coexistence. See [Open items](#open-items).

## Decisions this contract changes in the design

1. **No new Serve listener is needed.** The Gateway already owns a managed Tailscale Serve route
   on tailnet HTTPS port 443 (a *foreground* claim to a private loopback port, shown in
   `tailscale serve status --json` under `Foreground`; the plain `tailscale serve status` text hides
   it). Endpoint: `wss://bobs-mac-mini-openclaw.tail3549c7.ts.net/`. The design said the Mini had no
   Gateway route; that was wrong. A hand-made Serve route to loopback `18789` would not work anyway:
   the docs say the ordinary listener rejects Tailscale-shaped proxy ingress unless
   `gateway.trustedProxies` is widened, which weakens the setup. We made no Serve or Gateway config
   change.
2. **No shared secret reaches the phone, and no setup code is used.** Over the managed Serve route a
   fresh device identity is asked for approval without any token (verified). Approval is the only
   gate. The `openclaw qr` setup code is the wrong tool: its operator token carries
   `operator.approvals`, `operator.talk.secrets`, `operator.read`, `operator.write` (plus
   `operator.admin` unless `--limited`) and a node token, and the server accepts that handoff only
   from the official `openclaw-android` / `openclaw-ios` client ids (documented, from
   `dist/device-bootstrap-profile` and the connect handler).
3. **BYDMate cannot send its own client id.** `client.id` and `client.mode` are a closed list
   (verified: `bydmate` is rejected with `must be equal to one of the allowed values`). The smoke
   test uses `id: "gateway-client"`, `mode: "ui"`, which took the normal pairing path. Impersonating
   `openclaw-android` is not acceptable. If this id is a problem, the only fix is upstream.

## Action contract (issue 4)

This addendum describes the local implementation. The live Gateway evidence above does not verify fenced action output or car execution.

- Action turns append a runtime `bydmate.capabilities/1` manifest, an action-format instruction and, when present, one pending result report. Plain `askText` turns, including the Settings connection probe, carry none of these attachments. This supersedes the earlier no-tool-schema rule only for action turns; no new Gateway method or scope is added.
- The compact manifest lists 110 vehicle ids as `id|c-or-x|range-or-empty|Polish label`, with a risk legend, registry version and navigation choices. Its measured UTF-8 size is 6,428 bytes with alias teaching and 6,267 without it. The local cap is 6,500 bytes. These counts exclude the rest of the encoded `chat.send` frame.
- A proposal is one `bydmate-action` fenced JSON object with schema `bydmate.action/1`, `requestId`, `proposalId`, `capabilities`, `type` and `action`. `type` is `action` or `alias_proposal`; only the latter carries `phrase`. Vehicle actions carry `kind:"vehicle"`, an allowlisted `id` and an integer `value` only when required. Navigation carries `kind:"navigate"` and either `query` (up to 200 characters) or `shortcut` (`home` or `work`).
- The codec rejects multiple or unclosed action fences, more than 2,048 UTF-8 bytes, depth above three, duplicate or unknown keys, comments, single quotes, trailing commas and non-integer or overflowing values. Registry validation follows parsing; parsing alone never makes an id executable.
- The handler checks turn tag, registry version, age, recovery status and the durable ledger before execution. Recovered answers never execute or open an alias dialog. Reusing a processed turn or proposal id never executes it again. Production `resendUncertain` is exposed through the plain Settings probe; handler tests separately pin recovered-action refusal.
- Result lines use the `bydmate.result` prefix and code-only outcomes with `verified:"none"` on every result. Dispatch success does not establish the physical car state. A pending report is offered on the next action turn; no extra model turn is sent to report an outcome.
- Alias teaching is advertised only when `AliasProposalSinkHolder` is bound. The singleton coordinator binds it before the handler is created; Save validates again before the durable write and reports saved or dismissed through the handler. It has no second execution ledger. `AliasProposal.proposalId` is an opaque per-presentation UUID; callbacks echo it unchanged. The handler maps it to the live ticket, while result reports use the original protocol proposal id. A forgotten presentation cannot settle a replacement operation.
- With Polish AI enabled, only `NluRefusal.UNSUPPORTED` may reach OpenClaw. `NAV_INCOMPLETE` stays local after exact-alias lookup; NEGATED, QUOTED and PARTIAL remain earlier safety refusals.

Live fenced-output behavior, negotiated frame-size compatibility and physical-car behavior remain release checks. Do not infer them from the local codec tests or the historical pairing smoke tests.

## Endpoint and trust

- URL `wss://bobs-mac-mini-openclaw.tail3549c7.ts.net/` (port 443, tailnet only). The certificate
  is accepted by the default system trust stores (verified from Node and Android). Do not
  follow redirects (OkHttp `followRedirects(false)`); the production client must also pin the host.
- The device must be on the tailnet and resolve MagicDNS. The Gateway sees the connection as the
  tailnet peer; on the emulator that peer is the Mac, because emulator traffic leaves through the
  host.

## Device identity and signing

All values verified; the Android test checks them with a known-answer vector.

- Key: Ed25519. `deviceId` = lowercase hex SHA-256 of the 32-byte raw public key.
  `device.publicKey` = raw public key, base64url without padding.
- Signature: Ed25519 over the UTF-8 bytes of the payload below, base64url without padding.
- Payload `v3` (preferred; `v2` is still accepted), joined by `|`:

  ```
  v3|<deviceId>|<client.id>|<client.mode>|<role>|<scopes joined by ,>|<signedAt>|<token>|<nonce>|<platform>|<deviceFamily>
  ```

  `signedAt` is the challenge `ts` (milliseconds). `nonce` is the challenge nonce. `token` is the
  device token sent in `auth.deviceToken`, or the empty string on first connect. `platform` and
  `deviceFamily` are lowercased and trimmed.
- Known answer (RFC 8032 test 1 seed `9d61b19d…7f60`): payload
  `v3|21fe31df…21b9|gateway-client|ui|operator|operator.read,operator.write|1737264000000||NONCE-123|android|phone`
  signs to `F7NS7xmfmeBSmE0XowFvnul_U33ilE7Z5CRsS-7yWpeuYvcXC9ur0Ypkfdq9szV04FjhL2WyfalyCbmSfQpOBw`.
  Node `crypto` and BouncyCastle on Android agree.
- Android: BouncyCastle (`bcprov`, already pulled in by `bcpkix-jdk18on`) signs on the device. The
  Android Keystore did **not** report native Ed25519 support on the API 34 emulator, so plan for an
  Ed25519 seed encrypted by an AES-GCM Keystore key (round-trip verified). Android 10 (API 29, arm64
  emulator) is verified the same way: native Keystore Ed25519 `false`, BouncyCastle signing matched the
  known answer, the tamper check failed as expected, and the Keystore AES-GCM wrap round-tripped.

## Handshake and pairing

1. Open the socket. The Gateway sends an event (shape verified, nonce redacted):

   ```json
   {"type":"event","event":"connect.challenge","payload":{"nonce":"<n>","ts":1792507457865,"capabilities":["model-catalog-snapshot"]}}
   ```

2. Send `connect` (protocol `4` only). Request exactly `role: "operator"` and
   `scopes: ["operator.read","operator.write"]`; `caps`, `commands`, `permissions` empty.
3. Unpaired device. The Gateway answers with an error and closes the socket (`1008`). It creates a
   pending request (documented: a retry reuses it, and it expires after about 5 minutes):

   ```json
   {"type":"res","id":"1","ok":false,"error":{"code":"NOT_PAIRED","message":"pairing required: device is not approved yet",
    "details":{"code":"PAIRING_REQUIRED","reason":"not-paired","requestId":"<uuid>","deviceId":"<id>",
     "requestedRole":"operator","requestedScopes":["operator.read","operator.write"]}}}
   ```

   A socket that sends `chat.send` without a successful `connect` is refused (verified).
4. The owner approves on the Mini: `openclaw devices approve <requestId>`. The approved grant
   equalled the request (observed).
5. Reconnect. `hello-ok` returns the device token and the live authority (verified):

   ```json
   {"type":"res","id":"1","ok":true,"payload":{"type":"hello-ok","protocol":4,
    "server":{"version":"2026.10.1"},"features":{"methods":["chat.send","chat.abort","chat.history","…"]},
    "auth":{"role":"operator","scopes":["operator.read","operator.write"],"deviceToken":"<secret>"}}}
   ```

   Persist `auth.deviceToken`. Reconnect with `auth: {"deviceToken": "<token>"}` and the same
   identity; the token was not rotated across reconnects in our run. If a different token comes
   back, replace the stored one.
6. After removal on the Gateway, the stored token is refused with `PAIRING_REQUIRED` again and a new
   pending request appears (verified). Reject that request on the Mini. A read/write device cannot
   revoke itself (`device.token.revoke` needs `operator.pairing`); "Disconnect" in the app clears local
   state, and revocation is done on the Mini.

Error codes to map to Polish UI states: `NOT_PAIRED` / `PAIRING_REQUIRED` (approval required);
`AUTH_*` (documented: token mismatch, scope mismatch; re-pair); `FORBIDDEN` / `MISSING_SCOPE`;
`UNAVAILABLE` with `details.reason: "startup-sidecars"` and `retryAfterMs` (documented, retry).

## Chat

Session key: `agent:<agentId>:<rest>` (documented). The owner chose agent `tomek` (Bob); the smoke test
uses `agent:tomek:bydmate-car-test` (confirmed: the Gateway lists that key under agent `tomek`). The
first API 34 run used `agent:main:bydmate-car-test`. A new key needs no creation call; the first `chat.send` creates it
(verified).

`chat.send` (minimum that worked):

```json
{"type":"req","id":"2","method":"chat.send","params":{"sessionKey":"agent:main:bydmate-car-test",
 "message":"<text>","idempotencyKey":"<uuid>"}}
```

- Ack (verified): `{"ok":true,"payload":{"runId":"<uuid>","status":"started"}}`. The ack means
  admission, not transcript persistence (documented).
- Events arrive as `event: "chat"` frames. Match `payload.runId`; ignore other runs. States seen:
  `status` (startup phase, e.g. `preparing_workspace`), `delta`, `final`. Schema also defines
  `aborted` (seen) and `error` (not exercised; carries `errorKind`, `errorMessage`).
  Per-run `payload.seq` was non-decreasing in every run.

  ```json
  {"type":"event","event":"chat","payload":{"runId":"<id>","sessionKey":"agent:main:bydmate-car-test","agentId":"main","seq":17,
   "state":"delta","deltaText":"D","message":{"role":"assistant","content":[{"type":"text","text":"D"}]}}}
  {"type":"event","event":"chat","payload":{"runId":"<id>","sessionKey":"…","agentId":"main","seq":36,
   "state":"final","stopReason":"stop","message":{"role":"assistant","content":[{"type":"text","text":"Dziś jest wtorek."}]}}}
  ```

  `deltaText` is incremental (the first delta was one character). The `final` message holds the whole
  answer; use it for speech. Documented: without the `chat-only-assistant-text` capability the
  Gateway also streams an `agent` event family; the smoke test ignores it.
- **Idempotency (verified):** resending the same `idempotencyKey` and message returns the same
  `runId` with `status` `in_flight` or `ok` and adds no second user turn or run.
- **Abort (verified):** `chat.abort {sessionKey, runId}` returns `{"ok":true,"aborted":true,"runIds":[…]}`
  and a terminal `aborted` event follows. Partial text is kept in history (documented). Abort does not
  undo tool actions already completed.
- **Recovery (verified):** after a lost socket, reconnect with the device token, poll
  `chat.history {sessionKey, limit}` until `sessionInfo.hasActiveRun` is false, then resend the same
  key. The history response includes `inFlightRun {runId, text}` while a run is active and a
  `deltaCursor`. Exactly one user turn and one assistant answer existed afterwards. Never resend
  with a new key after an uncertain loss (the original run may have used a tool).
- History rows: `role`, `content` (string for `user`, array of blocks for `assistant`), plus an
  `__openclaw` metadata envelope. The Gateway labelled the user rows with the owner's profile name
  (probably from the Tailscale identity), so messages from BYDMate appear as the owner.

## Scope limits

Verified against the live Gateway with the device token (`operator.read`, `operator.write` only):

| Call | Result |
| --- | --- |
| `device.pair.list`, `device.pair.approve`, `device.token.revoke`, `node.pair.list` | `FORBIDDEN` / `MISSING_SCOPE` (`operator.pairing`) |
| `device.pair.setupCode` | `FORBIDDEN` / `MISSING_SCOPE` (`operator.admin`) |
| `exec.approval.list` | `FORBIDDEN` / `MISSING_SCOPE` (`operator.approvals`) |
| `config.get` | **Allowed.** Returned the config with every secret-like value replaced by `__OPENCLAW_REDACTED__` (checked in `raw`, `parsed`, and the runtime copy) |
| `sessions.list` | **Allowed.** 50 sessions across agents `main`, `tomek`, `stock-research`, `jola` |
| `chat.history` of `agent:main:main` | **Allowed** |

So read/write is **not** limited to the car conversation: the device can read the owner's other
conversations and the redacted config, and (by the method's definition, not tested) send to other
sessions. This matches the design's statement that scopes do not restrict a conversation, but the
extent is wider than "chat". Treat a stolen head unit as exposing the assistant's conversation
history until the device is removed on the Mini. The approval decision belongs to the owner.

## Open items

1. **Agent: decided.** The owner chose `tomek` (Bob) on 2026-10-20 and accepted that read/write scopes
   reach other sessions and the redacted config. Step 6 should use `agent:tomek:<car conversation>`.
2. Android 10 (API 29, arm64 emulator) is verified. A physical phone is still untested.
3. Car reachability to the Mini, SpeedCN coexistence, and cellular/hotspot behaviour are unverified.
4. The `error` chat state, token rotation, and the 5-minute approval expiry were not exercised.
5. `gateway-client`/`ui` is a borrowed identity; a Gateway upgrade could change how it is treated.
6. The managed Serve route's private loopback port changes when the Gateway restarts; the public
   tailnet URL does not.

## Negotiated outbound request limit

The client retains `hello-ok.policy.maxPayload` per connection when it is a positive integer. Missing policy or a missing key keeps legacy unbounded sending. A present invalid policy or value fails as `ProtocolMismatch` before token storage. The client checks the UTF-8 bytes of each complete encoded request, including its id and JSON escaping, before sending; equality is allowed.

An oversized chat request returns the local `RequestTooLarge` failure. It is known not to have been sent, so it causes no admission callback, history recovery or new uncertainty. The Ready socket remains usable for a smaller request. An oversized explicit resend returns `ResendTooLarge` and keeps the prior uncertain turn and key. The assistant keeps `uncertain=true`, so the Settings probe retains Retry. Its Polish text says that the retry was not sent and the original question remains unresolved. A first-send rejection remains non-uncertain. The manifest cap alone does not establish that a complete frame fits. These rules have local fixture tests; the installed Gateway policy remains unverified.

## Live settlement and reset

Ledger tickets bind each live operation to its ledger instance and entry token. Terminal state and report settle atomically; stale completions cannot alter a replacement entry or report. Cancellation and dispatch admission share one operation lock. Reset suppresses reports in memory immediately and prevents an older operation from creating a new report. If removal fails and the process restarts before any later successful write, the old disk report may reload. The report is best effort; actions are never replayed to repair it.

Report consumption uses an in-memory receipt bound to the ledger instance and report generation. `offerReport()` reads the line and receipt atomically and assigns an increasing offer sequence. The handler installs offers in sequence order. An acknowledged turn consumes only its exact receipt after a compare-and-set removes that same offer; it never consumes by protocol proposal id alone. A stale acknowledgement cannot clear a replacement report or a newer offer. A failed removal write leaves the report pending.

If two turns carry the report before either is acknowledged, the older acknowledgement leaves the newer offer in place. The newer acknowledgement consumes the report. The line can therefore be delivered more than once; this does not replay an action. Receipts and offer sequences are not persisted, and the `actions.json` format is unchanged.
