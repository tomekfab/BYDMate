# Polish voice and OpenClaw: acceptance record (plan Step 8)

Run 2026-10-21 on the uncommitted tree on top of `b27ca45e`. This is a verification record, not a release
note. **Not release-ready. Not car-ready. Every car check below is pending.**

Evidence levels used in this file:

- **JVM**: host unit tests and static checks.
- **Emulator**: Android 14 arm64 `emulator-5560` (BM_Polish_1b, 4 GB, M-series host). Not a phone, not the head unit.
- **Android 10**: signing and key storage on an API 29 arm64 emulator. Not re-run in Step 8 (see [Android 10](#android-10-signing)).
- **Car**: parked Leopard 5. Nothing was run there.

## Step 4 remediation verification — 2026-10-21

The fresh report's **46 findings across 28 paths are cleared**. Final standalone detekt: `BUILD SUCCESSFUL in 23s`; `detekt.txt` is empty. Baseline, rule thresholds and suppressions were not expanded.

| Check | Actual result |
| --- | --- |
| Combined focused before | 3339 tests, 42 classes, 0 failures/errors/skips; `BUILD SUCCESSFUL in 8m 40s` |
| Combined focused after | Same 3339 tests and names, 0 failures/errors/skips; `BUILD SUCCESSFUL in 9m 15s` |
| Exact full 4GB command below | 9882 tests, 1 failure, 0 errors, 3 skipped; exit 1, `BUILD FAILED in 12m 52s` |
| Debug assembly / lint / detekt in full command | Passed / passed / passed (detekt UP-TO-DATE after standalone gate) |

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home ANDROID_HOME=/Users/tomaszkopycki/Library/Android/sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:detekt -I /Users/tomaszkopycki/Developer/BYDMate-step8-evidence/scripts/test-heap4g.init.gradle --continue
```

The sole failure is the known `CalendarPeriodsTest` Lisbon DST assertion at line 114 (`expected:<-1572739200000> but was:<-1572742800000>`), which the parent had also verified on pristine HEAD. It was reproduced and left unchanged. The existing 2GB Gradle daemon emitted a low-heap/expiry warning; the test worker used 4GB and completed without an OOM failure.

Commands, logs, 46-finding map, before/after/full XML, reports, source snapshots/diff and final report: `/Users/tomaszkopycki/Developer/BYDMate-step8-evidence/step4-remediation/`. Exact focused selectors are retained in `focused.sh`.

Rebuilt APK copy: `/Users/tomaszkopycki/Developer/BYDMate-step8-evidence/step4-remediation/BYDMate-v4.0.1-step4-debug.apk`; 114857665 bytes; SHA-256 `00c2963e27d262c1becad6bfdee6ea2425d002d1acd50232a93110425219842c`. No device, emulator, SSH or car action ran in Step 4. Rebuilt-APK B1 memory/thread checks on an emulator and all parked-car checks remain pending. **Car readiness is not established.**

The original Step 8 findings below remain as historical evidence; this follow-up records only the Step 4 implementation and host verification. Gradle ownership is released.

## Android 10 compact dictionary checkpoint — 2026-10-21

The compact dictionary passes a real-app heap check on `BM_Polish_29_root` (`emulator-5562`, API 29). Direct instrumentation of the external `RemoteDiagnosticsProbeTest#polishDictionaryFitsRealAppHeap` classifies `zamknij wszystkie okna` as a match. After three GC requests and 100 ms pauses, it reports 6,210 KiB used against a 49,152 KiB runtime maximum; the assertion requires more than half the heap to remain free. Total time including GC pauses was 361 ms. This checks the dictionary alone, not native model memory, main-thread responsiveness during profile activation, or a car session.

The same two-test instrumentation run failed its separate remote-upload check: emulator Wi-Fi was disabled; after enabling it, the receiver rejected the Android route with 404. The receiver has since been corrected to the approved `/v1/diagnostic-batches` contract and its 16 Node tests pass. Fresh Android worker delivery remains pending. Do not treat the combined instrumentation command as green.

## Original Step 8 verdict

| Gate | Result |
| --- | --- |
| `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:detekt` | **FAILED**: 1 test (pre-existing), detekt 32 weighted issues (new). Needs a 4 GB test heap, see below. |
| Final Codex diff review | **5 findings** (4 × P1, 1 × P2), all with matching code evidence, none fixed yet |
| Polish dictionary inside the app heap | **Blocker.** Loads on the main thread: ANR on the emulator; heap 90–100 % of the 192 MiB cap |
| Parakeet accuracy gate (original 16 fixtures) | **15/16, unchanged failure** (`neg-03`); human clips 1/2 |
| Wrong vehicle action in the new probes | **0** (33 clips × 2 runs) |
| OpenClaw live on the emulator (final build) | Pass: approval wait, text reply, cancel, revoke |
| Distribution notices | **Blocked**: attribution is not user-visible; no licence files in the APK; repack and Piper/espeak terms unresolved |
| Parked-car checks | **All pending** |

A passing emulator result is not a car result. Only passes justify release claims; there are none for the car.

## New blockers found in Step 8

These need owner steps and an approved plan correction. No behavioural fix was made here.

### B1. The Polish dictionary does not fit the app heap and loads on the main thread

- Measured on the production APK on the emulator, in the real settings screen: **Włącz polski** →
  `NluParser: dictionary loaded: lang=pl templates=155 keys=370514 in 39932 ms`. The log line has the same
  pid and tid (5326/5326), so it ran on the main thread. Android reported `ANR in com.bydmate.app ... Input
  dispatching timed out ... Waited 5002ms for MotionEvent` at 07:20:04. After the load: Dalvik heap
  `Alloc 177,852 KB` of `Size 196,608 KB`; ART logged `Clamp target GC heap from 199MB to 192MB`. Before the
  load it was 10,521 KB. The app survived by a few MiB.
- Cause in code: `SettingsViewModel.kt:1648` runs `voiceLanguageSetup.activatePolish()` in `viewModelScope.launch`
  (main dispatcher). `VoiceLanguageSetup.kt:218` then calls `NluParser.warmUp(target)` synchronously.
- Repeated in isolation (instrumentation, app process, nothing else loaded): first Polish classify took 16,040 ms
  and left 174,762 KiB used of the 196,608 KiB cap; loading the Russian dictionary after it left 195,768 KiB used
  (99.6 %). Logcat tag `Step8Probe`, `HEAP`.
- The emulator reports `dalvik.vm.heapgrowthlimit=192m`, `heapsize=576m`; the manifest has no `largeHeap`. The
  Leopard 5 limit was not read. The load time was measured on a fast host; a head unit will be slower.
- Why the JVM suite is also affected: see [Full suite](#full-suite-memory-diagnosis).
- Not claimed: how the real car handles this. `TrackingService` warms the dictionary off the main thread
  (`TrackingService.kt:794-797`), but the heap use is the same.

### B2. `:app:detekt` fails on this change

`Analysis failed with 32 weighted issues`. The pristine `HEAD` passes detekt. Every finding is in a file that
this change edits or creates (`VoiceModule`, `VoiceLanguageSetup`, `PolishModelManager`, `OpenClawClient`,
`OpenClawAssistant`, `OpenClawProtocol`, `PolishAssistantText`, `PolishVoiceReplies`, `VoiceController`,
`SherpaTtsEngine`, `GigaAmModelManager`, `GigaAmAsrEngine`, `AsrLoadGuard`, `TtsModelManager`,
`VoiceProfileStore`, `PolishVoiceNormalizer`, and five test files). Kinds: TooManyFunctions, LongParameterList,
LongMethod, CyclomaticComplexMethod, NestedBlockDepth, TooGenericExceptionCaught,
LoopWithTooManyJumpStatements, EmptyFunctionBlock, MayBeConst. Full list:
`BYDMate-step8-evidence/logs/fullverify-4g.txt` lines 91–125. `config/detekt/baseline.xml` was not touched.

### B3. Final Codex review: five findings

See [Final review](#final-review-codex).

### B4. The test suite needs more than the build's 2 GB test heap

See [Full suite](#full-suite-memory-diagnosis).

### B5. Garbled negative commands fall through as "Unknown"

In the noisy probes, `neg-01` ("Nie zamykaj okien") became `Nie ziemi taki.`, `Nie wiemy taki.`,
`Nie ziemy taj opiem.` at 0, 5 and 10 dB. The grammar classified all three as `Unknown`, not `Refused`. No
vehicle action ran, but with OpenClaw opted in an `Unknown` utterance is sent to the online assistant as text.
Decide whether that is acceptable for a garbled negated command. Parakeet also produced English or German text for
noisy Polish (`You're here, you'll make it off there.`, `Zunächst ki.`).

### B6. Original accuracy gate still fails

15/16 fixtures (`neg-03`: `Nie ustawiaj temperatur na 22 stopnie.`), human clips 1/2 (`Otwórz szywerdach.`).
Re-run on the final code, whole `PolishNativeSmokeTest` class: 5 tests, 1 failure, 0 skipped. Fixture and
`manifest.json` hashes are identical to the start of the run (`corpus-hashes-before.txt`; manifest `f64c62f7…ccb2e`).

## Commands and results

Environment for every Gradle command: `JAVA_HOME=$(/usr/libexec/java_home -v 17)` (17.0.9),
`ANDROID_HOME=/Users/tomaszkopycki/Library/Android/sdk`, `GRADLE_USER_HOME=/tmp/diag1g/gradle-home`.

**The "offline Gradle is available" statement was false at the start of Step 8.** `/tmp/diag1g` did not exist (the
host had rebooted; uptime 20 min). I recreated `GRADLE_USER_HOME`, copied only the Gradle wrapper distributions
from `~/.gradle/wrapper/dists`, and ran **online** to refill the dependency cache. `~/.gradle/caches` was not
used. The Android 10 AVD from Step 2 is also gone (`emulator -list-avds`: `BM_Polish_1b`, `Pixel_2_API_34`).

| # | Command | Result |
| --- | --- | --- |
| 1 | `./gradlew :app:assembleDebug` | `BUILD SUCCESSFUL in 2m 44s`, 43 tasks (3 executed). Dependency refill. |
| 2 | `./gradlew :app:testDebugUnitTest -I test-diag.init.gradle -DdiagTag=base --continue` (build default 2g test heap) | **Not a clean run.** 618 suites finished, then `java.lang.OutOfMemoryError: Java heap space` (heap dump 3.06 GB). `VoiceDictionaryTest > the_dictionary_loads_and_reports_its_size` and `VoiceLanguageSetupTest > several missing recognizer files…` failed with OOM; the worker then thrashed (`2046M->2046M`, "To-space exhausted"). I killed it. Also `CalendarPeriodsTest` failed. No complete count. |
| 3 | same, `--tests '*VoiceDictionaryTest' --tests '*PolishCommand*Test' --tests '*VoiceLanguageSetupTest'` | `BUILD SUCCESSFUL in 3m 21s`; 1,777 tests, 0 failures. Live heap after full GC: 276 M → 507 M → 1,137 M, then flat at ~1.14 GB. |
| 4 | `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:detekt -I test-heap4g.init.gradle -DdiagTag=full4g --continue` | `BUILD FAILED in 11m 57s`. **9,743 tests, 1 failed, 3 skipped.** `assembleDebug` OK. `lintDebug` OK (0 errors, 192 warnings, 10 information in `lint-results-debug.xml`). `detekt` FAILED (B2). |
| 5 | Pristine `HEAD` clone: `./gradlew :app:testDebugUnitTest --tests '*CalendarPeriodsTest'` | 12 tests, 1 failed, `BUILD FAILED in 2m 27s`: **the same failure on `HEAD`, so it is a baseline failure.** |
| 6 | Pristine `HEAD` clone: `./gradlew :app:testDebugUnitTest :app:detekt -I test-diag.init.gradle -DdiagTag=head2g --continue` (default 2g heap) | `BUILD FAILED in 4m 39s` only because of `CalendarPeriodsTest`: **7,649 tests, 1 failed, 3 skipped; detekt passed; no OOM at 2g.** |

The failing baseline test: `CalendarPeriodsTest > startOfWeek and startOfMonth - Lisbon DST gap lands exactly on
the period start date`, `AssertionError: expected:<-1572739200000> but was:<-1572742800000>` (one hour). Not
investigated further; nothing in this change touches `util/`.

The 3 skipped tests are the same on `HEAD` and with the change: `WriteAllowlistTest "production has at least 100
entries"`, `AgentEvalLiveTest.the_model_handles_the_eval_set`, `FidMapTest "every DiParsData field has a FidMap
entry"`.

The combined plan command is therefore **not green**. Test count with the change is 9,743 versus 7,649 on `HEAD`
(+2,094).

### Config difference (reversible, outside the repo)

`app/build.gradle.kts` sets `test.maxHeapSize = "2g"`. Run 4 used an init script
(`BYDMate-step8-evidence/scripts/test-heap4g.init.gradle`) that sets `maxHeapSize = '4g'` for
`testDebugUnitTest`, adds `-Xlog:gc`, `-XX:+HeapDumpOnOutOfMemoryError` and a suite-progress log. No file in the
repo was changed for this. Run 4 also printed that the Gradle daemon (`org.gradle.jvmargs=-Xmx2048m`) ran out of
heap while running detekt and will restart; detekt still produced its report.

### Full-suite memory diagnosis

- **Reproduced:** run 2 above. The same suite on `HEAD` (run 6) completes at 2g.
- **Where the memory goes:** class histograms (`jcmd GC.class_histogram`, live objects only) are dominated by
  `byte[]`, `String`, `LinkedHashMap$Entry`, `LinkedHashMap`, `ArrayList` and `HashMap$Node[]`. At the
  Polish-dictionary tests: `byte[]` 530 MB, 5.8 M `String` objects, 2.7 M `LinkedHashMap$Entry`. That matches the
  expanded phrase tables of the new Polish dictionary (`keys=370514`, and the existing 4.2 M phrase expansions
  checked by `PolishCommandSafetyTest`, which alone takes 106 s).
- **Numbers:** live heap after full GC rises from 276 M to ~1.14 GB during the Polish dictionary tests and stays
  ~1.96 GB for most of the suite, 2.22 GB at the end. `HEAD` sampled 913 M at most (only three samples, so a
  lower bound). The build's 2 GB cap is below the new steady-state.
- **Not done:** I did not open the 3 GB heap dump (deleted afterwards), so "which object retains what" is
  inferred from histograms, not from a dominator tree. A larger heap is a stop-gap; the same data structure is
  B1's production problem.

## Final review (Codex)

- Workflow: `/Users/tomaszkopycki/.agents/openclaw-agent-skills/skills/autoreview` (`SKILL.md` read).
  Command: `autoreview --mode local --engine codex --max-priority P3 --stream-engine-output --prompt <scope notes>`.
  Engine `codex`, model `gpt-5.6-terra`, thinking `high`, 3 passes, bundle 1,272,787 bytes. The helper chose
  `terra`; I did not override it.
- **Scope deviation:** the helper refuses Codex review when any untracked file is binary (the 16 WAV fixtures,
  all under `app/src/androidTest/assets/voice/pl/`). I did not touch the tree. I reviewed a copy
  (`HEAD` + the working tree, WAVs omitted); `diff -rq` against the real tree shows identical sources. The audio
  fixtures were not reviewed. The prompt notes said so.
- Reviewer rule: findings are advisory. I read the cited code for each one in the real tree before listing it.
  Priority and confidence are the reviewer's. Report: `logs/autoreview.txt`, `logs/autoreview.json`.

| # | Priority | Conf. | Finding | Code evidence (real tree) | My verdict |
| --- | --- | --- | --- | --- | --- |
| R1 | P1 | 0.86 | TTS routing reads the live profile at synthesis time, not the session's captured one. A Polish assistant turn in flight when the profile switches to Russian can speak through a Russian-selected router, and with Russian cloud TTS selected the Polish reply can be uploaded. | `di/VoiceModule.kt:226` `selectedLanguage = { profileStore.activeLanguage() }` | Code matches. Race window and cloud upload path not exercised. |
| R2 | P1 | 0.93 | Saved custom phrases and automations are resolved before `NluParser.classify`, so a saved phrase equal to a negated/quoted/partial Polish request would run. | `voice/VoiceController.kt:677-678` (`automationResolver.match`, `userPhrases.match` before `parse`) | Code matches. This is also the documented precedence and the plan keeps custom automation un-translated, so it may be intended; the owner should decide. A saved phrase is the user's own exact wording. |
| R3 | P2 | 0.90 | Model publish renames the live install aside, then renames staging into place. A kill between the two leaves no install; the old model is stranded in `.previous-*` and the next download deletes it. | `voice/PolishModelManager.kt:155-162` | Code matches; already listed as an open risk in the plan's Step 3 note. |
| R4 | P1 | 0.94 | `ProfileAsrEngine.activate()` takes only `switchGate`; `withAssetsReleased()` takes `releaseMutex`. Activation can commit a language whose files were just deleted, and release can overlap. | `voice/ProfileAsrEngine.kt:100-114` vs `:116-127` | Code matches. Not reproduced. |
| R5 | P1 | 0.91 | Opt-out race: `ask()` checks `prefs.isEnabled()` before launching the turn; `setEnabled(false)` only cancels an already-active turn, so a turn about to start can still send text. | `voice/openclaw/OpenClawAssistant.kt:195`, `cancel()` at `:186` | Code matches. Not reproduced. |

Pass 3 of 3 returned no findings. The reviewer saw the Polish golden corpus covering negated, quoted, partial and
unsupported requests, and found no real credentials in the OpenClaw fixtures. Nothing was fixed or rejected here;
triage belongs to the owning steps (3, 5, 6, 7).

## Phone and emulator evidence

### Test flow shown to the user

Device panel on `emulator-5560`, landscape 1920×1080 at density 260 (the app lays out for a wide head unit; in
portrait the settings pane is unusable). Both settings were reset afterwards (`wm density reset`, rotation auto).
App: the debug APK below, fresh install, first-run wizard, then Settings → Voice agent.

1. Debug APK installed; first-run wizard stepped through. Two system prompts appeared and were declined; BYDMate
   repeatedly showed the Android "Allow USB debugging?" dialog (the app's own in-app adb key; tapping Cancel did not
   dismiss it). I pressed Allow on the emulator twice (second time with "Always allow" ticked; I did not verify the
   box took effect). Emulator only.
2. Settings → Voice agent shows `Język głosu: polski (offline)`: Parakeet `Pobierz (639 MB)`, Gosia
   `Pobierz (20 MB)`, `Włącz polski`, language `Rosyjski`.
3. **Production download of Parakeet, in the app:** `Pobierz (639 MB)` → `Rozpoznawanie mowy (Parakeet): pobieranie 0%`
   → `Gotowy`. On the device: the four files and `silero_vad.onnx` under `files/asr/`; `sha256sum` of the four
   Parakeet files equals the manifest pins (`acfc2b44…`, `179e50c4…`, `3164c13f…`, `d5854467…`). Gosia
   `Pobierz (20 MB)` → `Gotowy`.
4. `Włącz polski` → `Sprawdzam…`, then `Aktywny język głosu: Polski` and `Wróć do rosyjskiego`. Logcat:
   `VoiceLanguageSetup: activation target=pl result=Activated`. **During this step the app hit B1.**
5. OpenClaw: toggle `Włącz asystenta OpenClaw`, address `wss://bobs-mac-mini-openclaw.tail3549c7.ts.net/`,
   `Sparuj` → **`Czeka na zatwierdzenie na komputerze z asystentem`, request id shown, `Parowanie w toku` / `Anuluj`**.
   This is the waiting-for-approval state that Step 7 had not re-run on a live build. It now works on the final
   build. After `devices approve` → `Połączono`.

### Live OpenClaw against the Mini (final build)

Access as authorised: SSH `bobbobster@100.89.220.111`, agent `tomek`, CLI via the node path in
[openclaw-setup.md](openclaw-setup.md). Read the protocol contract and the setup first.

| Step | Visible in the app | Server side |
| --- | --- | --- |
| Before | n/a | `devices list`: `No device pairing entries.`; `tailscale serve status --json` saved |
| `Sparuj` | `Czeka na zatwierdzenie…` + request id | pending: role `operator`, scopes `operator.read`, `operator.write` only, `clientId gateway-client`, `clientMode ui`, `remoteIp 100.99.33.37` |
| `devices approve f2ffc331-…` | `Połączono` | `Paired (1)`, same scopes |
| Text `Czesc, odpowiedz jednym krotkim zdaniem po polsku. Nie uzywaj narzedzi.` (typed ASCII via `adb input text`) | answer `Cześć Tomek, w czym mogę pomóc?` | session `agent:tomek:bydmate-car-85dd573abbde` |
| Spoken reply | Gosia installed: logcat `SherpaTtsEngine: engine created: voice=gosia engineRate=22050`, `synth done: samples=48128` (2.18 s) | none |
| Long request, `Anuluj` | `Anulowano.` | `chat.abort requested: actor=gateway-client … runId=1bd7116d-…`, `lane task error … This operation was aborted` after 7,578 ms |
| `Odłącz`, then `devices remove fb6615e5…` | `Sparuj` button again | `No device pairing entries.`; `tailscale serve status --json` **identical** to before (diff empty) |

Not re-run in Step 8: reconnect after an app restart and the post-revoke rejected-connect message (both passed in
Step 7; see [openclaw-setup.md](openclaw-setup.md)). The emulator uses the Mac's tailnet path
(`remoteIp 100.99.33.37`), so this is not a separate phone's tailnet path.

### Parakeet end to end, emulator

Harness copy `/tmp/step8/harness` (a copy of the tree plus one extra test file, `Step8ProbeTest`, saved in
`BYDMate-step8-evidence/harness/`). The repo was not modified. Device staging: `/data/local/tmp/bydmate-pl-models`
holds `parakeet-tdt-0.6b-v3-int8` (re-downloaded, hashes verified on host and device), `gosia-medium-int8`.
I deleted our own earlier Whisper tiny/small staging directories there (evidence-only per the manifest; the pins
are in the feasibility document) to keep the 3 GiB floor. Device free space stayed ≥ 4.3 GiB; host ≥ 238 GiB.

- Whole `PolishNativeSmokeTest` on the final code: **5 tests, 1 failure (`neg-03`), 0 skipped**
  (`connectedDebugAndroidTest`, `BUILD FAILED in 2m 27s` for the harness copy; 4 compatibility/integrity/Gosia/human
  tests pass, the unchanged accuracy test fails). Compatibility run: `decoded=16 violations=0 peakKb=1045668`.
  Transcripts identical to Step 1i. The first decode pass took 4.0–10.5 s per clip while the host was running a Gradle
  build; the second pass 1.4–3.9 s. These are host-load timings, not a latency measure.
- **Short / noisy / non-Polish probes** (synthetic; macOS `say` voices and a script-mixed pseudo-cabin noise at
  10, 5 and 0 dB SNR on **copies** of fixtures; the 16-fixture corpus was not altered). Clip names, command text and
  noise script are in `BYDMate-step8-evidence/probes` and `scripts/mix_noise.py`. Each clip decoded twice;
  transcripts and verdicts were identical in both rounds. Verdict is `NluParser.classify(text, PL)` only; **no
  `ActionDispatcher`, no vehicle code, and not the controller path** (saved phrases and automations were not in
  play). Zero `Match` for any clip that was not a valid command.

| Clip (labelled probe) | s | Parakeet text | Grammar verdict |
| --- | --- | --- | --- |
| short-01 "Okno." | 0.76 | `Okno` | Unknown |
| short-02 "Nie." | 0.54 | `Nie.` | Unknown |
| short-03 "Zamknij." | 1.01 | `Zamknij.` | Unknown |
| short-04 "Otwórz." | 0.86 | `Otwórz.` | Unknown |
| short-05 "Zamknij okna." (a valid short command) | 1.24 | `Zamknij okna.` | Match (close windows), correct |
| short-06 "Tak." (Polish) | 0.62 | `Так.` (Cyrillic) | Unknown |
| nonpl en "Close all the windows." / "Open the driver's window." | 1.4–1.5 | exact English | Unknown ×2 |
| nonpl de "Schließe alle Fenster." | 1.48 | exact German | Unknown |
| nonpl ru "Закрой все окна." / "Открой окно водителя." | 1.3–1.5 | exact Russian | Unknown ×2 |
| nonpl cs / sk / uk | 1.5–1.8 | Czech, Slovak, Ukrainian text (`Закры у все викна.`) | Unknown ×3 |
| noise only, 3 s | 3.00 | empty | Unknown |
| noisy pos-01 "zamknij wszystkie okna" 10 / 5 / 0 dB | 1.75 | exact / exact / `Zunächst ki.` | Match / Match / Unknown |
| noisy pos-05 "głośność 50 %" 10 / 5 / 0 dB | 2.69 | `Ustaw głośność na 50%.` / `Jest to głośność na 50%.` / `Jest to włożność na 50 procent.` | Match Volume 50 / Refused `command_unsupported` / Unknown |
| noisy cmp-01 (two windows) 10 / 5 / 0 dB | 3.15 | `Odwórz okno kierowcy i zamknij okno pasażera.` / `No quiero feedland pasajera.` / empty | Refused `command_partial` / Unknown / Unknown |
| noisy neg-01 "nie zamykaj okien" 10 / 5 / 0 dB | 1.46 | `Nie ziemy taj opiem.` / `Nie wiemy taki.` / `Nie ziemi taki.` | Unknown ×3 (see B5) |
| noisy neg-02 "nie otwieraj okna kierowcy" 10 / 5 / 0 dB | 1.92 | `Nie otwierając na kierowcy.` / `You know, you make it roughly.` / `You're here, you'll make it off there.` | Refused `command_negated` / Unknown / Unknown |
| noisy quo-01 "powiedz zamknij wszystkie okna" 10 / 5 / 0 dB | 2.05 | `Powiedz zamknij wszystkie okna.` ×2 / `Powiedz nam tu wszystkie okne.` | Refused `command_quoted` ×3 |

Totals over 33 clips: 4 Match (all correct for what was said), 6 Refused, 23 Unknown, 0 wrong vehicle action.
Language detection findings: Russian, Czech, Slovak, Ukrainian, English and German speech are transcribed in
their own language (so Polish grammar never sees Polish words); noisy Polish can flip to English or German text.
Short Polish "Tak." came out Cyrillic. The grammar never broadened a match under any of this. Decode time 55–195 ms
per clip when the host was quiet (not a car figure). The probe set is a small synthetic sample, not a statistical
claim.

- **Gosia, captured and inspected:** four phrases synthesised in the app process and written as WAV
  (`BYDMate-step8-evidence/gosia-wav/gosia-0..3.wav`, 22,050 Hz mono PCM16, 1.71–2.95 s, SHA-256 in `gosia-wav/SHA256SUMS`), then decoded by Parakeet: `Okna zostały zamknięte.`, `Temperatura ustawiona na 22 stopnie.`
  (said "dwadzieścia dwa"), `Nie mam danych o poziomie baterii.`, `Nie rozumiem tego polecenia.`. This shows the
  audio contains the words. **Nobody listened** (the emulator was started with `-no-audio` after the first launch
  failed in the panel). Synthesis ≠ heard audio.

## Android 10 signing

Not re-run in Step 8: the API 29 AVD no longer exists on this host. Recorded results from earlier steps (not
re-verified now): Step 2 smoke test 11/11 on an API 29 arm64 emulator; Step 6 identity-store instrumentation 6/6
on Android 10. An Android 10 or a real head-unit Keystore result for the **final build** is **pending**.

## Parked-car checks (all pending)

Manual car actions need the user and a parked vehicle. No result below is invented.

| Check | Status |
| --- | --- |
| Polish commands and questions offline (internet disconnected) after model downloads | **pending** |
| Repeat sessions (no crash, no memory growth) | **pending** |
| Echo rejection (own Gosia speech not re-heard) | **pending** |
| Profile switching Russian ↔ Polish, including during a session/download | **pending** |
| Memory stability on the head unit (heap limit, B1) | **pending**; the emulator result in B1 is a warning |
| Supported hardware checks (windows, roof/shade, climate, seats, volume) | **pending** |
| Warm-model latency, target response start ≤ 3 s after speech ends | **pending**; no car timing exists |
| Spoken negative commands ("nie otwieraj okna", quoted and compound), **zero wrong vehicle action** | **pending**; any wrong action blocks release |
| Parakeet short/noisy/**cabin-noise**/non-Polish probes with real head-unit microphone | **pending** (only synthetic emulator probes exist) |
| Car Tailscale reachability to `bobs-mac-mini-openclaw.tail3549c7.ts.net:443` | **pending** |
| SpeedCN coexistence with the car's Tailscale | **pending**; a failed route reopens the network design (no public ingress, no assumed hotspot forwarding) |
| OpenClaw enabled in the car | **blocked until the two checks above pass** |

## Distribution notices: blocked

| Item | Finding |
| --- | --- |
| CC-BY-4.0 attribution for NVIDIA parakeet-tdt-0.6b-v3 | **Not user-visible.** The wording exists only as the `PolishModelPackage.attribution` constant in `PolishModelManifest.kt`. `rg` finds no use of it in UI, resources or assets. The settings card says only `Rozpoznawanie mowy (Parakeet)` and the size. **Fails the Step 8 check.** |
| "Modified" notice for the int8/export | Same: only in the manifest constant (`modifications`). Not shown to the user. |
| Notices inside the APK | `unzip -l` of the debug APK lists one notice file, `okhttp3/internal/publicsuffix/NOTICE`. No sherpa-onnx, onnxruntime, Piper, espeak-ng, NVIDIA or CC-BY text. |
| sherpa-onnx 1.13.3 static-link AAR (`app/libs/…aar`, SHA-256 `9f065fe6…86ac7`) | The AAR contains no `LICENSE`/`NOTICE` file (`unzip -l`: `classes.jar` and `.so` files only). The notices it should carry are **unread and unresolved**. |
| Repack `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8` | No README, licence file or licence metadata (Step 1i). Terms unresolved. Not asserted as forbidden. Which exact export script produced the pinned files is **inferred, not verified**. |
| Gosia / Piper / espeak-ng | Model card says dataset CC0 and "fine-tuned from lessac". Piper and espeak-ng licence texts were not read by me in Step 8 and I do not state their terms. Unresolved. |

Until the attribution is visible to the user, the notices are bundled, and the repack, AAR and Gosia terms are
settled, **do not distribute** beyond the owner's own test devices.

## APK

Built by the successful `:app:assembleDebug` task. The combined verification command failed (above), so this is a
**phone/emulator test APK only, not a release candidate.**

```
find app/build/outputs/apk/debug -maxdepth 1 -name '*.apk' -exec shasum -a 256 {} \;
75f1fff85a954fab275ebcba22f0fc78fa5f6658e9e7f10599927b6c79b046cc  app/build/outputs/apk/debug/BYDMate-v4.0.1.apk
```

- Path: `/Users/tomaszkopycki/Developer/BYDMate/app/build/outputs/apk/debug/BYDMate-v4.0.1.apk`, 112,774,637 bytes,
  version 4.0.1 (code 508), `minSdk` 29, debug-signed, arm64 only (`abiFilters`).
- Copy: `/Users/tomaszkopycki/Developer/BYDMate-step8-evidence/BYDMate-v4.0.1-polish-phone-test-debug.apk` (same SHA-256).
- Not installed in a car.

## Residual state and limits

- Mini: no paired devices; `tailscale serve status --json` unchanged; no config change.
- Emulator `emulator-5560`: app uninstalled by the last instrumentation run; `/data/local/tmp/bydmate-pl-models`
  keeps Parakeet and Gosia staging (≈0.7 GB). I launched it myself (`-no-audio`) after the panel failed to boot it, and killed and relaunched it once when
  its package manager broke (`StorageManager` null) after the 650 MB push;
  `emulator-5554` was never touched.
- `docs/plans/2026-10-21-floating-widget-voice-status.md` appeared in the tree after my review copy was taken (it is
  not in `git status`, probably ignored); I did not create or touch it. No commits.
- Evidence outside the repo (volatile `/tmp` was wiped once already): `/Users/tomaszkopycki/Developer/BYDMate-step8-evidence/`.
- Not verified: anything on a head unit; Piper/espeak/AAR/repack terms; audible Gosia output; a physical phone;
  Android 10 with the final build; whether the review's race findings (R1, R4, R5) reproduce.

### Final Android 10 integration checks — 2026-10-21

After correcting the receiver route, direct instrumentation for `RemoteDiagnosticsProbeTest#typedFailureUsesRealCaptureQueueAndWorker` returned `OK (1 test)` in 0.278s. The Mini stored the fresh 681-byte batch with the exact app acknowledgement digest and mode0600. A revoked token produced a retained blocked batch; valid configuration reimport through the Android Downloads picker resumed that same batch without changing its digest. `OpenClawIdentityStoreDeviceTest` returned `OK (6 tests)` in0.198s on API29. These checks do not prove physical-car network or cabin voice performance.
