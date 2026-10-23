# OpenClaw setup and phone-first smoke test

Purpose: connect BYDMate to the owner's existing OpenClaw on the Mac Mini with a paired, revocable
device, and prove it from an Android device before any car install. The wire details are in
[openclaw-protocol-contract.md](openclaw-protocol-contract.md).

Historical verification on 2026-10-20: the transport works. The Android smoke test passes 11 of 11 on an **arm64 API 34
emulator** (Android 14) and on an **arm64 API 29 emulator** (Android 10), both against agent `tomek`
(API 34 ran against `main`, before the owner chose `tomek`; see the API 34 note under Evidence).
A **physical phone** is not tested; that is the only part of the step 2 gate still open.

## Server facts (read-only checks, 2026-10-20)

- Host `bobbobster@100.89.220.111` (`bobs-mac-mini-openclaw`), OpenClaw `2026.10.1 (e178c39)`,
  CLI `/opt/homebrew/bin/openclaw`, package `/opt/homebrew/lib/node_modules/openclaw`.
- Gateway: `127.0.0.1:18789`, token auth, `gateway.tailscale.mode=serve`, `gateway.auth.allowTailscale=true`.
- Default agent per `openclaw agents list`: `main`. The agent with the `🦞 Bob` identity is `tomek`.
  **Decided by the owner on 2026-10-20:** the car talks to `tomek` (Bob), not `main`, and the owner
  accepts that `operator.read` + `operator.write` can read other sessions and the redacted config.
  The smoke test session is `agent:tomek:bydmate-car-test`.
- No devices were paired before or after our runs: `openclaw devices list` printed
  `No device pairing entries.` at the end of every run.

### Tailscale Serve, before and after

We changed nothing. Both states are identical:

```
tailscale serve status (background routes)
  https://bobs-mac-mini-openclaw.tail3549c7.ts.net:4443  /  -> http://127.0.0.1:4175
  https://bobs-mac-mini-openclaw.tail3549c7.ts.net:8443  /  -> http://127.0.0.1:3773

tailscale serve status --json  ->  Foreground (Gateway-managed claim, not visible in the text view)
  https://bobs-mac-mini-openclaw.tail3549c7.ts.net:443   /  -> http://127.0.0.1:<private port>
```

The plan asked for a new Serve listener to `18789`. It is not needed: the Gateway already publishes
the managed route on `:443`. No public listener, no shared Gateway token on Android, no auth change.

## Run the Android smoke test

Prerequisites: a connected arm64 Android device or emulator on the tailnet, JDK 17, Android SDK 34.
The test is opt-in, so a normal `connectedDebugAndroidTest` run skips it and never touches the network.

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME=/Users/tomaszkopycki/Library/Android/sdk
cd /Users/tomaszkopycki/Developer/BYDMate
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.bydmate.app.voice.openclaw.OpenClawPairingSmokeTest \
  -Pandroid.testInstrumentationRunnerArguments.openclawSmoke=true
```

Optional arguments (same `-Pandroid.testInstrumentationRunnerArguments.` prefix): `openclawUrl`,
`openclawSession` (default `agent:tomek:bydmate-car-test`), `openclawApprovalTimeoutSec` (240),
`openclawRevokeTimeoutSec` (240).

The test makes two requests of the owner while it runs. It logs a marker (tag `OpenClawSmoke`) and
waits:

| Marker | What to do on the Mini |
| --- | --- |
| `OPENCLAW_WAITING_FOR_APPROVAL deviceId=<id>` | List pending requests, check the entry for that `deviceId` asks for role `operator` with `operator.read` + `operator.write` only, then approve that request id |
| `OPENCLAW_REVOKE_NOW deviceId=<id>` | `devices remove <id>` |
| `OPENCLAW_REJECT_PENDING requestId=<id>` | `devices reject <id>` (the post-revoke connect created a new request) |

No secret is involved: the request id and device id are not credentials. The device token stays in
the test process memory and is never logged.

With more than one device attached, set `ANDROID_SERIAL=<serial>` so Gradle targets one device.

On the Mini, use the Gateway service's Node. The login shell's default Node (24.15.0) makes the
`devices` commands fail on `node:sqlite`:

```sh
ssh bobbobster@100.89.220.111
OC='"$HOME/.local/share/node/node-v24.21.0-darwin-arm64/bin/node" /opt/homebrew/lib/node_modules/openclaw/openclaw.mjs'
eval "$OC devices list"
eval "$OC devices approve <requestId>"
eval "$OC devices remove <deviceId>"
eval "$OC devices reject <requestId>"
```

During this work a helper `~/oc-probe/oc.sh` (a one-line wrapper around the same command) was left on the
Mini. Delete `~/oc-probe` if you do not want it. Over SSH, call the wrapper (`~/oc-probe/oc.sh devices list`);
a `$OC` variable holding "node path" fails in the Mini's zsh because it does not split words.

### Hands-off run

A host script can approve for you. It must approve only the `deviceId` the test logged and only
a request for exactly role `operator` and `operator.read` + `operator.write`. The script used on
2026-10-20 (API 29 run) followed `adb -s <serial> logcat -s OpenClawSmoke`, read `devices list --json`
to check the pending entry for that `deviceId`, then ran approve, remove and reject over SSH. It
refused to remove any device it had not approved. It was kept outside the repo.

### Android 10 (API 29) arm64 emulator setup

The SDK had no command-line tools and no API 29 image. Both were installed on 2026-10-20:

```sh
# cmdline-tools from dl.google.com (commandlinetools-mac-9862592_latest.zip), unzipped into
# $ANDROID_HOME/cmdline-tools/latest. It warns "SDK XML versions up to 3 ... version 4"; the install still worked.
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "system-images;android-29;google_apis;arm64-v8a" "platforms;android-29"
echo no | $ANDROID_HOME/cmdline-tools/latest/bin/avdmanager create avd -n Pixel_2_API_29 \
  -k "system-images;android-29;google_apis;arm64-v8a" -d pixel_2
# second emulator next to the existing API 34 one, own port so it does not interfere
$ANDROID_HOME/emulator/emulator -avd Pixel_2_API_29 -port 5556 -no-window -no-audio -no-snapshot \
  -dns-server 100.100.100.100,8.8.8.8
```

`device_open` from the T3 device tool failed to boot this AVD ("could not start"); booting it directly with
the emulator command worked. The emulator reported `ro.build.version.release=10`, `sdk=29`,
`abi=arm64-v8a`; after the Wi-Fi off/on reset, `ping bobs-mac-mini-openclaw.tail3549c7.ts.net` answered
(100.89.220.111, 36.6 ms).

### Emulator networking (arm64, no window)

The emulator started with no route to the tailnet. These steps fixed it:

```sh
$ANDROID_HOME/emulator/emulator -avd Pixel_2_API_34 -no-window -no-audio -no-snapshot \
  -dns-server 100.100.100.100,8.8.8.8
adb shell 'svc wifi disable; sleep 2; svc wifi enable'      # restores the default route
adb shell ping -c1 bobs-mac-mini-openclaw.tail3549c7.ts.net   # must resolve and answer
```

Emulator traffic leaves through the Mac, so the Gateway saw the Mac's tailnet address as the peer. This
does not prove a separate phone's tailnet path. On a physical phone, install and sign in to Tailscale,
open `https://bobs-mac-mini-openclaw.tail3549c7.ts.net/` in the phone browser first (record the time),
then run the test.

## Tailscale connection on startup

BYDMate can send one explicit `com.tailscale.ipn.CONNECT_VPN` broadcast to Tailscale's
exported `com.tailscale.ipn.IPNReceiver`. It requests connection without opening
Tailscale's screen.

The request is gated by OpenClaw admission. `OpenClawConnectionCoordinator` asks the
assistant for an automatic reconnect first. Only when the assistant admits the attempt
does BYDMate send the hint, and then it rechecks the generation before connecting.
An admission skipped because the assistant is disabled, the endpoint is invalid, or the
device is unpaired, awaiting approval or revoked sends nothing. An admitted attempt can
send the hint and later return `IdentityChanged` if its generation changes before
connecting. That later refusal prevents the connection; it cannot undo the sent hint.

Only three service events may send the hint: the service session start, a wake, and a
reachable-network edge. A network edge is published by `NetworkAvailableMonitor` after a
TCP probe bound to the validated network succeeds. If that probe fails, for example
because the car's Wi-Fi is up before its internet, a later callback for the same network
probes again. The edge then cancels any pending backoff wait and starts one attempt, or
joins the attempt already running. Backoff
timer retries (5, 10, 20, 40, then every 60 seconds), status changes and stale-skip
re-evaluations never send the hint.

The broadcast is asynchronous. A sent request is not a connected VPN: BYDMate cannot see
whether Tailscale accepted it. A missing or restricted receiver does not stop BYDMate.
The trace records each automatic decision as a `NET` event `openclaw_auto` with fixed codes
only: `origin` (start, wake, network, timer, status, stale), `admission` (admitted,
skipped), the skip `reason`, and for admitted attempts the `hint` outcome (not_requested,
requested, request_error) with `receiver` and `sent`. It never records endpoints,
identities or error messages.

Install Tailscale, sign in, and approve Android's VPN permission once before relying
on this. The installed Tailscale version must include this receiver. Still unverified on
the car: a cold boot where the internet arrives minutes after ignition, a sleep/wake, and
recovery without manually opening Tailscale or BYDMate. The local tests prove only the
coordinator and monitor contracts, not the car's startup cause.

Source: [official receiver](https://github.com/tailscale/tailscale-android/blob/main/android/src/main/java/com/tailscale/ipn/IPNReceiver.java)
and [VPN worker](https://github.com/tailscale/tailscale-android/blob/main/android/src/main/java/com/tailscale/ipn/StartVPNWorker.java).

## Evidence (2026-10-20)

### Android 10 / API 29, agent `tomek` (the gate run)

Device `Pixel_2_API_29(AVD) - 10`: arm64-v8a, Android 10, API 29, emulator `emulator-5556`
(`ANDROID_SERIAL=emulator-5556`; the API 34 emulator stayed up on `emulator-5554` and was not used).
Test log: `device sdk=29 release=10 abi=arm64-v8a`, `keystore Ed25519 native support: false`.

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 17)" ANDROID_HOME=/Users/tomaszkopycki/Library/Android/sdk ANDROID_SERIAL=emulator-5556
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.bydmate.app.voice.openclaw.OpenClawPairingSmokeTest \
  -Pandroid.testInstrumentationRunnerArguments.openclawSmoke=true
```

```
Starting 11 tests on Pixel_2_API_29(AVD) - 10
Finished 11 tests on Pixel_2_API_29(AVD) - 10
BUILD SUCCESSFUL in 48s
```

JUnit XML: 11 tests, 0 failures, 0 errors, 0 skipped. Per test (seconds): t01 0.04, t02 0.35, t03 3.54,
t04 0.48, t05 0.17, t06 0.45, t07 8.10, t08 3.20, t09 2.38, t10 10.20, t11 3.35. Each test proves
the same thing as in the API 34 table below. Specifics from the log (17:05, local time):

- t01: the RFC 8032 vector, the v3 known answer, tamper rejection and the Keystore AES wrap round-trip all
  passed on Android 10 (BouncyCastle software Ed25519; the Keystore has no native Ed25519 here either).
- t03: approved after about 2 s by the host approver; role `operator`, scopes exactly read + write, protocol 4.
- t05/t06: `config.get` secret-like fields 4, all redacted; `sessions.list` showed 50 sessions across `tomek`,
  `jola`, `main`, `stock-research` (accepted by the owner).
- t07: events `status`, `delta`, `final`; answer 13 characters in `agent:tomek:bydmate-car-test`.
- t11: the removed identity was refused; the new pending request was rejected on the Mini.

Mini side, same run: `devices list` printed `No device pairing entries.` before and after. The approver log
shows one approve, one remove and one reject, all `rc=0`, all for the logged device. `tailscale serve status`
showed the same 4443 and 8443 routes before and after, and one `Foreground` entry (the managed 443 route)
in `--json` both times. No Serve or Gateway config change was made.

Not covered by this run: a physical phone, a separate phone's own tailnet path (the emulator traffic leaves
through the Mac), the car, and SpeedCN. The first API 34 run below used the `main` agent.

### Android 14 / API 34 (first run, agent `main`)

Red, before approval existed (approval window set to 20 s, no approver):

```
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=\
  com.bydmate.app.voice.openclaw.OpenClawPairingSmokeTest \
  -Pandroid.testInstrumentationRunnerArguments.openclawSmoke=true \
  -Pandroid.testInstrumentationRunnerArguments.openclawApprovalTimeoutSec=20
Pixel_2_API_34(AVD) - 14 Tests 11/11 completed. (0 skipped) (9 failed)
  t01 ok, t02 ok
  t03 FAILED  AssertionError: pairing was not approved in time; last response:
              NOT_PAIRED/PAIRING_REQUIRED: pairing required: device is not approved yet
  t04..t11 FAILED (they need the paired device from t03)
BUILD FAILED in 2m 44s
```

t01 and t02 are the "fresh identity needs approval and cannot chat" assertions; they pass without
approval, which is the intended behaviour. The rest fail until a device is approved.

Green, with the approver running (same command, no timeout override):

```
Starting 11 tests on Pixel_2_API_34(AVD) - 14
Finished 11 tests on Pixel_2_API_34(AVD) - 14
BUILD SUCCESSFUL in 56s
```

| Test | Proves | Time |
| --- | --- | --- |
| t01 `ed25519SigningWorksOnThisDevice` | RFC 8032 vector, v3 payload known answer, tamper rejected, Keystore AES wrap round-trip | 0.18 s |
| t02 `freshIdentityRequiresApprovalAndCannotChat` | unpaired identity gets `PAIRING_REQUIRED` for read/write only; `chat.send` before `connect` fails | 0.58 s |
| t03 `approvedDeviceGetsOnlyReadWriteScopes` | after approval: role `operator`, scopes exactly read + write, device token issued, protocol 4 | 3.33 s |
| t04 `adminPairingAndApprovalMethodsAreDenied` | six admin/pairing/approval methods return `MISSING_SCOPE` | 0.30 s |
| t05 `configReadExposesNoSecrets` | `config.get` is readable and every secret-like value is redacted | 0.26 s |
| t06 `documentedScopeLimitsAreRecorded` | records that `sessions.list` spans 4 agents (informational) | 0.33 s |
| t07 `chatStreamsPolishAnswer` | Polish turn: `status`, `delta`, `final`; ordered; non-empty answer | 3.58 s |
| t08 `sameIdempotencyKeyDoesNotDuplicateTurn` | same key gives same run id, one user turn | 3.24 s |
| t09 `abortStopsARunningTurn` | `chat.abort` gives `aborted` after streaming started | 3.33 s |
| t10 `reconnectRecoversWithoutDuplicateSend` | drop socket after ack, reconnect with token, history shows answer, same-key resend adds no turn | 13.59 s |
| t11 `revokedDeviceTokenIsRejected` | after `devices remove`, the token and a fresh connect both need pairing | 3.68 s |

Device: `Pixel_2_API_34` (arm64-v8a, Android 14, API 34). Logged: the Keystore reported **no** native
Ed25519 support, so the software key path is the one proven.

A Node reference client (outside the repo) ran the same flow from the MacBook first: 17 of 18 checks passed
and the miss was `config.get` being allowed (now an explicit, redacted-secrets assertion in t05).

## After a run

- `devices list` must show no pending or paired entries. Remove or reject anything left.
- The test chat leaves the session `agent:tomek:bydmate-car-test` (API 29 run) with a few short Polish test
  turns in the `tomek` agent's transcript. The earlier API 34 run left `agent:main:bydmate-car-test` in
  `main`. Delete them from the Control UI if you do not want them.
- The agent runs the owner's configured model and tools; prompts were harmless and told it not to use
  tools, but that is a request, not a control.

## Still open for this step

1. Run the same test on a physical Android phone (USB debugging) and record the browser reachability
   check with timestamps. Android 10 is done (API 29 arm64 emulator).
2. Owner decisions are made: agent `tomek`, read/write reach accepted (2026-10-20).
3. Car reachability and SpeedCN coexistence remain unverified.


## Step 7: the settings connection test (phone-first)

The production app has a **Test połączenia z asystentem** card in Settings → Voice agent (all labels Polish).
It needs no microphone, speech model or car. It goes through the same transport,
device identity and conversation as the Polish voice session, and it has no vehicle collaborator.

How to use it on a phone or arm64 emulator on the tailnet:

1. Settings → Voice agent → scroll to **Asystent online (OpenClaw), tylko tekst**. Turn on **Włącz asystenta
   OpenClaw**. This opt-in is separate from voice recognition and spoken replies. All are off on a fresh install.
2. The Gateway address defaults to `wss://bobs-mac-mini-openclaw.tail3549c7.ts.net/`, including after an
   update from an empty saved address. A saved custom address is preserved.
   The field locks while a device is paired; use **Odłącz** to change it.
3. Tap **Sparuj**. The status shows the pairing; the owner approves the device on the Mini (check the pending
   entry asks for role `operator` with `operator.read` + `operator.write` only):

   ```sh
   OC='"$HOME/.local/share/node/node-v24.21.0-darwin-arm64/bin/node" /opt/homebrew/lib/node_modules/openclaw/openclaw.mjs'
   ssh bobbobster@100.89.220.111 "$OC devices list --json"
   ssh bobbobster@100.89.220.111 "$OC devices approve <requestId>"
   ```
4. Type Polish text in **Wiadomość testowa** and tap **Wyślij**. The answer appears under it. **Anuluj** aborts the
   running turn. **Nowa rozmowa** starts a new conversation (pairing stays). **Połącz ponownie** reconnects with
   the stored token after a restart; no new approval is needed.
5. Audio is optional. Without the Gosia voice installed the card says `Tylko tekst: pobierz głos Gosia...`.
   After **Pobierz** for **Głos Gosia (lokalny)** (about 20 MB, no ASR model needed), enable **Czytaj odpowiedzi agenta na głos**
   to hear answers through the local Polish voice. Spoken replies are off by default.
6. **Odłącz** clears the key, token and conversation on the phone. Revoke on the Mini as well:
   `$OC devices remove <deviceId>`. A rejected post-revoke connect leaves a pending request: `$OC devices reject <id>`.

Polish is the only supported voice language. **Sprawdź gotowość języka polskiego → Sprawdź** verifies
the installed Parakeet, VAD and Gosia files and shows any problems. This check does not enable the microphone.
The earlier **Włącz polski** language switch has been removed.

### Historical verification, 2026-10-21 (Android 14 arm64 emulator `emulator-5560`, BM_Polish_1b)

The labels and language-switch behavior below describe the earlier build, before the Polish-only change.
They are retained as the record of what was tested.

Instrumentation, the production objects over the real Keystore, controlled transport for failures:

```sh
ANDROID_SERIAL=emulator-5560 ./gradlew :app:connectedDebugAndroidTest --offline \
  -Pandroid.testInstrumentationRunnerArguments.class=com.bydmate.app.voice.openclaw.OpenClawPhoneFlowTest
```

Result: 6 tests, 0 failures, 0 skipped. A mutation that removed the opt-in check made
`optInIsOffByDefaultAndSeparateFromEveryRussianSetting` fail, then was reverted.

Live run against the Mini with the real settings screen (debug APK, no models installed at first):

| Step | Visible state | Server side |
| --- | --- | --- |
| Tap **Włącz polski** with nothing installed | red Polish lines: recognizer, VAD, Gosia missing; Russian stays active | none |
| Enable, address, **Sparuj** | `Łączę…` (this first build did not show the approval wait; the status now follows the live link, covered by a unit test but not re-run against the Mini) | pending request: role `operator`, scopes `operator.read`, `operator.write`, remote IP of the emulator |
| After `devices approve` | `Połączono` | `Paired (1)`, scopes read + write |
| Text test, no models installed | answer `Cześć, jestem Bob, Twój osobisty asystent AI.`; `Tylko tekst: pobierz głos Gosia, aby usłyszeć odpowiedź.` | turn in `agent:tomek:bydmate-car-<id>` |
| Restart app | `Sparowano, rozłączono`; **Połącz ponownie** gives `Połączono` | no new request |
| **Pobierz** Gosia (ASR not installed) | `Gotowy` after unpack and hash check (about 1.5 min on the emulator; the bar sits at 100% meanwhile) | none |
| Text test with Gosia | answer shown, `Odpowiedź odtwarzana lokalnym głosem Gosia.`; logcat: `engine created: voice=gosia engineRate=22050`, `synth done: samples=76427` | turn answered |
| Long request, **Anuluj** | `Anulowano.` | log: `chat.abort requested: actor=gateway-client ... runId=...`, `lane task error ... This operation was aborted` after 4968 ms |
| `devices remove` on the Mini, then **Wyślij** | `Asystent odrzucił to urządzenie. Sparuj je ponownie.`; status `Odrzucono przez asystenta. Sparuj ponownie` | pending request from the post-revoke connect |
| **Odłącz**, then `devices reject` | `Nie sparowano` | `No device pairing entries.`; Serve routes 4443 and 8443 unchanged |

Not covered: the speech was not heard by a person (the emulator has no audible output here; the evidence is
the synthesis and playback log), diacritics were typed as plain ASCII through `adb input text`, and the
emulator's traffic leaves through the Mac, so this is not a separate phone's tailnet path. The car, SpeedCN
coexistence and native Polish recognition are untouched by this run. The test identity was revoked and the
Mini lists no devices.

### Step 8 recheck, 2026-10-21 (final debug build, `emulator-5560`)

The status that Step 7 had not re-run against the Mini now works on the final build. Full table and caveats:
[polish-acceptance.md](polish-acceptance.md#live-openclaw-against-the-mini-final-build).

- `Sparuj` showed `Czeka na zatwierdzenie na komputerze z asystentem` with the request id and `Parowanie w toku` /
  `Anuluj`. Pending request: role `operator`, scopes `operator.read` + `operator.write`, `gateway-client`/`ui`.
- After `devices approve`: `Połączono`. Text test answered `Cześć Tomek, w czym mogę pomóc?`; Gosia (installed through
  the app) synthesised it (`samples=48128`, nobody heard it). `Anuluj` gave `Anulowano.` and the Gateway logged
  `chat.abort requested … This operation was aborted`.
- `Odłącz`, then `devices remove <id>`: `No device pairing entries.` and `tailscale serve status --json` byte-identical
  to the capture taken before the run. Reconnect after restart and post-revoke rejection were not repeated.
- Still open: a physical phone, the car's tailnet path and SpeedCN coexistence.
