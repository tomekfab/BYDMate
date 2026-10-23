# Polish native speech feasibility (plan steps 1 to 1i)

Measured 2026-10-20. Scope: does the pinned Sherpa 1.13.3 runtime run Polish Whisper and Piper Gosia, and is the selected tiny model accurate enough? Nothing here is a car result.

## Verdict

- **Step 1i (current, read this first): the active manifest pins Parakeet TDT 0.6B v3 int8 plus Gosia; Whisper is no longer in the manifest.** Pinned compatibility and integrity checks pass on the Android 14 arm64 emulator. The unchanged transcript-accuracy gate still **fails 15/16** (`neg-03`: `temperatur` for `temperatury`), and one of the two human clips misses the roof word (`szywerdach`). No safety invariant failed. No car, phone or release claim. Details: [Step 1i](#step-1i--parakeet-pinned-as-the-development-model). Everything below that mentions Whisper is historical evidence from earlier steps.
- **Runtime: compatible.** Sherpa 1.13.3 loads the multilingual Whisper tiny, base, small and medium int8 exports, transcribes Polish with `language=pl`, `task=transcribe`, and synthesizes Polish with Gosia on an arm64 Android 14 emulator. The Gosia and manifest checks pass.
- **Accuracy, as of step 1b: no candidate passes the 16-utterance gate. (Step 1h later ran Whisper large-v3-turbo int8: 16/16, see below; the other numbers here are unchanged.)** Whisper tiny int8 (pinned) passed **3/16**; base int8 (unpinned) **6/16**; small int8 (**pinned in step 1b**) **15/16**; medium int8 (unpinned comparison, step 1b) **15/16**. Each of the two best models misses a different word: small misses "szyberdach" (`Otwórz się berdech`), medium gets that right and misses "dowcip" (`doscip`). The closed-grammar matcher has no fuzzy matching, so a missed word is a refused or misrouted command.
- **Negation survived in every case.** All four negative utterances kept "nie" with all four models. Tiny and base still garbled the verb after it in several.
- **Speech work stays stopped.** `PolishNativeSmokeTest` fails for every model (tiny 13/16 failed, base comparison 10/16 failed, small 1/16 failed, medium 1/16 failed). Nothing was loosened, no fixture was removed, and no alternative wording replaced a failing one. Steps 3–5 must not start on this evidence.
- **Cost.** Small: 375,485,327 bytes of model files, 1.12 GB peak process memory. Medium: 946,072,270 bytes of model files, 2.47 GB peak process memory; on the 4 GB emulator it was memory-starved (decode 4–51 s per 1.4–4.4 s utterance), so its latency is not usable and it is unlikely to suit a head unit without measurement.
- **Not a phone or car result.** No physical phone was attached to the host in step 1b (`adb devices` listed only emulators), so the phone run is **pending**. Human-recorded corpus and parked-car acceptance are still required before choosing the car model.

- **Step 1h (at the end): Whisper large-v3-turbo int8 passed all 16 fixtures and, by the inferred file mapping, both human clips. First candidate to do so.** It costs 1.04 GB of files, 1.69 GB peak memory and 1.5–5.4 s per clip on a loaded host. Nothing is promoted; the production gate and Steps 3–5 stay stopped.
- **Step 1g (below): Parakeet TDT 0.6B v3 int8 passed 15/16, Vosk small-pl 10/16, Whisper turbo not run (emulator free-space floor; run in step 1h).** The gate was still failed at that point; Parakeet was the leading candidate for a human-corpus run but nothing is promoted.
- **Step 1c diagnosis (below): no supported setting fixes either miss.** Both misses survive the supported Sherpa 1.13.3 Whisper settings I could vary (small got worse: 14/16 and 12/16). Separate finding: the runtime stops decoding after about 6 tokens per second of input audio, which clipped words in one configuration and is a risk for the closed-grammar matcher. Gate and model choice are unchanged; nothing is promoted.

Caveats that limit these numbers:

- The audio is **synthetic**: macOS `say -v Zosia`, not consented human recordings. Clean synthetic speech probably overstates cabin-noise accuracy, and one TTS voice says nothing about speaker variety. Human recordings are still required for the real gate.
- The device is an arm64 **emulator on an Apple M1 Max**, not a phone or the car. Timings are only a lower bound; memory is the emulator process's `VmRSS`/`VmHWM`.
- 16 utterances is a smoke set, not a word-error-rate study.

## Pinned assets

The active manifest (Step 1i) holds only Parakeet (see Step 1i below) and Gosia. The Whisper tiny/small pins and the medium/turbo notes in this section were removed from `PolishModelManifest.kt`; they stay here as the record of what was evaluated.

Definitions: `app/src/main/kotlin/com/bydmate/app/voice/PolishModelManifest.kt` (validated by `PolishModelManifestTest`). SHA-256 and sizes were computed from files downloaded 2026-10-20, and the Whisper hashes also equal the Hugging Face LFS ETags.

**Whisper tiny int8 (ASR)**, repo `csukuangfj/sherpa-onnx-whisper-tiny` at commit `65176e2deb88badc814a94058666cadccc29b61c`, URL `https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/<commit>/<file>`:

| file | bytes | sha256 |
|---|---|---|
| tiny-encoder.int8.onnx | 12937772 | d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434 |
| tiny-decoder.int8.onnx | 89855401 | d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925 |
| tiny-tokens.txt | 816730 | b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126 |

- Encoder metadata read from the file: `is_multilingual=1`, `n_vocab=51865`, `n_mels=80`, `model_type=whisper-tiny`. This is the multilingual export, not `.en`. The float `.onnx` files from the same repo are not used and not downloaded.
- Runtime config: `FeatureConfig(sampleRate=16000, featureDim=80)`, `OfflineWhisperModelConfig(language="pl", task="transcribe")`, `numThreads=4`, `provider="cpu"`, `decodingMethod="greedy_search"`, other fields default.
- The same `tiny-tokens.txt` bytes are used by base and small.

**Whisper small int8 (ASR, step 1b candidate, pinned)**, repo `csukuangfj/sherpa-onnx-whisper-small` at commit `8f3c18b358db4d1f2fc1eae49d75cd20989e4309`, URL `https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/<commit>/<file>`:

| file | bytes | sha256 |
|---|---|---|
| small-encoder.int8.onnx | 112442483 | 4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9 |
| small-decoder.int8.onnx | 262226114 | acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee |
| small-tokens.txt | 816730 | b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126 |

- Sizes and the two ONNX hashes equal the Hugging Face tree API (`lfs.oid`) for that commit; I downloaded the files and computed SHA-256 locally. The tokens file is byte-identical to the tiny one.
- Encoder metadata read from the file (I parsed the ONNX `metadata_props`): `model_type=whisper-small`, `is_multilingual=1`, `n_vocab=51865`, `n_mels=80`, 12 audio and 12 text layers. Same runtime config as tiny, only the file names differ. The float `.onnx` files in that repo (559 MB and 410 MB) are not used.
- Manifest id `whisper-small-int8` is pinned and tamper-checked (`PolishModelManifestTest`). Being pinned makes it a verifiable evaluation candidate, not a production choice.

**Whisper medium int8 (comparison only, not in the manifest)**, repo `csukuangfj/sherpa-onnx-whisper-medium`, commit `8c31d28503847560985df21f90e14f0c736e075e`: `medium-encoder.int8.onnx` 374196283 bytes sha256 `1c54582b4d829de0089f6cb63bbbdb3bf7555398bacaf855fbecf1a84dfd193e`; `medium-decoder.int8.onnx` 571059257 bytes sha256 `595d00a338a365a7bfa0ca7f296cabc639583bef770ab6130df90f49a6412747`; `medium-tokens.txt` 816730 bytes (same hash as above). Metadata: `model_type=whisper-medium`, `is_multilingual=1`, `n_vocab=51865`, `n_mels=80`. I verified these hashes on the host and again on the device with `sha256sum` before the run, but the harness's candidate mode skips manifest verification, so this result is comparison evidence only. I chose medium as the one larger candidate because it is the next size in the same 80-mel export family that the existing config reads; turbo and large-v3 (128 mel, 1.0 GB and 1.8 GB int8 files) would need config changes and were not tried.

**Piper Gosia medium int8 (TTS)**, archive `https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-pl_PL-gosia-medium-int8.tar.bz2`: 21109262 bytes, sha256 `72acac4c4b031725c41a61b3af0314a3d30e1ec2cd83ee410ea5f9e6d2d9d4fb`. The release tag `tts-models` is mutable, so the checksum is the pin. Extracted: 359 files (`pl_PL-gosia-medium.onnx` 18681768 bytes, `.onnx.json`, `tokens.txt`, `MODEL_CARD`, and 355 files in `espeak-ng-data`). The manifest hashes the core files and pins the `espeak-ng-data` file count; per-file hashes are in the Kotlin file. Voice config: 22050 Hz, espeak voice `pl`, noise_scale 0.667, length_scale 1, noise_w 0.8.

**Notices.** What I read on 2026-10-20 and what is unresolved:

- Whisper: `openai/whisper` `LICENSE` on `main` is the MIT License, "Copyright (c) 2022 OpenAI", and its README ("License", line 160) says "Whisper's code and model weights are released under the MIT License." MIT requires the copyright and permission notice to be included in copies, so any bundled or mirrored model needs that notice.
- Conflict: the Hugging Face model card metadata for `openai/whisper-small` says `license: apache-2.0`. The upstream statements disagree (MIT vs Apache-2.0). Unresolved; confirm which applies before redistribution.
- The ONNX conversions are not OpenAI's files. The Hugging Face repos `csukuangfj/sherpa-onnx-whisper-{tiny,small,medium}` that we pin have no README, no licence file and no licence metadata (the API returned an empty `cardData`; `README.md` is a 404 for small). Unresolved: whether the converter grants any additional terms, and whether we may mirror those exact files. We did not copy or mirror them.
- Training data of Whisper is not disclosed by the licence texts I read; I make no claim about it.
- Runtime: I read the first lines of the sherpa-onnx `LICENSE` (Apache License 2.0) and the onnxruntime `LICENSE` (MIT) on their default branches. I did not read the notices bundled inside `sherpa-onnx-static-link-onnxruntime-1.13.3.aar`, so the exact attribution list for the packaged runtime is unverified.
- Gosia: `MODEL_CARD` says dataset license CC0 and the voice is fine-tuned from the lessac US English voice. I did not read the Piper or espeak-ng licence texts, so I do not state their terms; espeak-ng data ships inside the archive.
- Confirmed redistribution requirement so far: ship the MIT notice with any Whisper weights. Everything else above must be settled before packaging or mirroring any file.

## Stage the models (active: Parakeet + Gosia)

The instrumentation test never downloads. The script below verifies every SHA-256 and fails otherwise. The test copies `/data/local/tmp/bydmate-pl-models/<id>` into app-private storage (`filesDir/voice-test/pl/<id>`) because `connectedDebugAndroidTest` uninstalls the app and wipes that directory, then re-verifies size and SHA-256 against `PolishModelManifest`. Parakeet occupies about 0.67 GB twice (staging plus app-private copy), so budget about 1.4 GB plus Gosia and keep 3 GiB free.

```sh
STAGE="$(mktemp -d)"   # new empty directory; run the script from elsewhere
bash -s -- "$STAGE" <<'STAGE_SCRIPT'
set -euo pipefail
STAGE="$1"
C=2bda32ec70b097a55adaa07d9a7173915b43cc78
P="$STAGE/parakeet-tdt-0.6b-v3-int8"; G="$STAGE/gosia-medium-int8"
mkdir -p "$P" "$G"
for f in encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt; do
  curl -fsSL -o "$P/$f" "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/$C/$f"
done
( cd "$P" && shasum -a 256 -c - <<'SUMS'
acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247  encoder.int8.onnx
179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e  decoder.int8.onnx
3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3  joiner.int8.onnx
d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d  tokens.txt
SUMS
)
A="$STAGE/gosia.tar.bz2"
curl -fsSL -o "$A" "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-pl_PL-gosia-medium-int8.tar.bz2"
echo "72acac4c4b031725c41a61b3af0314a3d30e1ec2cd83ee410ea5f9e6d2d9d4fb  $A" | shasum -a 256 -c -
tar -xjf "$A" -C "$G" --strip-components=1 && rm "$A"
STAGE_SCRIPT
export ANDROID_SERIAL=<arm64 device serial>
B=/data/local/tmp/bydmate-pl-models
adb shell mkdir -p $B
adb push "$STAGE/parakeet-tdt-0.6b-v3-int8" $B/
adb push "$STAGE/gosia-medium-int8" $B/
adb shell chmod -R a+rX $B
```

Private human clips (outside the repo, never committed): convert as in Step 1e and push the two WAVs to `/data/local/tmp/bydmate-human/`; `parakeetDecodesPrivateHumanClips` fails if exactly two are not there.

Run (JDK 17, SDK 34, `ANDROID_SERIAL` set; three separate questions, three separate tests):

```sh
./gradlew :app:testDebugUnitTest --tests '*PolishModelManifestTest'
C=com.bydmate.app.voice.PolishNativeSmokeTest
./gradlew :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=$C#provisionedAssetsMatchThePinnedManifest,$C#parakeetRuntimeIsCompatibleAndKeepsSafetyInvariants,$C#parakeetDecodesPrivateHumanClips,$C#gosiaSynthesizesValidPolishPcm"
./gradlew :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=$C#parakeetTranscribesPolishUtterancesKeepingNegation"   # the accuracy gate; fails today
```

## Historical: staging the Whisper models (Steps 1 to 1b; no longer required)

The test methods, `-e whisperModel` / `-e whisperCandidate` switches and manifest ids named in this section and in Steps 1b to 1e were removed in Step 1i. The repo no longer contains that Whisper harness; the disposable copies under `/tmp/diag1*/copy` hold the versions that produced these results. Nothing in the active tests or manifest needs Whisper staging.

The instrumentation test never downloads. Stage once from the repo root; the script verifies every checksum and fails otherwise. The test copies `/data/local/tmp/bydmate-pl-models/<id>` into app-private storage (`filesDir/voice-test/pl/<id>`) because `connectedDebugAndroidTest` uninstalls the app and wipes that directory. It then re-verifies size and SHA-256 against the manifest.

```sh
STAGE="$(mktemp -d)"   # new empty directory
bash -s -- "$STAGE" <<'STAGE_SCRIPT'
set -euo pipefail
STAGE="$1"                       # a new, empty directory
C=65176e2deb88badc814a94058666cadccc29b61c
W="$STAGE/whisper-tiny-int8"; G="$STAGE/gosia-medium-int8"
mkdir -p "$W" "$G"
for f in tiny-encoder.int8.onnx tiny-decoder.int8.onnx tiny-tokens.txt; do
  curl -fsSL -o "$W/$f" "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/$C/$f"
done
( cd "$W" && shasum -a 256 -c - <<'SUMS'
d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434  tiny-encoder.int8.onnx
d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925  tiny-decoder.int8.onnx
b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126  tiny-tokens.txt
SUMS
)
A="$STAGE/gosia.tar.bz2"
curl -fsSL -o "$A" "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-pl_PL-gosia-medium-int8.tar.bz2"
echo "72acac4c4b031725c41a61b3af0314a3d30e1ec2cd83ee410ea5f9e6d2d9d4fb  $A" | shasum -a 256 -c -
tar -xjf "$A" -C "$G" --strip-components=1 && rm "$A"
STAGE_SCRIPT
export ANDROID_SERIAL=<arm64 device serial>
B=/data/local/tmp/bydmate-pl-models
adb shell rm -rf $B && adb shell mkdir -p $B
adb push "$STAGE/whisper-tiny-int8" $B/whisper-tiny-int8
adb push "$STAGE/gosia-medium-int8" $B/gosia-medium-int8
adb shell chmod -R a+rX $B
```

Run (JDK 17, SDK 34, one device selected with `ANDROID_SERIAL` when several are attached):

```sh
./gradlew :app:testDebugUnitTest --tests '*PolishModelManifestTest'
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bydmate.app.voice.PolishNativeSmokeTest
```

Stage small the same way (pinned, hash-checked) into `$B/whisper-small-int8/`, then select it with `-Pandroid.testInstrumentationRunnerArguments.whisperModel=small`. Without `whisperModel` the pinned tiny package runs, as before. `provisionedAssetsMatchThePinnedManifest` now also requires small to be staged.

To compare an unpinned Whisper size, push `<size>-encoder.int8.onnx`, `<size>-decoder.int8.onnx` and `<size>-tokens.txt` to `$B/candidates/whisper-<size>-int8/` and add `-Pandroid.testInstrumentationRunnerArguments.whisperCandidate=<size>`. Candidate runs skip manifest verification and log a warning.

## Test set

`app/src/androidTest/assets/voice/pl/manifest.json` lists 16 utterances, each with reference text, expected intent label, required tokens and forbidden tokens: 6 positive, 4 negative ("nie …"), 2 quoted, 2 compound and 2 unrelated. WAVs are PCM16 mono 16 kHz. A transcript passes when it is lowercased, has punctuation removed and diacritics folded, and contains every required token as a whole word (`a|b` alternatives; "22" counts for "dwadzieścia dwa", "50%" for "pięćdziesiąt procent"). Negative utterances must also contain "nie". Inflection variants (temperatura/temperaturę) pass; wrong verbs do not. The expected-intent labels are descriptive only; no NLU runs in this step.

## Results

Step 1 tiny and base numbers: emulator `BM_Polish_34` (stock Pixel 2 API 34 config, 3 GB RAM, since deleted). Step 1b small and medium numbers, plus a tiny re-run with the updated harness: emulator `BM_Polish_1b` (`emulator-5560`, AVD from `system-images;android-34;google_apis_playstore;arm64-v8a`, Pixel 2 profile, 4 GB RAM, 4 cores): Android 14 (SDK 34), `arm64-v8a`, host Apple M1 Max. Another emulator (`emulator-5554`, owned by a parallel task) was running on the same host throughout, so timings are noisy. Numbers come from logcat tag `PolishSmoke` and the test output.

| model | passed | load | RSS before → after load (MB) | peak RSS (MB) | decode per utterance |
|---|---|---|---|---|---|
| tiny int8 (pinned), step 1 | 3/16 | 390 ms (688 ms first run) | 323 → 437 | 529 | 79–171 ms |
| tiny int8 (pinned), 1b re-run | 3/16 | 2030 ms | 303 → 405 | 506 | not tabulated |
| base int8 (unpinned), step 1 | 6/16 | 750 ms | 322 → 497 | 653 | 160–304 ms |
| small int8 (**pinned**), 1b | 15/16 | 4185 ms | 324 → 697 | 1092 | 1.2–8.3 s (host contention; step 1 measured 0.5–1.1 s) |
| medium int8 (unpinned), 1b, second run | 15/16 | 36397 ms | 255 → 1260 | 2412 | 4.5–51 s (memory starved) |

Peak RSS is `VmHWM` of the instrumentation process in KB divided by 1024 (small 1118408 KB, medium 2469428 KB; the first medium run, whose first lines left the logcat buffer, reported 2349636 KB and 15/16 with the same failing utterance). Utterance audio is 1.4–4.4 s. No physical phone result exists. Phone and car latency and memory remain unmeasured.

Download and storage (recorded before the medium run): small 375,485,327 bytes, medium 946,072,270 bytes; the test also copies each package into app-private storage, so a device briefly holds twice that. Host free space was 21 GiB before the downloads and 11 GiB after medium; the emulator data partition was set to 8 GB (5.1 GB free after staging medium). Runtime libraries were not changed.

Gosia (`gosiaSynthesizesValidPolishPcm`, passed in the small run on `BM_Polish_1b`): load 3543 ms; "Okna zostały zamknięte." 1.75 s of audio in 701 ms, 2.70 s in 607 ms, 4.82 s in 1322 ms; rms 0.075–0.082, peak ≤ 0.614. The test checks PCM validity only (22050 Hz, finite samples, not silent, not clipped, longer text gives longer audio); nobody listened to the output.

### Utterance-level transcripts

✅ = passed the criterion above. Base is the step 1 result; small and medium are step 1b.

| id | reference | tiny int8 | base int8 | small int8 (pinned) | medium int8 (unpinned) |
|---|---|---|---|---|---|
| cmp-01 | otwórz okno kierowcy i zamknij okno pasażera | ❌ Odwusz oknoki rowcy i zamkni oknopasa | ❌ Odwóż oknok i rowcy i zamknij oknop | ✅ Otwórz okno kierowcy i zamknij okno pasażera | ✅ Otwórz okno kierowcy i zamknij okno pasażera |
| cmp-02 | zamknij wszystkie okna i ustaw temperaturę na dwadzieścia dwa stopnie | ✅ Zamknij wszystkie okna i ustaw temperatura na 22°C. | ✅ Zamknij wszystkie okna i ustaw temperaturę na 22 stopnie. | ✅ Zamknij wszystkie okna i ustaw temperaturę na 22 stopnie. | ✅ Zamknij wszystkie okna i ustaw temperaturę na 22 stopnie. |
| neg-01 | nie zamykaj okien | ❌ Nie zamyka Jochen. | ❌ Nie zamykajokiem. | ✅ Nie zamykaj okien. | ✅ Nie zamykaj okien. |
| neg-02 | nie otwieraj okna kierowcy | ❌ Nie otwierająk na kierowce. | ❌ Nie otwierajok na kierowce. | ✅ Nie otwieraj okna kierowcy. | ✅ Nie otwieraj okna kierowcy |
| neg-03 | nie ustawiaj temperatury na dwadzieścia dwa stopnie | ✅ Nie ustawiaj temperatury na 2200. | ✅ Nie ustawiaj temperatury na 22 stopnie. | ✅ Nie ustawiaj temperatury na 22 stopnie. | ✅ Nie ustawiaj temperatury na 22 stopnie. |
| neg-04 | nie zamykaj wszystkich okien | ❌ Nie zamyka i wszystkich okien. | ✅ Nie zamykaj wszystkich okien. | ✅ Nie zamykaj wszystkich okien. | ✅ Nie zamykaj wszystkich okien. |
| pos-01 | zamknij wszystkie okna | ❌ Zamknili wszystkie okna. | ✅ Zamknij wszystkie okna. | ✅ Zamknij wszystkie okna. | ✅ Zamknij wszystkie okna. |
| pos-02 | otwórz okno kierowcy | ❌ Odwuszok no kierowce | ❌ Odwóż okno kierowcy | ✅ Otwórz okno kierowcy | ✅ Otwórz okno kierowcy |
| pos-03 | ustaw temperaturę na dwadzieścia dwa stopnie | ❌ ustaw ten wyratore na 22. | ✅ Ustaw temperaturę na 22 stopnie. | ✅ Ustaw temperaturę na 22 stopnie. | ✅ Ustaw temperaturę na 22 stopnie. |
| pos-04 | jaki jest poziom baterii | ❌ Jak jest pożąbaterii? | ❌ Jak jest poziom baterii? | ✅ Jaki jest poziom baterii? | ✅ Jaki jest poziom baterii? |
| pos-05 | ustaw głośność na pięćdziesiąt procent | ✅ Ustaw głośność na 50%. | ❌ Ustaw głośności na 50% | ✅ Ustaw głośność na 50%. | ✅ Ustaw głośność na 50%. |
| pos-06 | otwórz szyberdach | ❌ odwuszy berdach. | ❌ odwóższy berdach | ❌ Otwórz się berdech | ✅ Otwórz szyberdach |
| quo-01 | powiedz zamknij wszystkie okna | ❌ Powiedz zamknili wszystkie okna. | ❌ Powiedz zamknieli wszystkie okna. | ✅ Powiedz zamknij wszystkie okna. | ✅ Powiedz "Zamknij wszystkie okna". |
| quo-02 | Kasia powiedziała otwórz okno kierowcy | ❌ Kasia powiedziała od wuszok noki rówcy. | ❌ Kasia powiedziała otwórz oknok i rowcy | ✅ Kasia powiedziała o otwórz okno kierowcy. | ✅ Kasia powiedziała "Otwórz okno kierowcy" |
| unr-01 | jaka jest dzisiaj pogoda w Krakowie | ❌ Jaka jest dd dzisiaj pogodew krakowie. | ✅ jaka jest do dzisiaj pogoda w Krakowie? | ✅ Jaka jest dzisiaj pogoda w Krakowie? | ✅ Jaka jest dzisiaj pogoda w Krakowie? |
| unr-02 | opowiedz mi krótki dowcip | ❌ opowiec mikrótki doscip | ❌ opowiedz mikrótki do wcip. | ✅ Opowiedz mi krótki dowcip. | ❌ Opowiedz mi krótki doscip. |

The tiny column is the step 1 run; the 1b re-run of pinned tiny again failed 13/16, starting with `pos-01` `Zamknili wszystkie okna.` (I did not re-tabulate every tiny transcript). Lost negation: none in any model (all four negative utterances contain "nie" in every column). The small transcripts for `quo-02` and the pass/fail pattern equal the step 1 unpinned small run, as expected for a deterministic greedy decode of the same bytes. The quoted utterances show that medium adds quotation marks, which the matcher strips as punctuation.

Not yet measured: a physical phone, human speakers, noise, Silero VAD segmentation, a repeated-session memory trend, the head unit, and the SpeedCN/Tailscale coexistence in the car.

## Red/green record

Step 1:

- Unit test, no API: Kotlin compile error `Unresolved reference 'PolishModelFile'` and the other new types.
- Unit test, stub API: `16 tests completed, 13 failed` (the 3 passes were vacuous on the stub).
- Unit test, implemented: `tests="16" skipped="0" failures="0"`, `BUILD SUCCESSFUL`.
- Instrumentation, before staging: all 3 tests failed with `Verified Polish model assets are not provisioned` / `Model assets are not provisioned or fail verification`.
- Instrumentation, staged tiny: manifest check and Gosia passed; the Whisper test failed `13/16 utterances failed`.

Step 1b (all with JDK 17, `ANDROID_HOME=/Users/tomaszkopycki/Library/Android/sdk`):

- Unit test, new assertions, no `WHISPER_SMALL_INT8`: `PolishModelManifestTest.kt` failed to compile with `Unresolved reference 'WHISPER_SMALL_INT8'`.
- Unit test, stub (small = copy of tiny with a new id): `21 tests completed, 4 failed` (pins exact commit/urls/sizes/checksums; tamper rejection; licence status; tiny files do not satisfy small). One new test passed vacuously on the stub.
- Unit test, implemented: `BUILD SUCCESSFUL`, `tests="21" skipped="0" failures="0" errors="0"`. One assertion in my own test was wrong at first (it counted a same-size tokens file as a size error) and was fixed to expect 2 size errors and 1 sha256 mismatch.
- Instrumentation, `whisperModel=small` (full class, 3 tests): manifest check and Gosia passed; Whisper failed `1/16 utterances failed: pos-06 [positive]: 'Otwórz się berdech' (missing=[szyberdach])`. 0 skipped.
- Instrumentation, `whisperCandidate=medium` (Whisper test only, unpinned comparison): failed `1/16 utterances failed: unr-02 [unrelated]: 'Opowiedz mi krótki doscip.' (missing=[dowcip])`. 0 skipped.
- Instrumentation, default (tiny, full class, the plan's verify command unchanged): `13/16 utterances failed`, the red result is preserved. 0 skipped.
- Phone: **pending**, no physical Android device attached.

## Step 1c — why small misses szyberdach and medium misses dowcip

Measured 2026-10-20 on emulator `BM_Polish_1b` (`emulator-5560`, AVD `BM_Polish_1b`, Android 14, SDK 34, `arm64-v8a`, 4 GB RAM, 4 cores). No production code, fixture audio, expected phrase or assertion was changed. Nothing here is a phone or car result, and nothing is promoted. The step 1b failing runs above stay as they are.

**Answer.** No supported decoding setting fixes either miss. The two recordings are decodable (each is transcribed correctly by the other model), so "bad audio" alone does not explain both. Pronunciation of one synthetic voice cannot be ruled out; I could not listen. VAD segmentation is not involved in this harness. The best-supported explanation is model decoding: both misses are single-word errors that move when only the supported window or timestamp setting changes. That is inference; I did not inspect logits.

### Evidence and inference

| Question | Evidence (measured or read) | Inference (not verified) |
|---|---|---|
| Pronunciation | `pos-06.wav` is 1.436 s; `unr-02.wav` is 2.028 s (`afinfo`). Speech starts in the first 20 ms in both (no leading silence; `pos-02` looks the same) and ends in digital silence (about −86 dB). `pos-06` has one 50 ms pause; `unr-02` has four pauses of 50–80 ms. Medium transcribes `pos-06` as "Otwórz szyberdach"; small baseline transcribes `unr-02` as "Opowiedz mi krótki dowcip." | The audio carries enough information for a larger or smaller model to get each word. Small hears "szy" as "się" and "dach" as "dech"; that fits a sibilant/vowel confusion, but I cannot tell whether the TTS voice or the model causes it. |
| Segmentation | The harness feeds each whole WAV to one stream (`PolishNativeSmokeTest.kt`, `decode()`); no VAD or chunking runs. Sherpa pads the features with zeros (`tail_paddings`, default 1000 frames). | VAD segmentation cannot explain these two results. A real capture path with VAD is untested. |
| Model decoding | Changing only supported settings changes transcripts: tailPaddings 3000 changed 3 of 16 and turned `unr-02` into "do wcip" (a word split); segment timestamps changed 6 of 16. | Both words sit near the model's decision boundary (a loanword, and a word with a w/c cluster). Small and medium disagree on them for that reason. |

Waveform numbers come from a throwaway script (`/tmp/diag/analyze.py`, 10 ms RMS frames, speech = within 30 dB of peak). I did not map pauses to words.

### Supported Whisper settings in Sherpa 1.13.3

Read from the vendored AAR (`javap` on `classes.jar`) and from upstream tag `v1.13.3` (commit `330609dab49be6ee8b30702918ca7abbbad1286a`), files `sherpa-onnx/csrc/offline-recognizer-whisper-impl.h`, `offline-whisper-greedy-search-decoder.cc`, `offline-whisper-model-config.cc`, `offline-recognizer.cc`. The upstream files are the tag's source; the AAR's native library is the official release asset, but I did not disassemble it.

| Setting | What the source says | Used here |
|---|---|---|
| `language`, `task` | `pl` and `transcribe` set the initial tokens `[sot, lang, task]`. | fixed since step 1 |
| `tailPaddings` | `<=0` means 1000 frames of zero padding; the encoder window is `min(frames + padding, 3000)`. 3000 gives the full 30 s window of the OpenAI reference. The help text suggests 300 for multilingual models. | D1 (3000) |
| `enableSegmentTimestamps` | Drops the `no_timestamps` token; the model emits timestamp tokens, which are filtered out of the text. | D2 |
| `enableTokenTimestamps` | Needs an ONNX export with attention outputs; the pinned files are not known to have one. | not run |
| `decodingMethod` | Whisper accepts only `greedy_search`; any other value logs an error and exits the process (`SHERPA_ONNX_EXIT(-1)`). | `greedy_search` |
| `maxActivePaths`, `hotwordsFile`, `hotwordsScore` | Documented for `modified_beam_search`, which upstream says applies to transducer models only. | not usable |
| `ruleFsts`, `ruleFars`, `hr` | Applied to each token string after decoding (`ApplyInverseTextNormalization`, `ApplyHomophoneReplacer`). That edits recognition output, which this step forbids. | not run |
| prompt, temperature, beam, logit suppression | Not present in the 1.13.3 Whisper path: the decoder builds only `sot, lang, task, [no_timestamps]` and takes the arg-max token. | none exist |

I did not verify `blankPenalty` for Whisper; I found no use of it in the Whisper decoder.

### Separate finding: a token budget of about 6 per second of input audio

`offline-whisper-greedy-search-decoder.cc` lines 196–200: `num_possible_tokens = num_feature_frames / 100.0 * 6`, capped at `n_text_ctx / 2`, and the decode loop stops there ("assume at most 6 tokens per second"). The frame count is that of the real audio, not the padded window, so `tailPaddings` does not change it, and the Kotlin API has no field for it.

- **Measured (D0, small, unchanged settings):** 5 of 16 utterances end exactly at the budget (`pos-02` 9/9, `pos-06` 8/8, `neg-01` 8/8, `quo-02` 15/15, `cmp-01` 18/18). Of those, `pos-02`, `pos-06` and `cmp-01` have no final punctuation; every one of the other 11 ends in `.`, `?` or `%.`. This fits a clipped final "." token. I did not log the end-of-text token, so it is not proven. Frames are estimated as `samples / 160`; the budget does not change for ±1 frame.
- **Measured (D2):** with timestamp tokens using budget, words were cut mid-word: `pos-02` "kierow", `cmp-01` "pasaż". Two of the four D2 failures are clipped words; `quo-01` ("zamknili") and `pos-06` are changed words. The mechanism (timestamp tokens counting against the budget) is my reading of the source and fits the counts; I did not log the timestamp tokens.
- **Does not explain the two misses.** Decoding is token by token, so the cap can only drop trailing tokens; it cannot change earlier ones. By my estimate a correct "szyberdach" needs about 7 tokens (`Ot|w|ór|z| szy|ber|dach`; I did not run the tokenizer), within the budget of 8.
- **Why it matters:** a fast or long required word at the end of a command can be clipped, and the matcher has no fuzzy match. A remedy would be input-side (more audio frames, for example trailing silence in the capture path). I did not test that, because it changes the input, not a decoding setting.

### Diagnostic runs

Harness: a copy of the working tree at `/tmp/diag/copy` (outside the repo). Only the copy of `PolishNativeSmokeTest.kt` differs: it reads optional `whisperTailPaddings` and `whisperSegmentTimestamps` arguments, logs `result.tokens`, and writes `report-asr-<model>-diag.json`. Corpus, `manifest.json` and WAVs are identical to the repo (`diff -r` on `app/src/androidTest/assets`: identical), and the matching and assertion code is unchanged. Models: the staged files already on the emulator, hash-checked in earlier steps; nothing was downloaded.

Build: `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew --offline :app:assembleDebug :app:assembleDebugAndroidTest -q` (exit 0), then `adb install -r -t` for both APKs. Every run:

```sh
ANDROID_SERIAL=emulator-5560 adb shell am instrument -w \
  -e class com.bydmate.app.voice.PolishNativeSmokeTest#whisperTranscribesPolishUtterancesKeepingNegation \
  <extra -e args> com.bydmate.app.test/androidx.test.runner.AndroidJUnitRunner
```

| run | extra args | counts as | result |
|---|---|---|---|
| D0 | `-e whisperModel small` | unchanged reference, for token counts | 15/16, fails `pos-06` "Otwórz się berdech" (same as step 1b) |
| D1 | `-e whisperModel small -e whisperTailPaddings 3000` | variation 1 of 3 | **14/16**: `pos-06` "Otwórz się Berdech", `unr-02` "Opowiedz mi krótki do wcip." |
| D2 | `-e whisperModel small -e whisperSegmentTimestamps true` | variation 2 of 3 | **12/16**: `pos-02` "Otwórz okno kierow", `pos-06` "Otwórz się w ber", `quo-01` "Powiedz zamknili wszystkie okna.", `cmp-01` "Otwórz okno kierowcy i zamknij okno pasaż" |
| D3 | `-e whisperCandidate medium -e whisperTailPaddings 3000` | variation 3 of 3 | **inconclusive**: the process crashed after `pos-01` (1/16 decoded, passed) |

D3 crash: `INSTRUMENTATION_RESULT: shortMsg=Process crashed.`; logcat shows `lowmemorykiller: Kill 'com.bydmate.app' (4894) ... to free 2619288kB rss, 372160kB swap; reason: min watermark is breached even after kill`. Medium with a full 30 s window needs more memory than this 4 GB emulator has. I used all three variations and did not retry. Medium's `dowcip` miss was not re-run in this step; the step 1b result stands. Timings and peak memory (D0 1,117,452 KB, D1 1,623,392 KB `VmHWM`) are not comparable because another emulator (`emulator-5554`, a parallel task) was running.

All 16 fixtures, every run (✅ passes the matching rule; D0 shows tokens used / estimated budget):

| id | reference | D0 small, unchanged (tokens/cap) | D1 small, tailPaddings=3000 | D2 small, segment timestamps |
|---|---|---|---|---|
| pos-01 | zamknij wszystkie okna | ✅ Zamknij wszystkie okna. (7/10) | ✅ Zamknij wszystkie okna. | ✅ Zamknij wszystkie okna. |
| pos-02 | otwórz okno kierowcy | ✅ Otwórz okno kierowcy (9/9 at cap) | ✅ Otwórz okno kierowcy | ❌ Otwórz okno kierow |
| pos-03 | ustaw temperaturę na dwadzieścia dwa stopnie | ✅ Ustaw temperaturę na 22 stopnie. (10/16) | ✅ Ustaw temperaturę na 22 stopnie. | ✅ Ustaw temperaturę na 22 stopnie. |
| pos-04 | jaki jest poziom baterii | ✅ Jaki jest poziom baterii? (8/10) | ✅ jaki jest poziom baterii. | ✅ Jaki jest poziom baterii? |
| pos-05 | ustaw głośność na pięćdziesiąt procent | ✅ Ustaw głośność na 50%. (9/16) | ✅ Ustaw głośność na 50%. | ✅ Ustaw głośność na 50%. |
| pos-06 | otwórz szyberdach | ❌ Otwórz się berdech (8/8 at cap) | ❌ Otwórz się Berdech | ❌ Otwórz się w ber |
| neg-01 | nie zamykaj okien | ✅ Nie zamykaj okien. (8/8 at cap) | ✅ Nie zamykaj okien. | ✅ Nie zamykaj okien |
| neg-02 | nie otwieraj okna kierowcy | ✅ Nie otwieraj okna kierowcy. (10/11) | ✅ Nie otwieraj okna kierowcy. | ✅ Nie otwieraj okna kierowcy. |
| neg-03 | nie ustawiaj temperatury na dwadzieścia dwa stopnie | ✅ Nie ustawiaj temperatury na 22 stopnie. (12/18) | ✅ Nie ustawiaj temperatury na 22 stopnie. | ✅ Nie ustawiaj temperatury na 22 stopnie. |
| neg-04 | nie zamykaj wszystkich okien | ✅ Nie zamykaj wszystkich okien. (9/12) | ✅ Nie zamykaj wszystkich okien. | ✅ Nie zamykaj wszystkich okien. |
| quo-01 | powiedz zamknij wszystkie okna | ✅ Powiedz zamknij wszystkie okna. (9/12) | ✅ Powiedz zamknij wszystkie okna. | ❌ Powiedz zamknili wszystkie okna. |
| quo-02 | Kasia powiedziała otwórz okno kierowcy | ✅ Kasia powiedziała o otwórz okno kierowcy. (15/15 at cap) | ✅ Kasia powiedziała o otwórz okno kierowcy. | ✅ Kasia powiedziała o otwórz okno kierowcy |
| cmp-01 | otwórz okno kierowcy i zamknij okno pasażera | ✅ Otwórz okno kierowcy i zamknij okno pasażera (18/18 at cap) | ✅ Otwórz okno kierowcy i zamknij okno pasażera | ❌ Otwórz okno kierowcy i zamknij okno pasaż |
| cmp-02 | zamknij wszystkie okna i ustaw temperaturę na dwadzieścia dwa stopnie | ✅ Zamknij wszystkie okna i ustaw temperaturę na 22 stopnie. (17/26) | ✅ Zamknij wszystkie okna i ustaw temperaturę na 22 stopnie. | ✅ Zamknij wszystkie okna i ustaw temperaturę na 22 stopnie. |
| unr-01 | jaka jest dzisiaj pogoda w Krakowie | ✅ Jaka jest dzisiaj pogoda w Krakowie? (11/14) | ✅ Jaka jest dzisiaj pogoda w Krakowie? | ✅ Jaka jest dzisiaj pogoda w Krakowie? |
| unr-02 | opowiedz mi krótki dowcip | ✅ Opowiedz mi krótki dowcip. (10/12) | ❌ Opowiedz mi krótki do wcip. | ✅ Opowiedz mi krótki dowcip. |

Remaining failures: D0 `pos-06`; D1 `pos-06`, `unr-02`; D2 `pos-02`, `pos-06`, `quo-01`, `cmp-01`; medium (step 1b, not repeated) `unr-02`. No run lost "nie" in a negative utterance. Raw logs were in `/tmp/diag/` (`d0`–`d3` `.logcat.txt`, `.instrument.txt`, `.cmd`) and are not kept in the repo.

Device note: `device_open` for `BM_Polish_1b` first failed ("could not start"). I booted the AVD with `emulator -avd BM_Polish_1b -port 5560 -no-window -no-audio -no-snapshot`, and `device_open` then succeeded. The runs used `adb` and `am instrument`, not UI driving. The emulator's own "more than one emulator" warnings appeared because two emulators were attached; I pinned `ANDROID_SERIAL=emulator-5560` for every run.

### Recommendation

1. Keep the 16-utterance gate and the pinned small candidate as they are. Do not promote a model and do not download another one on this evidence: a larger model is not shown to fix the problem, and medium does not fit this 4 GB emulator at a 30 s window.
2. Do not tune `tailPaddings` or segment timestamps: both made small worse.
3. Decide how to handle the token budget before step 3: either confirm in a diagnostic copy that appended silence (input side) restores trailing tokens without changing the words, or accept and document the limit. This needs your decision because it changes the input, not a setting.
4. A single synthetic voice cannot tell "model near the boundary" from "this voice's rendering" for two words. Human-recorded utterances, with consent, are the next real signal; the phone and parked-car checks remain pending.

## Step 1d — does appended silence fix the short-audio token cap?

Measured 2026-10-20 on emulator `BM_Polish_1b` (`emulator-5560`, AVD `BM_Polish_1b`, Android 14, SDK 34, `arm64-v8a`, 4,016,812 kB `MemTotal`, host Apple M1 Max). Pinned `whisper-small-int8`, unchanged settings (`language=pl`, `task=transcribe`, `tailPaddings` default 1000, no timestamps, `greedy_search`). No production code, fixture audio, `manifest.json` (SHA-256 identical) or assertion changed, and no model was downloaded. Nothing here is a phone or car result.

**Answer.** No. Appending 1 s or 2 s of digital silence restores a final "." on three fixtures but fixes no failing word, and it breaks two passing fixtures (`quo-01`, `quo-02`). Baseline 15/16, +1 s 13/16, +2 s 13/16. The original native gate stays failed at 15/16; the padded 13/16 results are diagnostic only and do not count against or toward that gate.

### What the upstream code says (read, not run)

Tag `v1.13.3`, commit `330609dab49be6ee8b30702918ca7abbbad1286a`:

- [`offline-whisper-greedy-search-decoder.cc` lines 196–201](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.3/sherpa-onnx/csrc/offline-whisper-greedy-search-decoder.cc#L196-L201): the cap is `num_possible_tokens = min(int(num_feature_frames / 100.0 * 6), n_text_ctx / 2)`. The loop checks `max_token_id == eot` at the top and `break`s *before* pushing the token, so the end-of-text token is never in `tokens`. The loop also ends silently when `i` reaches the cap, without looking at the next token.
- [`offline-recognizer-whisper-impl.h` lines 109–135](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.3/sherpa-onnx/csrc/offline-recognizer-whisper-impl.h#L109-L135): `num_feature_frames` passed to the decoder is the real audio frame count, before `tail_padding_frames` is added. So appended silence in the *input* raises the cap by 6 tokens per second; `tailPaddings` does not.
- Consequence for the evidence: `tokens.size() < cap` means the loop left through EOT (only other exit is the text-context limit, 448, far away). `tokens.size() == cap` is ambiguous: the cap cut the output, or EOT would have come next anyway. The Kotlin API (`result.tokens`) does not expose EOT, and I did not read logits. I did not read the OpenAI reference decoder.

### Exact commands

```sh
# Harness: new copy outside the repo (APFS clone of the step 1c copy; only PolishNativeSmokeTest.kt edited)
cp -cR /tmp/diag/copy /tmp/diag1d/copy
# edit: optional -e padSeconds appends N s of zero samples after readWav16kMono; budget now (frames/100.0*6) capped at 224
cd /tmp/diag1d/copy && JAVA_HOME=$(/usr/libexec/java_home -v 17) ANDROID_HOME=$HOME/Library/Android/sdk \
  ./gradlew --offline :app:assembleDebug :app:assembleDebugAndroidTest -q          # exit 0
ANDROID_SERIAL=emulator-5560 adb install -r -t app/build/outputs/apk/debug/BYDMate-v4.0.1.apk                              # Success
ANDROID_SERIAL=emulator-5560 adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk           # Success
# one run per condition, N = 0, 1, 2
ANDROID_SERIAL=emulator-5560 adb shell "am instrument -w \
  -e class com.bydmate.app.voice.PolishNativeSmokeTest#whisperTranscribesPolishUtterancesKeepingNegation \
  -e whisperModel small -e padSeconds N com.bydmate.app.test/androidx.test.runner.AndroidJUnitRunner"
```

Diff checks: `diff -rq app/src/main`, `app/src/test` and `app/build.gradle.kts` between the repo and the copy report no differences; `app/src/androidTest` differs only in `PolishNativeSmokeTest.kt`. The padding is applied inside the copy of the test after the WAV is read, so the repo WAVs are untouched. Padding is zero samples (digital silence). The emulator `emulator-5554` (another task's, 2 GB) was also attached; every command pinned `ANDROID_SERIAL=emulator-5560`. `device_open` was called for `emulator-5554` (the only booted Android device at that time); `BM_Polish_1b` holds the staged models, so I booted it with `emulator -avd BM_Polish_1b -port 5560 -no-window -no-audio -no-snapshot`. The runs used `adb` and `am instrument`, not UI driving.

Actual instrument results (`Tests run: 1, Failures: 1` each, 0 skipped, because any failed utterance fails the test; all 16 utterances ran in each):

| run | result | failing utterances |
|---|---|---|
| baseline (`padSeconds 0`) | 15/16 | `pos-06` "Otwórz się berdech" (same as step 1b and D0) |
| +1 s | 13/16 | `pos-06` "Otwórz się w Berdach."; `quo-01` "Powiedz zamknili wszystkie okna."; `quo-02` "Wkrośka się powiedziała o otwórz okno kierowcy." (`kasia` missing) |
| +2 s | 13/16 | `pos-06` "Otwórz się Berdech."; `quo-01` same as +1 s; `quo-02` "Wkrośnia się powiedziała o otwórz okno kierowcy." |

Memory (`VmHWM` of the instrumentation process): baseline 1,117,496 kB, +1 s 1,119,984 kB, +2 s 1,147,388 kB; RSS before load → after load → after decode: 282,356 → 710,636 → 347,892 kB (baseline). Decode per utterance: baseline 862–5522 ms (first-run warm-up included), +1 s 793–2390 ms, +2 s 668–1689 ms; the other emulator was running, so timings are not comparable. Raw logs and JSON reports stay outside the repo in `/tmp/diag1d/` (`*.instrument.txt`, `*.logcat.txt`, `report-pad{0,1,2}.json`).

### All 16 fixtures

✅ passes the matching rule (unchanged). `in` is input duration in seconds (baseline / +1 s / +2 s). `tok` is `tokens/cap` per condition; `*` marks `tokens == cap`. The cap is my estimate from `samples / 160` frames, the same estimate as step 1c (the baseline numbers equal step 1c's D0).

| id | in (s) | pass | tok base · +1 s · +2 s | baseline transcript | +1 s transcript | +2 s transcript |
|---|---|---|---|---|---|---|
| pos-01 | 1.750 / 2.750 / 3.750 | ✅ ✅ ✅ | 7/10 · 7/16 · 7/22 | Zamknij wszystkie okna. | Zamknij wszystkie okna. | Zamknij wszystkie okna. |
| pos-02 | 1.657 / 2.657 / 3.657 | ✅ ✅ ✅ | 9/9* · 10/15 · 10/21 | Otwórz okno kierowcy | Otwórz okno kierowcy. | Otwórz okno kierowcy. |
| pos-03 | 2.830 / 3.830 / 4.830 | ✅ ✅ ✅ | 10/16 · 10/22 · 10/28 | Ustaw temperaturę na 22 stopnie. | Ustaw temperaturę na 22 stopnie. | Ustaw temperaturę na 22 stopnie. |
| pos-04 | 1.726 / 2.726 / 3.726 | ✅ ✅ ✅ | 8/10 · 8/16 · 8/22 | Jaki jest poziom baterii? | Jaki jest poziom baterii? | Jaki jest poziom baterii? |
| pos-05 | 2.690 / 3.690 / 4.690 | ✅ ✅ ✅ | 9/16 · 9/22 · 9/28 | Ustaw głośność na 50%. | Ustaw głośność na 50%. | Ustaw głośność na 50%. |
| pos-06 | 1.436 / 2.436 / 3.436 | ❌ ❌ ❌ | 8/8* · 10/14 · 9/20 | Otwórz się berdech | Otwórz się w Berdach. | Otwórz się Berdech. |
| neg-01 | 1.459 / 2.459 / 3.459 | ✅ ✅ ✅ | 8/8* · 8/14 · 8/20 | Nie zamykaj okien. | Nie zamykaj okien. | Nie zamykaj okien. |
| neg-02 | 1.924 / 2.924 / 3.924 | ✅ ✅ ✅ | 10/11 · 10/17 · 10/23 | Nie otwieraj okna kierowcy. | Nie otwieraj okna kierowcy. | Nie otwieraj okna kierowcy. |
| neg-03 | 3.154 / 4.154 / 5.154 | ✅ ✅ ✅ | 12/18 · 12/24 · 12/30 | Nie ustawiaj temperatury na 22 stopnie. | Nie ustawiaj temperatury na 22 stopnie. | Nie ustawiaj temperatury na 22 stopnie. |
| neg-04 | 2.028 / 3.028 / 4.028 | ✅ ✅ ✅ | 9/12 · 9/18 · 9/24 | Nie zamykaj wszystkich okien. | Nie zamykaj wszystkich okien. | Nie zamykaj wszystkich okien. |
| quo-01 | 2.047 / 3.047 / 4.047 | ✅ ❌ ❌ | 9/12 · 9/18 · 9/24 | Powiedz zamknij wszystkie okna. | Powiedz zamknili wszystkie okna. | Powiedz zamknili wszystkie okna. |
| quo-02 | 2.621 / 3.621 / 4.621 | ✅ ❌ ❌ | 15/15* · 19/21 · 19/27 | Kasia powiedziała o otwórz okno kierowcy. | Wkrośka się powiedziała o otwórz okno kierowcy. | Wkrośnia się powiedziała o otwórz okno kierowcy. |
| cmp-01 | 3.155 / 4.155 / 5.155 | ✅ ✅ ✅ | 18/18* · 19/24 · 19/30 | Otwórz okno kierowcy i zamknij okno pasażera | Otwórz okno kierowcy i zamknij okno pasażera. | Otwórz okno kierowcy i zamknij okno pasażera. |
| cmp-02 | 4.374 / 5.374 / 6.374 | ✅ ✅ ✅ | 17/26 · 17/32 · 17/38 | Zamknij wszystkie okna i ustaw temperaturę na 22 stopnie. | Zamknij wszystkie okna i ustaw temperaturę na 22 stopnie. | Zamknij wszystkie okna i ustaw temperaturę na 22 stopnie. |
| unr-01 | 2.388 / 3.388 / 4.388 | ✅ ✅ ✅ | 11/14 · 11/20 · 11/26 | Jaka jest dzisiaj pogoda w Krakowie? | Jaka jest dzisiaj pogoda w Krakowie? | Jaka jest dzisiaj pogoda w Krakowie? |
| unr-02 | 2.027 / 3.027 / 4.027 | ✅ ✅ ✅ | 10/12 · 10/18 · 10/24 | Opowiedz mi krótki dowcip. | Opowiedz mi krótki dowcip. | Opowiedz mi krótki dowcip. |

Lost negation: none in any condition (all four negative utterances keep "nie").

### Observations and inference

Observed (token lists in the logcat `DIAG` lines):

- Five baseline utterances sit at `tokens == cap` (`pos-02`, `pos-06`, `neg-01`, `quo-02`, `cmp-01`). With padding, no utterance is at the cap.
- `pos-02` and `cmp-01`: the padded token list equals the baseline list plus one final `.`. The baseline stopped one token short of the "." and lost nothing else. Same for `pos-06` at the end (the padded lists end in `.`), but its word tokens also changed.
- `neg-01`: 8 tokens in all three conditions, ending in `.`. The cap was reached without losing anything.
- `quo-01` (cap 12, 9 tokens) and `quo-02` (the changes are at the *start*, "Kasia" → "Wkrośka się") break under padding although the cap is not involved. A token cap can only drop trailing tokens, so these two regressions come from the changed input, not from the cap.

Inference (not verified): appended digital silence alters the encoder input (more audio frames, and the features are normalised over the longer clip) so near-boundary words shift; this fits `pos-06` changing differently at +1 s and +2 s ("w Berdach" vs "Berdech"). I did not inspect logits or encoder features. Because only 3 of 16 fixtures ended in a cap-clipped "." and the matcher strips punctuation, the cap changed no pass/fail result on this corpus. A clipped *word* needs a command that is long for its duration; none appeared in these 16 fixtures at baseline (D2 in step 1c showed clipping only with timestamp tokens on). Each condition ran once; greedy decoding is deterministic and the baseline reproduces step 1b and 1c exactly, so I did not repeat.

### Recommendation

1. Do not add silence padding to the capture path. It fixed no failing word, it broke `quo-01` and `quo-02`, and `pos-06` stays failed under both pad lengths.
2. Treat the cap as a documented limit, not a fix target: the matcher already ignores punctuation, and the corpus shows only a lost final ".". Re-check it with human-recorded utterances, where fast speech and long final words could clip a real word.
3. The original gate is unchanged: small 15/16 fails at `pos-06`. Next real signal is still consented human recordings, then the phone and parked-car checks. Decision for you: whether to proceed to those before step 3.

Primary sources inspected: the two upstream files above at tag `v1.13.3` (commit `330609dab49be6ee8b30702918ca7abbbad1286a`), fetched with `curl` from `raw.githubusercontent.com` and read in full around the cited lines.

## Step 1e — two human recordings through pinned small

Measured 2026-10-20 on emulator `BM_Polish_1b` (`emulator-5560`, AVD `BM_Polish_1b`, Android 14, SDK 34, `arm64-v8a`, 4,016,812 kB `MemTotal`, host Apple M1 Max). Pinned `whisper-small-int8`, unchanged baseline settings (`language=pl`, `task=transcribe`, default `tailPaddings`, no timestamps, `greedy_search`, 4 threads, no padding). No production code, fixture audio, `manifest.json` or assertion changed. No model was downloaded. Nothing here is a phone or car result.

**Answer.** Both recordings fail the matcher. Neither contains the model's two known misses in the same form, but the human voice produces two *new* wrong words. This is supplemental evidence from one speaker and two clips; it does not replace the 16-fixture corpus, and **the original gate stays failed at 15/16** (`pos-06` "Otwórz się berdech"). Nothing is promoted.

### Raw transcripts

The requested phrases were "Otwórz szyberdach." and "Opowiedz mi krótki dowcip." The mapping from file to phrase is **unknown** (I was not told it and I did not listen). The "inferred" column is my guess from the transcripts and durations only; it is not ground truth.

| source file (New Recording…) | attachment id | m4a (AAC, 48 kHz stereo) | converted 16 kHz mono | raw transcript | tokens / cap | decode |
|---|---|---|---|---|---|---|
| `New Recording 2.m4a` | `6dc411c9-2a9f-4c90-9a7c-4cdc43eac916` | 3.563 s | 3.519 s, peak 0.198, rms 0.0195 | `opowiedz mi krótki dawcip` | 10 / 21 | 1142 ms |
| `New Recording.m4a` | `35a51a90-db3e-4d90-8ecd-7bfe78ec8c83` | 2.709 s | 2.665 s, peak 0.342, rms 0.0317 | `Odfuszczy w erdach.` | 9 / 15 | 579 ms |

Token lists (logcat): recording 2 `op|ow|iedz| mi| kró|t|ki| daw|c|ip`; recording 1 ` Od|f|usz|czy| w| er|d|ach|.`. Neither run reached the token cap, so the cap did not cut anything.

Inferred match (not independent ground truth): `New Recording 2` is almost certainly the joke request, because four of five words are right and the length fits. `New Recording` is probably "Otwórz szyberdach": the first sound (`Od…`) and the ending (`…erdach`) resemble "otwórz" and "szyberdach", but this is a guess and the transcript shares no full word with the target. If the mapping were the other way round, both clips would be even worse matches, so I treat the inference as likely, not certain.

### Comparison with the requested pair, under the unchanged matcher rules

Rules from `manifest.json` (`pos-06` needs `otworz` + `szyberdach`; `unr-02` needs `opowiedz` + `dowcip`; diacritics folded, punctuation stripped). I applied them by hand to the inferred pairing. The harness did not score these clips.

| inferred target | transcript | required words found | would pass |
|---|---|---|---|
| "Opowiedz mi krótki dowcip." | `opowiedz mi krótki dawcip` | `opowiedz` yes; `dowcip` no (`dawcip`) | no |
| "Otwórz szyberdach." | `Odfuszczy w erdach.` | `otworz` no; `szyberdach` no | no |

Relation to the two known errors: medium missed `dowcip` as `doscip`; the human clip misses it as `dawcip`, which is a different wrong word with the same failure shape. Small's `szyberdach` miss (`berdech`) is also visible in the human clip as `erdach`, so the weakness on that word is not specific to the synthetic fixtures. The human result is worse than the fixture result for the roof command (no correct word at all).

### Exact commands and output

```sh
# Source files stay in the T3 attachments directory; nothing was copied into the repo.
A=/Users/tomaszkopycki/.t3/userdata/attachments/85df0b37-c850-4e1d-be69-e1cfe03cb0a1
ffmpeg -nostdin -y -i "$A-6dc411c9-2a9f-4c90-9a7c-4cdc43eac916-m4a.m4a" -ac 1 -ar 16000 -c:a pcm_s16le /tmp/diag1e/audio/new-recording-2.wav
ffmpeg -nostdin -y -i "$A-35a51a90-db3e-4d90-8ecd-7bfe78ec8c83-m4a.m4a" -ac 1 -ar 16000 -c:a pcm_s16le /tmp/diag1e/audio/new-recording.wav
# ffmpeg only downmixes to mono and resamples to 16 kHz PCM16; no trim, denoise, silence or gain filter. Output: 110 KiB (3.51 s) and 83 KiB (2.66 s).
# Harness: APFS clone of /tmp/diag1d/copy with one added test, whisperTranscribesHumanRecordings, in /tmp/diag1e/copy
cp -cR /tmp/diag1d/copy /tmp/diag1e/copy
cd /tmp/diag1e/copy && JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew --offline :app:assembleDebug :app:assembleDebugAndroidTest -q   # no output; APKs rebuilt 21:11
emulator -avd BM_Polish_1b -port 5560 -no-window -no-audio -no-snapshot
export ANDROID_SERIAL=emulator-5560
adb shell "mkdir -p /data/local/tmp/bydmate-human"
adb push /tmp/diag1e/audio/new-recording.wav /tmp/diag1e/audio/new-recording-2.wav /data/local/tmp/bydmate-human/   # 2 files pushed
adb install -r -t app/build/outputs/apk/debug/BYDMate-v4.0.1.apk                                   # Success
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk                # Success
adb shell "am instrument -w -e class com.bydmate.app.voice.PolishNativeSmokeTest#whisperTranscribesHumanRecordings com.bydmate.app.test/androidx.test.runner.AndroidJUnitRunner"
# → com.bydmate.app.voice.PolishNativeSmokeTest:.   Time: 4.679   OK (1 test)
adb logcat -d -s PolishSmoke | grep HUMAN
```

The test uses the pinned, hash-verified small package (`requireProvisioned`), so the manifest check ran. The added test asserts nothing about content; it only logs. Models were the existing staged ones under `/data/local/tmp/bydmate-pl-models/`. Raw outputs stay outside the repo: `/tmp/diag1e/human.instrument.txt`, `/tmp/diag1e/human.logcat.txt`, converted WAVs in `/tmp/diag1e/audio/`. The sources' SHA-256: `6dc411c9…` file `9502e1e719bba7ac870c9791b84beed16375c23f59d4c6af9ec89ccd9ce7fad9`; `35a51a90…` file `79d322c3ed92b9cde9d6a05616b10c5ed2add8558fd7f976db885322d6d6b53e`. `emulator-5554` (another task's Pixel 2 API 34) was attached; every command pinned `ANDROID_SERIAL=emulator-5560`. `device_open` for `BM_Polish_1b` failed again ("could not start"), so I booted the AVD with the `emulator` command above, as in steps 1c and 1d. The run used `adb` and `am instrument`, not UI driving.

### Missing checks

- Ground truth: nobody confirmed which clip says which phrase, and I did not listen to them.
- One speaker, one take each, two phrases. No variance, no noise levels, no car cabin audio.
- Single decode each. Greedy decoding is deterministic on the same bytes and build, so I did not repeat.
- Not run: medium, tiny or base on these clips; the other 14 requested commands; the app's real capture path (microphone, AGC, 16 kHz resampler). The m4a files came from a phone recorder at 48 kHz stereo, then ffmpeg resampled them; the app's own resampler may differ.
- Phone and parked-car checks remain pending (no phone attached).

### Recommendation

1. Keep small as an unpromoted candidate and keep the gate failed. The human clips reproduce the two weak words (`szyberdach`, `dowcip`) and add no evidence that any setting fixes them.
2. Fix this at the grammar layer instead of the model: add accepted variants for the weak words (`szyberdach`/`berdech`/`erdach`-type forms), or fuzzy match the command nouns. Decision for you: that needs a consented, labelled corpus of human clips first, because tuning on two clips would overfit.
3. Next: record the full 16-phrase list with a known file-to-phrase mapping (ideally one file per phrase named by id), and run it through small on the emulator.

## Step 1f — the same two human recordings through medium

Measured 2026-10-20 on emulator `BM_Polish_1b` (`emulator-5560`, Android 14, SDK 34, `arm64-v8a`, 4,016,812 kB `MemTotal`, host Apple M1 Max). Whisper medium int8, the unpinned step 1b comparison files already staged under `/data/local/tmp/bydmate-pl-models/candidates/whisper-medium-int8/`. Their size and SHA-256 on the device match the step 1b pins for all three files. Baseline settings, as in step 1e: `language=pl`, `task=transcribe`, default `tailPaddings`, no timestamps, `greedy_search`, 4 threads, no padding. One run, no retry. No model was downloaded and no production code, decoder setting, audio, transcript or assertion changed. The diagnostic harness is a clone of the 1e harness in `/tmp/diag1f/copy` with one added test (`whisperMediumTranscribesHumanRecordings`) that only logs. `emulator-5554` (another task's device) was running; I did not use it. Nothing here is a phone or car result.

**Answer.** Medium transcribes the joke clip correctly and gets half of the roof clip. This is supplemental evidence (one speaker, two clips). **The original gate stays failed at 15/16** for small and for medium; supplemental results cannot pass it.

### Raw transcripts

The file-to-phrase mapping is still **inferred**, not confirmed by listening (as in step 1e). A log-only successful test execution (`OK (1 test)`) says the decode ran; it says nothing about accuracy. Accuracy is the transcript column.

| source file (New Recording…) | inferred phrase | small int8 (step 1e, pinned) | medium int8 (step 1f, unpinned) | medium tokens / cap | medium decode |
|---|---|---|---|---|---|
| `New Recording 2.m4a` (3.519 s) | "Opowiedz mi krótki dowcip." | `opowiedz mi krótki dawcip` | `Opowiedz mi krótki dowcip` | 9 / 21 | 4002 ms |
| `New Recording.m4a` (2.665 s) | "Otwórz szyberdach." | `Odfuszczy w erdach.` | `Otwórz się w erdach.` | 10 / 15 | 4059 ms |

Medium tokens: recording 2 ` Op|ow|iedz| mi| kró|t|ki| dow|cip`; recording 1 ` Ot|w|ór|z| się| w| er|d|ach|.`. Neither run reached the token cap.

### Matcher rules applied by hand (the harness did not score these clips)

Rules from `manifest.json`: `pos-06` needs `otworz` + `szyberdach`; `unr-02` needs `opowiedz` + `dowcip`; diacritics folded, punctuation stripped. No fuzzy matching and no accepted variants were added.

| inferred target | medium transcript | required words found | would pass |
|---|---|---|---|
| "Opowiedz mi krótki dowcip." | `Opowiedz mi krótki dowcip` | `opowiedz` yes; `dowcip` yes | yes |
| "Otwórz szyberdach." | `Otwórz się w erdach.` | `otworz` yes; `szyberdach` no (`w erdach`) | no |

Compared with small on the same clips: medium fixes `dowcip` and `otwórz`, but `szyberdach` is still split and wrong (`w erdach`), the same weak word that small missed on the fixture (`berdech`) and on this clip (`erdach`). On the 16 fixtures medium missed `dowcip` instead; on the human clips it gets `dowcip` right. The two models therefore fail on different words in different conditions, and neither is reliable on both. With two clips, I draw no conclusion about which is better.

### Cost on this emulator

Load 5224 ms. Process `VmRSS` before load 283,856 kB, after load 1,315,400 kB; `VmHWM` (peak) 2,269,076 kB (2.16 GiB) after the second clip. Decode 4.0 s for 3.5 s of audio and 4.1 s for 2.7 s of audio, roughly 1.1–1.5× real time. This run was not memory-starved like the step 1b medium run (4–51 s per utterance); I did not investigate why, and `emulator-5554` was still running, so timings are noisy. No `lowmemorykiller` kill appears in the logcat (only lmkd property reload lines at 21:16:23). The memory peak is still more than twice small's 1.12 GB, so medium is unlikely to suit a head unit without a measurement on that hardware.

### Exact commands and output

```sh
cp -cR /tmp/diag1e/copy /tmp/diag1f/copy   # then added one test to PolishNativeSmokeTest.kt in the copy only
cd /tmp/diag1f/copy && JAVA_HOME=$(/usr/libexec/java_home -v 17) ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew --offline :app:assembleDebug :app:assembleDebugAndroidTest -q   # no output
export ANDROID_SERIAL=emulator-5560
adb shell "cd /data/local/tmp/bydmate-pl-models/candidates/whisper-medium-int8 && sha256sum medium-encoder.int8.onnx medium-decoder.int8.onnx medium-tokens.txt"
# 1c54582b…193e  medium-encoder.int8.onnx   595d00a3…2747  medium-decoder.int8.onnx   b34b360d…fa126  medium-tokens.txt  (all match step 1b)
adb install -r -t app/build/outputs/apk/debug/BYDMate-v4.0.1.apk                      # Success
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk   # Success
adb logcat -c
adb shell "am instrument -w -e class com.bydmate.app.voice.PolishNativeSmokeTest#whisperMediumTranscribesHumanRecordings com.bydmate.app.test/androidx.test.runner.AndroidJUnitRunner"
# → com.bydmate.app.voice.PolishNativeSmokeTest:.   Time: 13.513   OK (1 test)
adb logcat -d -s PolishSmoke
```

The first Gradle attempt failed with `SDK location not found` because `ANDROID_HOME` was unset in that shell. I set it and rebuilt; nothing else changed. A first build also logged the memory object instead of kilobytes, so I fixed the log line and rebuilt before running. Raw outputs stay outside the repo: `/tmp/diag1f/medium.instrument.txt`, `/tmp/diag1f/medium.logcat.txt`. Audio stays in `/tmp/diag1e/audio/`.

### Missing checks

- Mapping of files to phrases is unconfirmed (nobody listened).
- One speaker, two clips, one decode each. No variance, noise or car-cabin audio; the app's real capture path and resampler are untested.
- Medium is unpinned and unverified by the harness manifest check (hashes were checked by hand). Its licence status is unresolved (see the Notices paragraph above).
- The other 14 requested commands were not run through medium on human audio.
- No phone or parked-car result; memory and latency on head-unit hardware are unmeasured.

### Recommendation

1. Keep the gate failed and promote nothing. Medium is no better than small on the evidence: it fixed `dowcip` and half of the roof clip but still misses `szyberdach`, and it costs 2.2 GB peak memory and 946 MB of model files.
2. Do not pick a model from two clips. Record the full 16-phrase list with one file per phrase id, then run small and medium on it. That gives a mapping and a corpus.
3. `szyberdach` is the recurring failure in both models; decide whether the command set should use a different, easier word for the sunroof, or accept grammar-layer variants after a labelled human corpus exists. Decision for you.

## Step 1g — Parakeet v3, Whisper turbo and Vosk small-pl on the same 18 clips

Measured 2026-10-20 on emulator `BM_Polish_1b` (`emulator-5560`, AVD `BM_Polish_1b`, Android 14, SDK 34, `arm64-v8a`, 4 cores, 4,016,812 kB `MemTotal`, host Apple M1 Max). Sherpa 1.13.3 from the unchanged production AAR. No production code, runtime library, fixture, `manifest.json` (SHA-256 `f64c62f7…ccb2e`, unchanged) or matcher rule changed in the repo; the only repo file edited is this document. Nothing here is a phone or car result, and nothing is promoted.

**Answer.**

- **The original gate stays failed.** Parakeet TDT 0.6B v3 int8 passed **15/16** and failed `neg-03`. Vosk small-pl-0.22 passed **10/16**. Whisper large-v3-turbo int8 was **not run**: staging it would breach the 3 GiB emulator free-space floor (details below). Small and medium also scored 15/16, so no candidate has passed 16/16.
- **Parakeet is the best result so far, but not a pass.** It fixes both words that split small and medium on the fixtures (`szyberdach`, `dowcip`), keeps "nie" in all four negative utterances, decodes 69–299 ms per utterance (small: 0.5–1.1 s uncontended in step 1, up to 8.3 s under host contention in step 1b), and peaks at 1.01 GB (small: 1.09 GB). It fails on one inflection ending (`temperatur` for `temperatury`) and misses the roof word on the human clip (`szywerdach`). It cannot be forced to Polish: the NeMo transducer path in Sherpa has no language setting, the model detects the language itself.
- **Vosk small-pl does not fit.** It is fast and small (491 MB peak, 542 ms load) but fails 6 of 16, mostly by substituting real Polish words (`odtwórz szybę`, `odpowiedz` for `opowiedz`, `kierowcę`). It also needs a new runtime dependency (`vosk-android`), which the app removed earlier.
- A perfect or near-perfect supplemental run does not pass the pinned production gate. The harness execution succeeding is a different fact from accuracy: both runs executed fully (`am instrument` ran all 18 clips, no crash, no skip); the accuracy result is the transcripts and the 15/16 and 10/16 counts below.

### Candidates and what I could verify

| candidate | language support | export / runtime fit | licence (primary source) | outcome |
|---|---|---|---|---|
| Parakeet TDT 0.6B v3 int8 | Polish listed among 25 languages in the NVIDIA model card; language auto-detected, not selectable | Sherpa `nemo_transducer` exists in the 1.13.3 AAR (`OfflineTransducerModelConfig`, `modelType="nemo_transducer"`). Encoder metadata read from the file: `feat_dim=128`, `vocab_size=8192`, `subsampling_factor=8`, `normalize_type=per_feature`, `model_type=EncDecRNNTBPEModel`. Sherpa reads `feat_dim` from the model (v1.13.3 `offline-recognizer-transducer-nemo-impl.h` line 229–232), so the 80-mel Whisper config is not reused. | CC-BY-4.0 (model card "GOVERNING TERMS"). The Sherpa repack repo has no licence metadata. | **ran, no library change** |
| Whisper large-v3-turbo int8 | multilingual; `pl` is in the file's `all_language_codes`, so the Whisper path can force `language=pl` (not run) | Encoder metadata read from the file: `n_mels=128`, `n_audio_layer=32`, `n_text_layer=4`, `n_vocab=51866`, `sot_sequence=50258,50259,50360`, `is_multilingual=1`. Sherpa 1.13.3 takes the feature size from `n_mels` (`offline-whisper-model.cc` line 318, 356), so 128 mels needs no config change. Not tested at runtime. | Model card `license: mit`; `openai/whisper` `LICENSE` is MIT. Handy's page lists Apache-2.0 for the same family. Conflict unresolved (as for small in step 1b). The Sherpa repack repo has no licence metadata. | **downloaded and verified on the host; not run** (floor) |
| vosk-model-small-pl-0.22 | Polish only | Kaldi model; needs `com.alphacephei:vosk-android:0.3.75` (Maven Central, AAR 13,472,638 bytes, Apache-2.0 per its POM) and `net.java.dev.jna:jna:5.13.0@aar`. The app has no Vosk API today (`TrackingService.kt` line 933 only deletes an orphaned `vosk` directory). I added both only to the disposable harness copy; production dependencies unchanged. | Apache-2.0 per the Vosk models page; the zip carries no licence file. | **ran in the disposable harness**; adopting it would be a runtime-library change |

### Community and published numbers (anecdotes separated from measurements)

Measured by the model owners or third parties, **each on a different corpus and engine, so not equivalent to each other or to our 16 fixtures**:

- NVIDIA model card (greedy, no external LM): Parakeet v3 Polish FLEURS **7.31%**, MLS **7.28%**; 25-language FLEURS average 11.97%. Source: the card's results table.
- Handy's transcribe.cpp benchmark, FLEURS test, Q8_0 GGUF, [Polish page](https://models.handy.computer/languages/pl/): whisper-large-v3 4.69, whisper-large-v3-turbo 5.81, parakeet-tdt-0.6b-v3 7.37, whisper-medium 8.59, whisper-small 16.82, base 35.68, tiny 54.22. Different quantisation and engine from our int8 ONNX exports through Sherpa.
- Vosk README inside the model zip (Alpha Cephei's own test sets): 18.36% (Common Voice), 16.88% (MLS), 11.55% (Voxpopuli).

Anecdotes (single opinions, not measurements):

- GitHub [alphacep/vosk-api#1644](https://github.com/alphacep/vosk-api/issues/1644), 2024-10-16, one user: "in case of Polish language your model vosk-model-small-pl-0.22 is simply fantastic, in my opinion much better than whisper tiny/small and works much faster". Our measurement disagrees for small on these fixtures (Vosk 10/16, small 15/16); it did confirm "faster than small" (about 0.54 s mean vs small's 0.5–8 s).
- The Reddit thread `r/LocalLLaMA/comments/1nf10ye/30_days_testing_parakeet_v3_vs_whisper/` could not be read: Reddit answered my script with HTTP 403, and a web search did not surface its text. I draw nothing from it.
- Web searches found vendor blogs and a search snippet of another Reddit thread, none with Polish-specific Parakeet results. I did not open those pages and I rely on none of them. I found no independent Polish report for Parakeet v3 on Android.

### Downloaded assets

Host storage was checked before every download (guard script `/tmp/diag1g/scripts/download.sh` refuses to start if the free space after the file would be under 3 GiB). Files were downloaded sequentially, each into its own new directory under `/tmp/diag1g/dl/`, outside the repo, with no duplicate archive. Each Hugging Face URL is a `resolve/<commit>` URL that redirects to a signed `cdn.hf.co` link; the commit is the pin.

| asset | HTTPS URL | bytes | SHA-256 (computed locally) | cross-check |
|---|---|---|---|---|
| `decoder.int8.onnx` | `https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/2bda32ec70b097a55adaa07d9a7173915b43cc78/decoder.int8.onnx` | 11845275 | 179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e | equals HF LFS oid |
| `joiner.int8.onnx` | same repo and commit | 6355277 | 3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3 | equals HF LFS oid |
| `encoder.int8.onnx` | same repo and commit | 652184281 | acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247 | equals HF LFS oid |
| `tokens.txt` | same repo and commit | 93939 | d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d | not an LFS file (8193 lines) |
| `turbo-decoder.int8.onnx` | `https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo/resolve/2ca6ff69fc878651b770880507669577ac41c2ff/turbo-decoder.int8.onnx` | 361080764 | 20accd02388482eb3a46bd615631adfdc85e1eb2c7db9ea3f02a40ffe6b81547 | equals HF LFS oid |
| `turbo-encoder.int8.onnx` | same repo and commit | 674716297 | b02dcdf54f348741e93fe732b67d933c8dcb6735655f710640143081db38878b | equals HF LFS oid |
| `turbo-tokens.txt` | same repo and commit | 816730 | b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126 | byte-identical to the tiny/small tokens file |
| `vosk-model-small-pl-0.22.zip` | `https://alphacephei.com/vosk/models/vosk-model-small-pl-0.22.zip` | 52979372 (matches `Content-Length`) | c4cd16498ea544f446f9e9a55cbd602b71cfe5a2b6f2b0834d81e1b6fce15f0d | no published hash; `Last-Modified` 2022-06-05 |

- Totals: Parakeet 670,478,772 bytes of model files (no archive; the official release tarball `sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2` is 487,170,055 bytes with GitHub digest `sha256:5793d0fd…16bf`, **not downloaded**, so I did not verify that it equals the Hugging Face files); turbo 1,036,613,791 bytes (the release tarball is 563,790,207 bytes, not downloaded); Vosk 52,979,372 bytes compressed, 95,652,517 bytes expanded (14 files; per-file hashes in `/tmp/diag1g/meta/vosk-files.sha256`).
- Source revisions: repack repos above; Parakeet source model `nvidia/parakeet-tdt-0.6b-v3` commit `541d1f99c6b0c3cd0b11a95167540bb8edefd82b` (card read from `main` at the time of the run); `openai/whisper-large-v3-turbo` commit `41f01f3fe87f28c78e2fbf8b568835947dd65ed9`. The Sherpa export docs list the `csukuangfj/sherpa-onnx-whisper-turbo` repo as the turbo export ([Export Whisper to ONNX](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/whisper/export-onnx.html)); the NeMo page names the conversion script directory `scripts/nemo/parakeet-tdt-0.6b-v3` ([NeMo transducer models](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html)). I did not read that script.
- Licence and notice text read: NVIDIA card (CC-BY-4.0, attribution required; the int8 conversion would be adapted material, so a change notice and attribution would be needed), OpenAI turbo card (MIT) and `LICENSE` (MIT, "Copyright (c) 2022 OpenAI"), Vosk models page and `vosk-android` POM (Apache-2.0). Not read: the JNA licence, the Sherpa repack repos' terms (they publish none), the Granary dataset terms. Unresolved: whether we may mirror the repacked ONNX files.

### Device runs

The emulator data partition had 3,951,796 kB free (3.77 GiB) before the first run. Each candidate was pushed to `/data/local/tmp/bydmate-1g/<id>`, hash-listed on the device (Parakeet: all four hashes equal the host values; Vosk: only top-level files, the README hash matched), run in a fresh `am instrument` process (so `VmHWM` is per candidate), then deleted. The test reads models in place (no copy into app storage), so only one copy sits on the device. I deleted only my own staged files; nothing from earlier steps was touched.

- **Whisper turbo: BLOCKED, not a pass and not a failure.** Staging it needs 1,012,324 kB and would leave 2,939,492 kB (2.80 GiB), under the 3 GiB (3,145,728 kB) floor. The guard script refused: `BLOCKED: staging whisper-turbo-int8 would leave 2939492 KB < 3 GiB floor`. The only way to run it without breaking the floor is to free about 200 MB on the emulator, for example by removing the step 1b medium staging (902 MB under `candidates/`), which I must not delete. A second reason to expect trouble: medium crashed with a low-memory kill on this 4 GB emulator at a full 30 s window (step 1c, D3), and turbo's encoder is larger (675 MB int8 vs 374 MB). That is an expectation, not a measurement.
- **Concurrent emulator and host load.** `emulator-5554` (another task's) was attached and booted throughout, and the host 1-minute load average read 6.6 (13.6 five minutes earlier), so timings are noisy lower bounds. Thread count: 4 for Sherpa candidates (the same as small/medium); Vosk has no thread setting.

| candidate | fixtures passed | load | RSS before → after load (MB) | peak RSS `VmHWM` (MB) | decode per utterance |
|---|---|---|---|---|---|
| small int8 (step 1b baseline) | 15/16 | 4185 ms | 324 → 697 | 1092 | 1.2–8.3 s (contention) |
| medium int8 (step 1b/1f baseline) | 15/16 | 36397 ms (5224 ms in 1f) | 255 → 1260 | 2412 (2269 in 1f) | 4.5–51 s (4.0–4.1 s in 1f) |
| **Parakeet v3 int8**, run 1 | **15/16** | 3039 ms | 259 → 948 | 1006 | 99–299 ms (fixtures), 123–161 ms (human) |
| **Parakeet v3 int8**, run 2 | 15/16 (same transcripts) | 2021 ms | 259 → 948 | 1007 | 69–242 ms (fixtures), 128–159 ms (human) |
| Vosk small-pl-0.22 | 10/16 | 542 ms | 259 → 425 | 480 | 416–699 ms (fixtures), 369–395 ms (human) |

Peak RSS is the process `VmHWM` in kB divided by 1024: Parakeet run 1 1,030,580 kB, run 2 1,031,032 kB, Vosk 491,040 kB. The "before" figure (about 259 MB) is the bare instrumentation process, so Parakeet's own weight is about 0.7–0.8 GB. Parakeet's load time dropped from 3.0 s to 2.0 s on the second run (file cache). Peak memory and timing were measured once per run; there was no `lowmemorykiller`, crash or fatal line in either logcat.

### All 18 clips, raw transcripts

✅ = passes the unchanged matcher (lowercase, punctuation removed, diacritics folded, required words, "nie" for negatives). Small and medium columns are the step 1b results. The two human clips are not scored by the harness (the file-to-phrase mapping is still inferred, not confirmed by listening: I could not listen); the verdict there is my hand application of the `pos-06` / `unr-02` rules to the inferred pairing, as in steps 1e and 1f.

| id | reference | small int8 | medium int8 | Vosk small-pl | Parakeet v3 int8 |
|---|---|---|---|---|---|
| cmp-01 | otwórz okno kierowcy i zamknij okno pasażera | ✅ Otwórz okno kierowcy i zamknij okno pasażera. | ✅ same | ✅ otwórz okno kierowcy i zamknij okno pasażera | ✅ Otwórz okno kierowcy i zamknij okno pasażera. |
| cmp-02 | zamknij wszystkie okna i ustaw temperaturę na dwadzieścia dwa stopnie | ✅ …na 22 stopnie. | ✅ same | ✅ …na dwadzieścia dwa stopnie | ✅ Zamknij wszystkie okna i ustaw temperaturę na 22 stopnie. |
| neg-01 | nie zamykaj okien | ✅ Nie zamykaj okien. | ✅ same | ✅ nie zamykaj okien | ✅ Nie zamykaj okien. |
| neg-02 | nie otwieraj okna kierowcy | ✅ Nie otwieraj okna kierowcy. | ✅ same | ✅ nie otwieraj okna kierowcę | ✅ Nie otwieraj okna kierowcy. |
| neg-03 | nie ustawiaj temperatury na dwadzieścia dwa stopnie | ✅ Nie ustawiaj temperatury na 22 stopnie. | ✅ same | ✅ nie ustawiaj temperatury na dwadzieścia dwa stopnie | ❌ Nie ustawiaj temperatur na 22 stopnie. (missing `temperatury\|temperatura`) |
| neg-04 | nie zamykaj wszystkich okien | ✅ | ✅ | ✅ nie zamykaj wszystkich okien | ✅ Nie zamykaj wszystkich okien. |
| pos-01 | zamknij wszystkie okna | ✅ | ✅ | ✅ zamknij wszystkie okna | ✅ Zamknij wszystkie okna. |
| pos-02 | otwórz okno kierowcy | ✅ Otwórz okno kierowcy | ✅ same | ❌ otwórz okno kierowcę (missing `kierowcy`) | ✅ Otwórz okno kierowcy. |
| pos-03 | ustaw temperaturę na dwadzieścia dwa stopnie | ✅ …na 22 stopnie. | ✅ same | ✅ …na dwadzieścia dwa stopnie | ✅ Ustaw temperaturę na 22 stopnie. |
| pos-04 | jaki jest poziom baterii | ✅ | ✅ | ✅ jaki jest poziom baterii | ✅ Jaki jest poziom baterii? |
| pos-05 | ustaw głośność na pięćdziesiąt procent | ✅ …na 50%. | ✅ same | ✅ …na pięćdziesiąt procent | ✅ Ustaw głośność na 50%. |
| pos-06 | otwórz szyberdach | ❌ Otwórz się berdech | ✅ Otwórz szyberdach | ❌ odtwórz szybę (missing `otworz`, `szyberdach`) | ✅ Otwórz szyberdach. |
| quo-01 | powiedz zamknij wszystkie okna | ✅ Powiedz zamknij wszystkie okna. | ✅ Powiedz "Zamknij wszystkie okna". | ❌ odpowiedz zamknij wszystkie okna (missing `powiedz`) | ✅ Powiedz zamknij wszystkie okna. |
| quo-02 | Kasia powiedziała otwórz okno kierowcy | ✅ Kasia powiedziała o otwórz okno kierowcy. | ✅ …"Otwórz okno kierowcy" | ❌ jak się powiedziały otwórz okno kierowcę (missing `kasia`) | ✅ Kasia powiedziała, otwórz okno kierowcy. |
| unr-01 | jaka jest dzisiaj pogoda w Krakowie | ✅ | ✅ | ❌ jaka jest dzisiaj pogodę w krakowie (missing `pogoda`) | ✅ Jaka jest dzisia pogoda w Krakowie? |
| unr-02 | opowiedz mi krótki dowcip | ✅ Opowiedz mi krótki dowcip. | ❌ Opowiedz mi krótki doscip. | ❌ odpowiedz mi krótki dowcip (missing `opowiedz`) | ✅ Opowiedz mi krótki dowcip. |
| human 2 (`New Recording 2`, inferred "Opowiedz mi krótki dowcip.") | | `opowiedz mi krótki dawcip` → fail | `Opowiedz mi krótki dowcip` → pass | `opowiedz mi krótki dowcipy` → fail (`dowcipy` ≠ `dowcip`) | `Opowiedz mi, krótki dowcip.` → pass |
| human 1 (`New Recording`, inferred "Otwórz szyberdach.") | | `Odfuszczy w erdach.` → fail | `Otwórz się w erdach.` → fail | `odchodź żeber dach` → fail | `Otwórz szywerdach.` → fail (`szywerdach`, one letter off) |

Totals on the 16 fixtures: small 15, medium 15, Vosk 10, Parakeet 15. Human clips by inferred mapping: small 0/2, medium 1/2, Vosk 0/2, Parakeet 1/2.

Observations (from the transcripts above):

- **Lost negation: none.** All four negative utterances keep "nie" for both new candidates. Parakeet's single failure is on a negative utterance but it is the noun ending, not the negation. Vosk's four negative utterances all pass.
- **Parakeet's miss is an inflection ending.** `temperatur` for `temperatury` (tokens `…| tem|per|atur| na…`: the final `y` was not emitted). One more clipped ending passed only because the matcher does not require that word: `dzisia` for `dzisiaj` (`unr-01`). So 15/16 slightly overstates how clean the output is. I saw no truncated tail of the kind step 1c found for Whisper.
- **Vosk fails by dictionary substitution**: it only emits real Polish words, so a mispronounced or unusual word becomes another word (`odtwórz szybę`, `odpowiedz`). It also drops case on every word and writes numbers out, which the matcher accepts.
- **Parakeet punctuation and digits**: it emits `22`, `50%` and sentence punctuation, which the matcher already accepts. A comma appeared inside `quo-02` and the human joke clip; punctuation is stripped.
- The `neg-02` Vosk pass with `kierowcę` while `pos-02` fails on the same word is a property of the unchanged fixture rules (`neg-02` does not require `kierowcy`); I did not touch the rules.
- Two runs of Parakeet produced identical transcripts for all 18 clips (diffed after removing timings). Vosk ran once; its decoder is deterministic for the same input, but I did not repeat it.

### Exact commands

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME=/Users/tomaszkopycki/Library/Android/sdk
# Harness: APFS clone of the step 1f copy, outside the repo
cp -cR /tmp/diag1f/copy /tmp/diag1g/copy
# in the copy only: add androidTest/.../voice/CandidateBenchTest.kt (new), and to app/build.gradle.kts:
#   implementation("com.alphacephei:vosk-android:0.3.75"); implementation("net.java.dev.jna:jna:5.13.0@aar")
# Diff vs repo: app/src/main, app/src/test, app/src/androidTest/assets identical (diff -rq empty); manifest.json hash identical.
```

Build. `~/.gradle/caches` did not exist when this step started (the step 1f copy built offline earlier; `./gradlew --offline` now failed with `Could not read workspace metadata from /Users/tomaszkopycki/.gradle/caches/8.9/kotlin-dsl/accessors/…/metadata.bin (No such file or directory)`), so I built with a fresh, private Gradle home and left `~/.gradle` alone. The dependency download made the build slow:

```sh
cd /tmp/diag1g/copy
GRADLE_USER_HOME=/tmp/diag1g/gradle-home ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -q
# no output with -q; real 6m26.9s; APKs written 21:56 (app 121,200,498 bytes, test 2,455,087 bytes)
GRADLE_USER_HOME=/tmp/diag1g/gradle-home ./gradlew --stop      # "1 Daemon stopped" (my private daemon only)
export ANDROID_SERIAL=emulator-5560
adb install -r -t app/build/outputs/apk/debug/BYDMate-v4.0.1.apk                      # Success
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk   # Success
```

Run (script `/tmp/diag1g/scripts/run_candidate.sh <id> <test method> <host dir>`: checks free space, pushes, lists hashes, runs, captures logcat, deletes the staged model):

```sh
run_candidate.sh vosk-model-small-pl-0.22 voskSmallPl   .../vosk-small-pl-0.22-x/vosk-model-small-pl-0.22
run_candidate.sh parakeet-v3-int8         parakeetV3    /tmp/diag1g/dl/parakeet-v3-int8      # twice
run_candidate.sh whisper-turbo-int8       whisperTurbo  /tmp/diag1g/dl/whisper-turbo-int8     # BLOCKED by the floor, nothing pushed
# inside: adb shell "am instrument -w -e class com.bydmate.app.voice.CandidateBenchTest#<method> -e run <id> \
#   com.bydmate.app.test/androidx.test.runner.AndroidJUnitRunner"
```

Actual instrumentation results (`FAILURES!!!` here means at least one fixture failed the unchanged matcher, which is the accuracy result, not a harness error):

- Vosk: `Time: 10.224 … Tests run: 1, Failures: 1`, `6/16 utterances failed` (`pos-02`, `pos-06`, `quo-01`, `quo-02`, `unr-01`, `unr-02`).
- Parakeet, both runs: `Tests run: 1, Failures: 1`, `1/16 utterances failed: neg-03 [negative]: 'Nie ustawiaj temperatur na 22 stopnie.' (missing=[temperatury|temperatura] forbidden=[] lostNegation=false)`; run 1 `Time: 6.271`.
- Skips: 0 in the runs that executed. Turbo: not executed.

Device setup: `device_list` showed `emulator-5560` (`BM_Polish_1b`) booted; `device_open` for it succeeded on the first call (no boot needed this time). The runs used `adb` and `am instrument`, not UI driving, and `emulator-5554` was not touched. Emulator free space after cleanup: 3,951,764 kB (before: 3,951,796 kB). The host had between 2 and 95 GiB free at different moments (other processes changed it; `df -h` reported 2.0 GiB for a while, then 22–95 GiB), so the 3 GiB host floor was checked by the guard script before each download and held. Raw outputs and logs stay outside the repo: `/tmp/diag1g/out/` (`*.instrument.txt`, `*.logcat.txt`, `*.device-sha256.txt`, `*.crashgrep.txt`, `parakeet-v3-int8.run1.*`), harness at `/tmp/diag1g/copy`, assets at `/tmp/diag1g/dl/` (about 1.7 GB), metadata and source excerpts at `/tmp/diag1g/meta/` and `/tmp/diag1g/src/`. Nothing in `/tmp` was deleted.

### Limits of this evidence

- 16 synthetic utterances from one TTS voice plus two human clips from one speaker with an unconfirmed file-to-phrase mapping. This is a preliminary comparison, not a car acceptance corpus. The published WERs above come from different corpora and do not rank these models for our command set.
- Emulator on an M1 Max with a concurrent emulator and a loaded host. Decode times are lower bounds; the head unit is unmeasured. Parakeet's 0.75 GB model weight plus runtime on a car head unit is unmeasured.
- Parakeet language is auto-detected. A short or noisy command could be decoded as another language; I saw no such case on 18 clips, but I did not test it.
- Turbo was not run, so nothing is known about its Polish accuracy on these clips, only its published FLEURS number from a different engine.
- Parakeet and turbo weights come from a third-party Sherpa repack with no licence metadata. CC-BY-4.0 (Parakeet) and MIT (turbo card) apply upstream; whether the repack adds terms is unknown.
- No noise, no capture-path resampling, no VAD segmentation, no repeated-session memory trend.

### Recommendation (next model decision, nothing promoted)

1. Keep the 16-utterance gate failed and Steps 3–5 stopped. No candidate is 16/16.
2. Treat **Parakeet TDT 0.6B v3 int8 as the leading candidate to carry into the consented human corpus**, in place of small. It matches the best fixture score (15/16), fixes both known weak words on the fixtures, decodes several times faster than small on this emulator (69–299 ms vs 0.5–1.1 s uncontended) with similar peak memory, and showed no truncated tails (the Whisper token cap does not apply to a transducer; I did not read the transducer decoder code). Its two real weaknesses are visible: a dropped inflection ending and `szywerdach` on the human roof clip.
3. Do not pick Vosk small-pl: 10/16 and a new runtime dependency.
4. Decision for you on turbo: free about 200 MB on `BM_Polish_1b` (for example, remove the step 1b medium staging, which can be re-staged from its pinned hashes) or enlarge the AVD data partition, then run `whisperTurbo` once. I expect a memory problem on 4 GB, so give it a lower priority than the human corpus.
5. Decision for you on the gate itself: the only Parakeet miss is a one-letter noun ending, which a stem or inflection-tolerant matcher would accept. Loosening the matcher is your call and must not be done by an implementer; I changed nothing.
6. Next evidence regardless of model: record the 16 phrases with one file per phrase id and a known mapping, then run Parakeet v3 (and small as the baseline) on it; then check language auto-detection behaviour on short commands and the parked-car acceptance.

## Step 1h — Whisper large-v3-turbo int8 on the same 18 clips

Measured 2026-10-20 (22:02–22:04 CEST) on emulator `BM_Polish_1b` (`emulator-5560`; `device_list` then `device_open` first; Google `sdk_gphone64_arm64`, Android 14, SDK 34, `arm64-v8a`, 4 cores, 4,016,812 kB `MemTotal`, host Apple M1 Max). Only this document changed in the repo. No download, runtime library, production code, fixture, `manifest.json`, matcher rule or model pin changed; no commit. `emulator-5554` was not touched. Nothing here is a phone or car result, and nothing is promoted.

**Answer (measured).**

- **Turbo passed 16/16 fixtures** under the unchanged matcher, with no lost negation, no forbidden token and no truncated word. The harness assertion held (`OK (1 test)`) and the on-device report JSON lists `pass=true` for all 16. This is the first candidate to meet the fixture criterion; small, medium and Parakeet all stopped at 15/16.
- **Both human clips came out as the inferred phrases**: `new-recording-2.wav` → `Opowiedz mi krótki dowcip.` and `new-recording.wav` → `Otwórz Szyberdach.` Under my hand application of the `unr-02` / `pos-06` rules both pass. The harness does not score these clips, and I still cannot listen, so the file-to-phrase mapping stays inferred. A transcript that reads as a clean command phrase supports that mapping, but the mapping and the transcripts are not independent evidence.
- **No memory failure.** Peak process memory was 1.69 GB (`VmHWM` 1,733,032 kB), against a 4 GB emulator. No crash, `lowmemorykiller`, `lmkd` kill, `FATAL EXCEPTION` or `SIGSEGV` line in logcat (empty `crashgrep`, and a second grep over the whole buffer counted 0). One run, no retry. This contradicts the step 1g expectation (a prediction, not a measurement) that turbo would hit memory trouble on this emulator.
- **Cost is high.** 1,036,613,791 bytes of model files, load 4.7 s, decode 1.5–5.4 s per 1.4–4.4 s clip. The host was heavily loaded (1-minute load average 29, then 43; `emulator-5554` booted), so decode times are approximate and I do not know the uncontended figure.

### Storage cleanup (reversible, our own staging only)

- Before: emulator `/data` free 3,951,612 kB. Step 1g showed turbo needed 1,012,324 kB and would leave 2.80 GiB, under the 3 GiB floor.
- Target: `/data/local/tmp/bydmate-pl-models/candidates/whisper-medium-int8/` (our step 1b staging; three files, 946,072,270 bytes). I listed `files/voice-test` in the app's private storage: it holds only `whisper-small-int8` and three step 1d reports, **no medium copy**, so no app-private file was touched.
- Backup first. No host copy existed (`find` over `/tmp` and `~/Developer` found no `medium-*.onnx`). I pulled one, with 91 GiB free on the host, then hashed the device files and the host files; all six hashes equal the step 1b pins above:

| file | bytes | sha256 (device = host = step 1b pin) |
|---|---|---|
| medium-encoder.int8.onnx | 374196283 | 1c54582b4d829de0089f6cb63bbbdb3bf7555398bacaf855fbecf1a84dfd193e |
| medium-decoder.int8.onnx | 571059257 | 595d00a338a365a7bfa0ca7f296cabc639583bef770ab6130df90f49a6412747 |
| medium-tokens.txt | 816730 | b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126 |

- Backup path: `/tmp/diag1h/backup/whisper-medium-int8/` (re-hashed after the run; still matches). To restore: `adb push /tmp/diag1h/backup/whisper-medium-int8/. /data/local/tmp/bydmate-pl-models/candidates/whisper-medium-int8/`.
- Removed only that directory with `rm -rf`. The empty `candidates/` parent remains. Free after cleanup: 4,875,528 kB (4.65 GiB). Free during the turbo run (after pushing): 3,863,168 kB (3.68 GiB), above the floor. Free after deleting turbo: 4,875,504 kB. Host free: 91 GiB before, 89 GiB after.
- Turbo hashes on the host and on the device both equal the step 1g values:

| file | bytes | sha256 |
|---|---|---|
| turbo-encoder.int8.onnx | 674716297 | b02dcdf54f348741e93fe732b67d933c8dcb6735655f710640143081db38878b |
| turbo-decoder.int8.onnx | 361080764 | 20accd02388482eb3a46bd615631adfdc85e1eb2c7db9ea3f02a40ffe6b81547 |
| turbo-tokens.txt | 816730 | b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126 |

### Configuration actually run

`whisperTurbo` in `/tmp/diag1g/copy/app/src/androidTest/kotlin/com/bydmate/app/voice/CandidateBenchTest.kt` (read before the run): `FeatureConfig(sampleRate=16000, featureDim=128)`, `OfflineWhisperModelConfig(language="pl", task="transcribe")`, `numThreads=4`, `provider="cpu"`, `decodingMethod="greedy_search"`. No silence padding, no preprocessing. The 16 fixture WAVs come from the unchanged `voice/pl/manifest.json`; the two human WAVs are the ones in `/data/local/tmp/bydmate-human`. I did not rebuild: the APKs installed in step 1g (`com.bydmate.app`, `com.bydmate.app.test`) were used as they were, so there was no Gradle run and `~/.gradle` and the private `GRADLE_USER_HOME` were not used.

### All 18 clips, raw turbo transcripts

✅ = passes the unchanged matcher (harness result for the 16 fixtures; hand application for the human clips).

| id | reference | Whisper turbo int8 | decode (ms) |
|---|---|---|---|
| pos-01 | zamknij wszystkie okna | ✅ Zamknij wszystkie okna. | 5418 |
| pos-02 | otwórz okno kierowcy | ✅ Otwórz okno kierowcy | 4797 |
| pos-03 | ustaw temperaturę na dwadzieścia dwa stopnie | ✅ Ustaw temperaturę na 22 stopnie. | 3145 |
| pos-04 | jaki jest poziom baterii | ✅ Jaki jest poziom baterii? | 2435 |
| pos-05 | ustaw głośność na pięćdziesiąt procent | ✅ Ustaw głośność na 50%. | 2508 |
| pos-06 | otwórz szyberdach | ✅ Otwórz szyberdach | 3040 |
| neg-01 | nie zamykaj okien | ✅ Nie zamykaj okien. | 4542 |
| neg-02 | nie otwieraj okna kierowcy | ✅ Nie otwieraj okna kierowcy. | 3340 |
| neg-03 | nie ustawiaj temperatury na dwadzieścia dwa stopnie | ✅ Nie ustawiaj temperatury na 22 stopnie. | 4141 |
| neg-04 | nie zamykaj wszystkich okien | ✅ Nie zamykaj wszystkich okien. | 2650 |
| quo-01 | powiedz zamknij wszystkie okna | ✅ Powiedz zamknij wszystkie okna. | 1648 |
| quo-02 | Kasia powiedziała otwórz okno kierowcy | ✅ Kasia powiedziała otwórz okno kierowcy. | 2320 |
| cmp-01 | otwórz okno kierowcy i zamknij okno pasażera | ✅ Otwórz okno kierowcy i zamknij okno pasażera | 2415 |
| cmp-02 | zamknij wszystkie okna i ustaw temperaturę na dwadzieścia dwa stopnie | ✅ Zamknij wszystkie okna i ustaw temperaturę na 22 stopnie. | 2676 |
| unr-01 | jaka jest dzisiaj pogoda w Krakowie | ✅ Jaka jest dzisiaj pogoda w Krakowie? | 1511 |
| unr-02 | opowiedz mi krótki dowcip | ✅ Opowiedz mi krótki dowcip. | 1502 |
| human 2 (`new-recording-2.wav`, 3.52 s, inferred "Opowiedz mi krótki dowcip.") | | ✅ (hand) Opowiedz mi krótki dowcip. | 2635 |
| human 1 (`new-recording.wav`, 2.67 s, inferred "Otwórz szyberdach.") | | ✅ (hand) Otwórz Szyberdach. | 3155 |

Negation, quotation, compound: all four `neg-*` keep "nie" and the correct verb; both `quo-*` keep the quoted verb and `powiedz`/`powiedziała`; both `cmp-*` keep both halves. `neg-03` has `temperatury`, the ending Parakeet dropped, and `unr-01` has the full `dzisiaj`. Pass/fail counts: 16/16 fixtures, 0 fixture failures, 0 lost negations, 2/2 human clips by inferred mapping.

### Timing and memory (one run)

| stage | value |
|---|---|
| load | 4661 ms |
| RSS before → after load | 264,940 kB → 1,370,492 kB (259 → 1,338 MB) |
| `VmHWM` at load | 1,588,688 kB |
| RSS after fixtures / at end | 1,712,692 kB / 1,713,236 kB |
| peak `VmHWM` | 1,733,032 kB (1,692 MB) |
| decode, 16 fixtures | 1,502–5,418 ms for 1.44–4.37 s audio |
| decode, 2 human clips | 2,635 ms (3.52 s), 3,155 ms (2.67 s) |
| test time | `Time: 59.053` s as reported by `am instrument` |

The first clip (5.4 s for 1.75 s of audio) may include warm-up; I did not separate that. I did not sample page faults or swap, so I cannot say whether memory pressure slowed the decode.

### Comparison with the earlier candidates

Prior results stay as recorded above; this table only adds the turbo row. "Human" is by the inferred mapping.

| candidate | fixtures | human (inferred) | model files (bytes) | load | peak `VmHWM` (MB) | decode per clip | language | note |
|---|---|---|---|---|---|---|---|---|
| Vosk small-pl-0.22 | 10/16 | 0/2 | 95,652,517 (expanded) | 542 ms | 480 | 416–699 ms | Polish only | new runtime dependency |
| Whisper small int8 (pinned) | 15/16 | 0/2 | 375,485,327 | 4185 ms | 1092 | 0.5–1.1 s uncontended; to 8.3 s loaded | forced `pl` | misses `szyberdach` |
| Parakeet TDT 0.6B v3 int8 | 15/16 | 1/2 | 670,478,772 | 2.0–3.0 s | 1006 | 69–299 ms | auto-detected | drops `temperatury` ending; `szywerdach` on the human roof clip |
| Whisper medium int8 | 15/16 | 1/2 | 946,072,270 | 5.2–36.4 s | 2269–2412 | 4.0–51 s (starved in 1b) | forced `pl` | misses `dowcip` |
| **Whisper large-v3-turbo int8** | **16/16** | **2/2** | **1,036,613,791** | **4661 ms** | **1692** | **1.5–5.4 s (loaded host)** | forced `pl` | no miss on any of 18 clips |

Decode times in this table come from different host loads and are not comparable to each other. Memory and the pass counts are the comparable columns.

### Exact commands and output

```sh
export PATH=/Users/tomaszkopycki/Library/Android/sdk/platform-tools:$PATH ANDROID_SERIAL=emulator-5560
M=/data/local/tmp/bydmate-pl-models/candidates/whisper-medium-int8
adb shell "cd $M && sha256sum *"                      # three hashes above
mkdir -p /tmp/diag1h/backup/whisper-medium-int8
adb pull $M/. /tmp/diag1h/backup/whisper-medium-int8  # 3 files pulled, 0 skipped. 123.1 MB/s (946072270 bytes in 7.332s)
(cd /tmp/diag1h/backup/whisper-medium-int8 && shasum -a 256 *)   # equal to the device hashes and the step 1b pins
adb shell "rm -rf $M"                                 # only this directory
adb shell df -k /data                                 # 4875528 KB available (was 3951612)
bash /tmp/diag1g/scripts/run_candidate.sh whisper-turbo-int8 whisperTurbo /tmp/diag1g/dl/whisper-turbo-int8
```

Output of the run script, unedited apart from blank lines:

```
emulator free_kb=4875528 candidate_kb=1012324 floor_kb=3145728
/tmp/diag1g/dl/whisper-turbo-int8/: 3 files pushed, 0 skipped. 82.6 MB/s (1036613791 bytes in 11.967s)
20accd02…1547  turbo-decoder.int8.onnx
b02dcdf5…878b  turbo-encoder.int8.onnx
b34b360d…a126  turbo-tokens.txt
com.bydmate.app.voice.CandidateBenchTest:.
Time: 59.053
OK (1 test)
free after run: 3863168 KB
free after cleanup: 4875504 KB
```

The script runs `am instrument -w -e class com.bydmate.app.voice.CandidateBenchTest#whisperTurbo -e run whisper-turbo-int8 com.bydmate.app.test/androidx.test.runner.AndroidJUnitRunner`. `OK` here means the test's own assertion held (it fails when any fixture misses its required words, has a forbidden word, or loses "nie"; Parakeet and Vosk failed that way in step 1g). It is not a log-only judgement. I also read the device report `files/report-1g-whisper-turbo-int8-whisper-turbo-int8.json` (`loadMs` 4661, `peakHwmKb` 1733032, 16 of 16 `pass=true`, no `lostNegation`). Skips: 0. Failures and crashes: none. Raw outputs: `/tmp/diag1g/out/whisper-turbo-int8.{instrument.txt,logcat.txt,device-sha256.txt,crashgrep.txt,report.json}`. Nothing in `/tmp` was deleted.

### Limits of this evidence

- Same 16 synthetic clips from one TTS voice, plus two clips from one human speaker with an inferred mapping. A 16/16 on this smoke set is not a word-error rate and does not show that human, noisy or accented speech passes. The two human clips alone cannot rank turbo above Parakeet.
- One run. Greedy decoding is deterministic for the same input and files (Parakeet repeated identically in step 1g), but I did not repeat turbo, and timing and memory were measured once.
- Emulator on an M1 Max with a loaded host. Phone and head-unit memory, latency and thermal behaviour are unmeasured. A 1.7 GB peak and 1.0 GB of model files may not fit a head unit; the head unit's RAM is not in this evidence.
- Step 1c found a runtime cap of about 6 decoded tokens per second of input audio in Sherpa 1.13.3's Whisper path. No turbo transcript here was clipped, but the longest clip is 4.4 s, so longer utterances are untested.
- Licence is unresolved: the turbo card says MIT and `openai/whisper` is MIT, while another page lists Apache-2.0 for the family; the Sherpa repack repo has no licence metadata, and whether we may mirror the files is unknown.
- Turbo is not in the model manifest and was not pinned in code. It would need a manifest entry (and a 128-mel config check in production code) before any non-test use.

### Recommendation (next model decision, nothing promoted)

1. **Keep the production gate and Steps 3–5 stopped.** Turbo meets the 16-fixture criterion, but the model choice depends on the human corpus, latency and head-unit memory, none of which exist yet. That is your call to lift, not mine.
2. **Carry two candidates into the consented human corpus: Whisper turbo (accuracy) and Parakeet v3 (speed and memory), with small as the baseline.** Turbo is the only model with no miss on 18 clips and it can be forced to Polish, which Parakeet cannot. Parakeet is about 10–30 times faster per clip in the measurements I have (different host loads, so treat this as rough) and uses 1.0 GB against 1.7 GB.
3. **Before choosing turbo for the car**, measure its uncontended latency and peak memory on a phone-class or head-unit-class device. If the head unit cannot hold about 1.7 GB or cannot decode a short command in a couple of seconds, turbo drops out regardless of accuracy.
4. Decision for you on the latency target for a spoken command; it picks between turbo and Parakeet more than accuracy does at this point.
5. Unchanged: record the 16 phrases with a known file-to-phrase mapping, then run the candidates on that corpus; check Parakeet's language auto-detection on short commands; settle the licences before any mirroring.

## Step 1i — Parakeet pinned as the development model

Measured 2026-10-20 (22:2x to 22:3x CEST) on emulator `BM_Polish_1b` (`emulator-5560`; `device_list` then `device_open` first; Android 14, SDK 34, `arm64-v8a`, 4 cores, host Apple M1 Max). Sherpa 1.13.3 from the unchanged production AAR; no runtime or dependency change. `emulator-5554` (another task's) stayed attached and was never targeted: every Gradle and `adb` command ran with `ANDROID_SERIAL=emulator-5560`. No vehicle code, no vehicle writes, no commit.

**Answer.**

- **Pinned compatibility and integrity pass.** `PolishModelManifestTest` 27 tests, 0 failures, 0 skipped. On the emulator the four new methods (asset integrity, runtime compatibility with safety invariants, private human clips, Gosia PCM) passed: 4 tests, 0 failures, 0 errors, 0 skipped.
- **The original accuracy gate still fails, unchanged: 15/16.** `parakeetTranscribesPolishUtterancesKeepingNegation` fails with `neg-03 [negative]: 'Nie ustawiaj temperatur na 22 stopnie.' (missing=[temperatury|temperatura] forbidden=[] lostNegation=false)`. That is the same miss as in Step 1g. It stays a failure; a passing compatibility test is a different statement.
- **No safety invariant failed** (details below). That is a result on 16 synthetic clips, not grammar-safety evidence; Step 4 does not exist yet.
- **Human clips (file-to-phrase mapping still inferred, not heard):** `new-recording-2.wav` → `Opowiedz mi, krótki dowcip.` (passes the `unr-02` rule by hand); `new-recording.wav` → `Otwórz szywerdach.` (fails the `pos-06` rule: `szyberdach` is one letter off). So 1/2, as in Step 1g.
- Nothing here claims car readiness. Steps 3 to 5 cannot be marked ready for release from this step.

### What was pinned

Active manifest ids: `parakeet-tdt-0.6b-v3-int8`, `gosia-medium-int8`. Whisper tiny/small were removed from `PolishModelManifest.kt` and from the provisioning requirement. Repo `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8`, commit `2bda32ec70b097a55adaa07d9a7173915b43cc78` (the Step 1g URLs), four plain files and no archive:

| file | bytes | sha256 |
|---|---|---|
| encoder.int8.onnx | 652184281 | acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247 |
| decoder.int8.onnx | 11845275 | 179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e |
| joiner.int8.onnx | 6355277 | 3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3 |
| tokens.txt | 93939 | d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d |

URL form: `https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/2bda32ec70b097a55adaa07d9a7173915b43cc78/<file>`. I reused `/tmp/diag1g/dl/parakeet-v3-int8` after re-hashing it on the host (`shasum -a 256`, all four equal the Step 1g values) and again on the device (`sha256sum` after `adb push`, same four values). Nothing was downloaded in this step.

Runtime settings (`PolishModelManifest.PARAKEET_SETTINGS`, identical to the Step 1g run): `FeatureConfig(sampleRate=16000, featureDim=128)`, `OfflineTransducerModelConfig(encoder, decoder, joiner)`, `tokens`, `modelType="nemo_transducer"`, `numThreads=4`, `provider="cpu"`, `decodingMethod="greedy_search"`. The NeMo transducer path has no language or task field, so Polish is never forced.

### Licence, attribution and export notices (primary sources, read 2026-10-20)

- **Upstream model.** `nvidia/parakeet-tdt-0.6b-v3` (commit `541d1f99c6b0c3cd0b11a95167540bb8edefd82b`, `README.md` read from `main`, SHA-256 `7a4306a4…302b`). Card front matter: `license: cc-by-4.0`. Body: "GOVERNING TERMS: Use of this model is governed by the CC-BY-4.0 license" and "This model is ready for commercial/non-commercial use." The card has no copyright line and no separate NOTICE text that I could find.
- **What CC-BY-4.0 requires when we share it, modified or not** (legal code read, section 3(a)(1) and 3(a)(2)): identify the creator, retain any copyright notice supplied (none found), include a notice that refers to the licence and to the disclaimer of warranties, give a URI to the material, **indicate that it was modified**, and include the licence text or URI. It may be met in any reasonable manner for the medium. Proposed wording is stored as `PolishModelPackage.attribution` and a plain statement of changes as `modifications`; Step 8 must check that attribution is user-visible in the package.
- **Supplied export changes (not NVIDIA's files).** The pinned files are a third-party conversion. I read `scripts/nemo/parakeet-tdt-0.6b-v3/export_onnx.py` and `run.sh` in `k2-fsa/sherpa-onnx` on `master` (script SHA-256 `ec6194e9…1e40`; copyright "2025 Xiaomi Corp.", repo licence Apache-2.0). It loads the NeMo checkpoint, exports `encoder.onnx`, `decoder.onnx`, `joiner.onnx` (transducer branch only), quantizes each with onnxruntime `quantize_dynamic` (encoder `QUInt8`, decoder and joiner `QInt8`), writes `tokens.txt` from the joint vocabulary plus `<blk>`, and adds metadata to the encoder (`vocab_size`, `normalize_type`, `subsampling_factor=8`, `model_type=EncDecRNNTBPEModel`, `feat_dim=128`, `comment: Only the transducer branch is exported`, source `url`). `run.sh` installs `onnx==1.17.0` and `onnxruntime==1.17.1`. **Inference, not verified:** that the pinned repack files came from exactly this script revision. The repack repo has four commits dated 2025-08-16 and I read the script as it is today.
- **Repack repo terms.** `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8` has no README (404), no licence file and no licence metadata (`cardData` empty). **Unresolved:** whether the converter adds terms beyond the upstream CC-BY-4.0. A missing licence field does not itself prove redistribution is forbidden, and I record no actual restriction. We neither mirror nor commit the weights: the manifest downloads from the repack URL only.
- **Training data.** The card lists Granary (its dataset card metadata says `cc-by-4.0`) and NeMo ASR Set 3.0. I did not read NeMo ASR Set 3.0 terms and make no claim about them.
- **Runtime.** Unchanged from earlier steps (Sherpa Apache-2.0, onnxruntime MIT); the notices bundled inside the AAR are still not read. Gosia notices unchanged and still unresolved for Piper/espeak-ng.
- Copies kept outside the repo in `/tmp/diag1i/meta/`: card, API JSON, export script, CC-BY-4.0 legal code.

### Red/green record

Commands: `export JAVA_HOME="$(/usr/libexec/java_home -v 17)" ANDROID_HOME=/Users/tomaszkopycki/Library/Android/sdk GRADLE_USER_HOME=/tmp/diag1g/gradle-home`, then for device runs `ANDROID_SERIAL=emulator-5560`. The private Gradle home had no unit-test, Hilt or UTP dependencies cached, so the first attempts with `--offline` failed with `No cached version of … available for offline mode` (`kotlin-reflect`, then `hiltAggregateDeps`, then `android-test-plugin-host-additional-test-output`). I re-ran each once without `--offline` to fill that private cache, then used `--offline`. `~/.gradle` was not used. Logs: `/tmp/diag1i/logs/`.

| # | what | command | actual result |
|---|---|---|---|
| R1 | unit, no API | `./gradlew :app:testDebugUnitTest --tests '*PolishModelManifestTest'` | compile failed: `PolishModelManifestTest.kt:59:37 Unresolved reference 'PARAKEET_TDT_V3_INT8'`, plus `PARAKEET_SETTINGS`, `validateNemoTransducerShape`, `resolveNemoTransducer`, `PolishAsrResolution`; `BUILD FAILED` |
| R2 | unit, stub API (empty package, `NotReady(emptyList())`) | same | `27 tests completed, 12 failed`, `BUILD FAILED in 51s`. Failing: shipped ids, four-file pins, runtime settings, shape, shape without joiner, licence/modification notice, corrupted tokens, truncated joiner, missing joiner, empty directory, corrupted-file-with-all-four, four verified paths. One compatibility test (`not ready when the directory does not exist`) passed vacuously on the stub |
| G1 | unit, implemented | same | `BUILD SUCCESSFUL in 56s`, JUnit XML `tests=27 failures=0 errors=0 skipped=0` (a later re-run reported `BUILD SUCCESSFUL in 3s` because Gradle found it up to date) |
| R3 | device, Parakeet not staged | `connectedDebugAndroidTest` with `class=$C#provisionedAssetsMatchThePinnedManifest,$C#parakeetRuntimeIsCompatibleAndKeepsSafetyInvariants,$C#parakeetTranscribesPolishUtterancesKeepingNegation` | `Starting 3 tests on BM_Polish_1b(AVD) - 14`, all 3 `FAILED`: `Verified Polish model assets are not provisioned under /data/user/0/com.bydmate.app/files/voice-test/pl` and `Model assets are not provisioned or fail verification`; `BUILD FAILED in 32s` |
| G2 | device, staged | `…class=$C#provisionedAssetsMatchThePinnedManifest,$C#parakeetRuntimeIsCompatibleAndKeepsSafetyInvariants,$C#parakeetDecodesPrivateHumanClips,$C#gosiaSynthesizesValidPolishPcm` | first attempt: compatibility test errored with `IllegalFormatPrecisionException: 39` in my new log line (`String.format` over a transcript containing `50%`): a harness bug, not a recognition result. Fixed; re-run `BUILD SUCCESSFUL in 1m 3s`, `tests=4 failures=0 errors=0 skipped=0` |
| R4 | device, accuracy gate, unchanged assertion | `…class=$C#parakeetTranscribesPolishUtterancesKeepingNegation` (run twice) | `1 tests, 1 failure`, `java.lang.AssertionError: 1/16 utterances failed: neg-03 [negative]: 'Nie ustawiaj temperatur na 22 stopnie.' (missing=[temperatury\|temperatura] forbidden=[] lostNegation=false)`; `BUILD FAILED` |
| F | device, whole class in one process | `…class=$C` | `Starting 5 tests`, `tests=5 failures=1 errors=0 skipped=0`, `BUILD FAILED in 1m 33s`; the one failure is the accuracy gate above |

`$C` is `com.bydmate.app.voice.PolishNativeSmokeTest`. Zero skipped checks in every device run. The accuracy assertion code was moved to a Parakeet recognizer but its matcher, tokens and failure rule are the original ones: required tokens as whole words after case, punctuation and diacritic folding; `nie` required for negatives. `manifest.json` SHA-256 is still `f64c62f7…ccb2e`, and none of the 16 WAVs was touched.

### Separate checks: what each test claims

- `provisionedAssetsMatchThePinnedManifest` (integrity): sizes and SHA-256 of all Parakeet and Gosia files, plus the Gosia `espeak-ng-data` file count. Not an accuracy statement.
- `parakeetRuntimeIsCompatibleAndKeepsSafetyInvariants` (compatibility and safety): resolves the four verified paths, loads the recognizer with the pinned settings, decodes all 16 fixtures and checks the invariants below. **Passing it does not mean the transcripts are accurate**; it passed while the accuracy gate failed.
- `parakeetTranscribesPolishUtterancesKeepingNegation` (the transcript-accuracy gate): the only test that may be called that.
- `parakeetDecodesPrivateHumanClips`: needs exactly two WAVs in `/data/local/tmp/bydmate-human` and fails otherwise; it logs raw transcripts and asserts only that they are non-empty.

### Safety invariants (16 fixtures, 0 violations)

- Every negative clip kept `nie`: neg-01 to neg-04 all start `Nie …`. neg-03 still fails the accuracy gate on its noun ending, not on negation.
- No forbidden token appeared. **Limit:** every `forbiddenTokens` list in `manifest.json` is empty, so this check is vacuous on the current corpus; I did not edit the fixtures to change that.
- No negative or unrelated transcript satisfied the required-token set of any positive or compound fixture (6 negative/unrelated transcripts against the 8 positive/compound required-token sets).
- Quoted clips kept their quote marker (`Powiedz …`, `Kasia powiedziała, …`). Like every model so far, Parakeet transcribes the quoted command text in full, so refusing quoted speech is a grammar job (Step 4), not something this step shows.
- Not a grammar-safety result.

### All 16 fixtures and 2 human clips, raw transcripts

✅ = passes the unchanged accuracy matcher. Same decode in every run (logcat tag `PolishSmoke`).

| id | reference | Parakeet v3 int8 |
|---|---|---|
| pos-01 | zamknij wszystkie okna | ✅ Zamknij wszystkie okna. |
| pos-02 | otwórz okno kierowcy | ✅ Otwórz okno kierowcy. |
| pos-03 | ustaw temperaturę na dwadzieścia dwa stopnie | ✅ Ustaw temperaturę na 22 stopnie. |
| pos-04 | jaki jest poziom baterii | ✅ Jaki jest poziom baterii? |
| pos-05 | ustaw głośność na pięćdziesiąt procent | ✅ Ustaw głośność na 50%. |
| pos-06 | otwórz szyberdach | ✅ Otwórz szyberdach. |
| neg-01 | nie zamykaj okien | ✅ Nie zamykaj okien. |
| neg-02 | nie otwieraj okna kierowcy | ✅ Nie otwieraj okna kierowcy. |
| neg-03 | nie ustawiaj temperatury na dwadzieścia dwa stopnie | ❌ Nie ustawiaj temperatur na 22 stopnie. (missing `temperatury\|temperatura`) |
| neg-04 | nie zamykaj wszystkich okien | ✅ Nie zamykaj wszystkich okien. |
| quo-01 | powiedz zamknij wszystkie okna | ✅ Powiedz zamknij wszystkie okna. |
| quo-02 | Kasia powiedziała otwórz okno kierowcy | ✅ Kasia powiedziała, otwórz okno kierowcy. |
| cmp-01 | otwórz okno kierowcy i zamknij okno pasażera | ✅ Otwórz okno kierowcy i zamknij okno pasażera. |
| cmp-02 | zamknij wszystkie okna i ustaw temperaturę na dwadzieścia dwa stopnie | ✅ Zamknij wszystkie okna i ustaw temperaturę na 22 stopnie. |
| unr-01 | jaka jest dzisiaj pogoda w Krakowie | ✅ Jaka jest dzisia pogoda w Krakowie? (`dzisia`: the final `j` is lost; the matcher does not require that word) |
| unr-02 | opowiedz mi krótki dowcip | ✅ Opowiedz mi krótki dowcip. |
| human `new-recording-2.wav` (inferred "Opowiedz mi krótki dowcip.") | | `Opowiedz mi, krótki dowcip.` (passes the `unr-02` rule by hand) |
| human `new-recording.wav` (inferred "Otwórz szyberdach.") | | `Otwórz szywerdach.` (fails the `pos-06` rule: `szywerdach`) |

15/16 fixtures, 1/2 human clips by the inferred mapping. The human clips are one speaker and are not scored by the harness; the mapping was never confirmed by listening. The five full corpus decodes in this step (compatibility run, two stand-alone accuracy runs, two inside the whole-class run) produced identical transcripts for all 16 fixtures, as in Step 1g; the human clips were decoded in three runs with the same two transcripts.

### Settings, timings and memory (emulator, loaded host, noisy)

- Model settings: see above. Four threads; no padding, preprocessing, hotwords or transcript repair.
- Load: 18.2 s and 18.9 s in the two stand-alone accuracy runs (fresh APK install and a fresh 0.67 GB copy into app-private storage, cold file cache) versus 6.9 s in the whole-class run. Step 1g measured 2.0 to 3.0 s reading the files in place. The cause of the slower first loads is not verified; I believe it is a cold cache after the copy and `emulator-5554` running.
- Decode: 136 to 1724 ms per 1.4 to 4.4 s fixture across the runs; human clips 232 to 2218 ms for 3.52 s and 2.67 s. The same clips took 69 to 299 ms in Step 1g, so these figures show host contention, not a regression I can explain.
- Peak process memory (`VmHWM`): 1,030,588 to 1,031,940 kB (about 1.0 GiB) across runs. `VmRSS` before load 265,060 to 325,184 kB, after load 971,956 to 997,304 kB, after decoding (recognizer still loaded) 1,028,044 to 1,031,564 kB. Emulator, not a phone or head unit.
- Storage: emulator free space was 5.0 GiB before staging, 4.28 GiB at the end (floor 3 GiB held); the host had about 94 GiB free.

### Known misses and language auto-detection limits

- **Known misses:** `neg-03` `temperatur` (dropped final `y`); `unr-01` `dzisia` (passes only because the matcher does not require that word); human roof clip `szywerdach`. Exact matching has no tolerance for any of these.
- **Language detection:** the model chooses the language itself and the NeMo transducer API in Sherpa 1.13.3 exposes no language field, so Polish cannot be forced, and I make no forced-Polish claim. All 18 clips came out as Polish text, but I did **not** test short, noisy or non-Polish input. Those probes are assigned to Step 8 and are pending. Uncertainty from detection must never broaden command matching; that belongs to the Step 4 grammar and is untested here.

### Limits of this evidence

- Synthetic Zosia fixtures plus two clips from one speaker with an unconfirmed mapping. No noise, no VAD segmentation, no repeated-session memory trend, no capture-path resampling.
- Emulator on an M1 Max with another emulator running; no phone, no head unit, no vehicle.
- The forbidden-token check is vacuous with the current manifest; the cross-fixture check covers only 16 clips.
- Gosia was checked for valid PCM only (22050 Hz, finite, not silent, not clipped); nobody listened. Its 3 phrases took 452 to 1350 ms to generate across runs.
- The repack's own terms and the notices inside the AAR remain unresolved, as above.

### Changed files

`docs/voice/polish-native-feasibility.md`, `app/src/main/kotlin/com/bydmate/app/voice/PolishModelManifest.kt`, `app/src/androidTest/kotlin/com/bydmate/app/voice/PolishNativeSmokeTest.kt`, `app/src/test/kotlin/com/bydmate/app/voice/PolishModelManifestTest.kt`, `docs/plans/polish-voice-overview.html`. No fixture, `manifest.json` or WAV edits. Staged on the emulator (not in git): `/data/local/tmp/bydmate-pl-models/parakeet-tdt-0.6b-v3-int8`; the earlier Whisper staging directories there were left in place, untouched and unused.

## Step 8 — re-run on the final tree, probes and in-app download (2026-10-21)

Details and caveats: [polish-acceptance.md](polish-acceptance.md). Emulator `emulator-5560` (Android 14 arm64), disposable harness copy; nothing here is a car result.

- **Accuracy gate unchanged: 15/16.** Whole `PolishNativeSmokeTest` class, final code: 5 tests, 1 failure (`neg-03`: `Nie ustawiaj temperatur na 22 stopnie.`), 0 skipped; `COMPAT decoded=16 violations=0 peakKb=1045668`. Human clips: `Opowiedz mi, krótki dowcip.` and `Otwórz szywerdach.` (mapping still inferred). Fixture and `manifest.json` SHA-256 values are identical to the start of the run (`manifest.json` `f64c62f7…ccb2e`).
- **Production download verified.** The app downloaded Parakeet itself (`Pobierz (639 MB)` → `Gotowy`); `sha256sum` of the four files on the device equals the pins above. Gosia downloaded (`Pobierz (20 MB)` → `Gotowy`).
- **Short, noisy and non-Polish probes (language auto-detection), synthetic, labelled, run twice with identical results:** 33 clips, 0 wrong vehicle actions, 4 correct `Match`, 6 `Refused`, 23 `Unknown`. Russian, Czech, Slovak, Ukrainian, English and German speech was transcribed in its own language and never matched; noisy Polish sometimes became English or German text; garbled `nie zamykaj okien` stayed `Unknown`, not `Refused` (so it would reach the opt-in assistant as text). Table: [polish-acceptance.md](polish-acceptance.md#phone-and-emulator-evidence). Not car microphone audio, not cabin noise.
- **Gosia output inspected, not heard.** Four phrases written as 22,050 Hz PCM16 WAV and decoded by Parakeet: `Okna zostały zamknięte.`, `Temperatura ustawiona na 22 stopnie.`, `Nie mam danych o poziomie baterii.`, `Nie rozumiem tego polecenia.`
- **Housekeeping.** I deleted our own Whisper tiny/small staging directories on the emulator (`/data/local/tmp/bydmate-pl-models/whisper-*`; pins and results stay in this document) and re-staged Parakeet from a fresh, hash-checked download because `/tmp/diag1g` had been wiped.
- **Notices.** The "Step 8 must check user-visible attribution" item **fails**: the CC-BY-4.0 text exists only as a Kotlin constant, not in the UI or APK. The Sherpa AAR contains no licence files. See the table in [polish-acceptance.md](polish-acceptance.md#distribution-notices-blocked).
