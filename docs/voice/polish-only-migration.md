# Polish-only migration compatibility exceptions

## Latest car-test verification — 2026-10-22

Ready for the approved car capture test. All nine final-review findings are repaired. The post-repair combined command (`/tmp/bydmate-final-fixes-verify.sh`) finished `BUILD SUCCESSFUL in 10m 45s`: 196 debug tests and 2 release tests, zero failures/errors/skips; debug, unsigned release and Android-test APK builds, lint and Detekt passed. The unsigned release APK is not distributed.

Before this repair batch, the full suite passed 8,512 debug tests with zero failures/errors and three skips (`BUILD SUCCESSFUL in 18m 11s`). It was not repeated after the batch; the affected areas were covered by the focused combined run above. The earlier failed repair run is retained as `/tmp/bydmate-final-fixes-verify.log`; its test assertion, function-count and translation findings are fixed in the successful rerun.

The final debug APK passed two synthetic recorder device tests on emulator-5562 (`/tmp/bydmate-final-device-check.log`). The repaired capture implementation also passed manual Start, seven markers, Stop, Save, Cancel and Delete checks; exported and retained private bytes matched. All 12 upgrade seed/assert/restore invocations passed on that implementation (`/tmp/bydmate-fixed-car-upgrade.log`). The last function extraction and error translations were followed by the final build and two device tests; the manual and upgrade flows were not repeated for those static changes.

Python checker: 39 tests, OK; repository scanner: `PASS: no unclassified authored Cyrillic`. `git diff --check` passed. Voice mutation proof passed both arms and restored exact source bytes before the earlier full run. Durable logs and manual evidence are in `BYDMate-step8-evidence/polish-only/car-capture/` beside this checkout.

Download: `/Users/tomaszkopycki/Downloads/BYDMate-car-capture-test-2026-10-22/BYDMate-v4.0.1-car-capture.apk`, 113414234 bytes. SHA-256: `73a559bbc22ca177f9d0abdd9a9066b93ae9bc49cbf51d0573d38e64c744686b`. Polish instructions and `SHA256SUMS.txt` are beside it. The previous car-test APK is preserved.

The pinned-model `neg-03` word-ending miss remains: all three decoder arms scored 15/16 and retained negation. Google Maps/Waze HUD adapters are not implemented. The approved car test collects their private navigation data and checks the microphone, steering-wheel button, widget and Mini connection. Physical-car behavior remains unverified. The instructions cover button-driven listening, close-after-command, and the reversible factory-assistant switch, including its effect on Shortcuts.

The older results below are historical checkpoints and are superseded by this block where they differ.

`tools/check-polish-only.py` scans authored source, resources, documentation and
tooling for Cyrillic. It reports a path and line for every occurrence that is not
listed below. It skips generated output, dependencies and Git history.

The migration removes the Russian voice path. It does not change external input
formats, installed app identifiers, licenses, attribution, user-entered text or
saved histories. The English word `Russian` remains valid in migration data and
truthful historical discussion; it is not a runtime language choice.

## Allowed Cyrillic

| Location | Reason | Boundary |
| --- | --- | --- |
| `app/src/main/res/values-be/strings.xml` and `strings_hybrid_peripherals.xml` | Belarusian is an existing non-Russian UI locale. | The two exact Android resource files. Only `<string name="…">` lines may contain Cyrillic. |
| `NaviGoButton.kt`, `NaviRichNotificationParser.kt`, `NaviRichPostProcessor.kt`, `NaviScreenReader.kt`, `NavGuidance.kt`, and `NavManeuverCodes.kt` | These paths parse or compare captured Yandex notification input. | Only the exact per-path values in `EXTERNAL_YANDEX_TOKENS`; an unrelated Cyrillic value in the same path still reports. |
| `RuleShare.kt` and `HudTextSanitizer.kt` | Existing share-file slugs and HUD text use character-to-ASCII transforms. | Only complete `'character' to "ASCII"` table rows in the two exact paths. |
| `SelfEchoFilter.kt` | The legacy normalizer folds two Cyrillic Unicode characters before echo comparison. | Only the exact documented and executable `ё`→`е` fold. |
| `AgentTools.kt` | Historical saved Place aliases and canonical keys remain readable. | Only the exact aliases in the `HomeWork` compatibility branch, the exact `legacyPlaceName = "Дом"` field, and distance-input units. A standalone output label remains reportable. |
| `tools/check-polish-only.py` | The checker lists its approved compatibility data and Unicode transforms. | Only the exact values declared in its allowlists; adding another Cyrillic token fails. |
| `app/src/test/resources/beta/map-rsc-national-library.txt` | Captured BETA map response used by charger tests. | Only JSON-style `name` and `address` string values in this exact capture; every other field remains checked. |
| `app/src/test/resources/nominatim/reverse-minsk.json` | Captured Nominatim reverse-geocoding response. | Only root `name`/`display_name` and address `road`/`city_district`/`city`/`country` string fields in this exact response. |
| `docs/plans/2026-10-21-multi-navigator-hud-feasibility.md` | The plan quotes the exact Navigator stub notification and distance/ETA units needed to describe the current parser contract. | Only `Навигатор запущен`, `км`, `м`, `ч`, and `мин`. |
| `docs/voice/polish-acceptance.md` | The acceptance table preserves exact non-Polish probes and an ASR transcript to prove that they are rejected. | Only `Так.`, `Закрой все окна.`, `Открой окно водителя.`, and `Закры у все викна.`. |
| `AgentTools.kt` | Historical saved Place aliases remain readable. | Only the input aliases in `EXACT_TOKEN_ALLOWANCES` and the exact `legacyPlaceName = "Дом"` field. The canonical output label `Дом` remains reportable. |
| `MediaSessionListenerServiceTest.kt` | Captured Yandex Navigator notification titles and bodies verify the producer contract, trace redaction, and manoeuvre handling. | Only the exact values in its `TEST_ORACLE_TOKEN_ALLOWANCES` entry; adjacent authored Cyrillic still reports. |
| `HudTextSanitizerTest.kt` | Direct Unicode-to-Latin transliteration test oracles verify the HUD sanitizer. | Only the exact Cyrillic source strings in its `TEST_ORACLE_TOKEN_ALLOWANCES` entry; adjacent authored Cyrillic still reports. |
| `AgentTools.kt` | A historical saved `Работа` place and the launcher-label alphabet folding table preserve compatibility matching. | Only the complete Polish-input compatibility row in `TEST_ORACLE_LINE_ALLOWANCES` and the exact `LAUNCHER_LABEL_CYRILLIC_FOLD_PAIRS` entries. The pairs match characters only; they do not permit a free-form Cyrillic launcher label. |
| `PowerStateRuleMigration.kt` | The one-shot migration compares a historical saved template key. | Only `Шторка при движении`; the former hybrid translation defect is not allowed. |
| `SettingsRepository.kt`, `AutomationViewModel.kt`, `DashboardScreen.kt`, and `AppLanguage.kt` | Native currency, supported Belarusian locale/template data, and technical units are compatibility or locale data. | Only the exact per-path values or the one exact unit-regex line in `DashboardScreen.kt`. |
| `values{,-en,-pl,-pt,-zh}/strings.xml` | `Русский язык` is a factory selector label and `ЮMoney` is a provider brand. | Only those exact strings, only in the five listed resource files. |
| `config/detekt/baseline.xml` | Detekt generates `<ID>` values from source signatures, including historical literal text. | Only complete `<ID>…</ID>` lines; other XML content remains reportable. |
| `tools/test_check_polish_only.py` | The checker’s own unit tests need a fixed set of hostile Unicode inputs and compatibility examples. | Only `CHECKER_TEST_ORACLE_TOKENS`; this is not an exemption for application tests or test directories. |
| Selected test-oracle paths in `TEST_ORACLE_TOKEN_ALLOWANCES` | These exact test values prove captured navigation/launcher/provider input, captured charger-provider fields, historical serialized bytes, supported Belarusian output, or Unicode guard coverage. | Exact path and token only. An unrelated Cyrillic value on the same path still reports. |
| `app/src/test/resources/native-stack-fixtures/window-songl-held-target-20260929.txt` | A raw Song L device/log capture preserves the original protocol bytes. | Only the ten exact captured command/verdict tokens in `NATIVE_SONGL_CAPTURE_TOKENS`; adjacent authored Cyrillic reports. |
| `app/src/test/resources/navdata/maneuver-corpus.txt` | Frozen characterization vocabulary for maneuver-code regression coverage. It is not evidence of a Yandex-only contract. | Only the 469 exact Cyrillic rows in `FROZEN_MANEUVER_CORPUS_ROWS`. The checker does not read the corpus or golden TSV to make this decision; an appended row or extra text on a known row reports. |
| `RuleShareTest.kt` legacy round trip | Exact historical name, trigger caption, and action caption prove that export/import preserves saved user text. | Only `Дом: звонок`, `Скорость > 7 км/ч`, and `Закрыть все окна` in that exact test path; adjacent authored text still reports. |
| Report-backed navigation, provider, Belarusian, raw-capture, history, and fixture paths in `REPORT_BACKED_ORACLE_TOKENS` | Owner-reviewed external navigation input, supported Belarusian locale output, raw capture bytes, historical saved output, and exact Unicode/privacy test oracles. | Exact path and exact token only. The map is frozen in the checker; it does not read source, reports, or golden files at scan time. Adjacent authored Cyrillic still reports. |
| `AgentToolsHomeWorkTest.kt` | One test proves a Polish request resolves a historical saved `Дом` place by its coordinates. | Only the two complete fixture/assertion lines in `TEST_ORACLE_LINE_ALLOWANCES`; other saved-place fixtures still report. |

Generated maneuver labels, driver text, tool descriptions, assistant prompts,
developer comments, RU-locale feature tests, and ordinary test fixtures must be
Polish or English as appropriate. Do not add a directory-wide, file-wide, generic
Kotlin, regex-class, or test-tree exemption. A new external-data or attribution
exception needs an exact path, exact tokens, a reason, and matching changes in
this document, the checker, and its tampering regression tests. The checker must
continue to report untranslated tests and the runtime-safety Cyrillic heuristic.

The checker intentionally still reports generic DriverMemory facts, generic saved
places, generic weather/place inputs, synthetic route-road values, and generic
mocked Overpass labels covered by the checker regression. They do not by
themselves prove a released-data compatibility contract.

## Verification status — 2026-10-22

The compatibility exceptions above are scanner policy, not proof that the app is
ready for a car test. The repair-batch checker run recorded 34 passing Python tests
and a successful full scan with no unclassified authored Cyrillic occurrences.
The reported command was `python3 tools/check-polish-only.py`; its output was
`PASS: no unclassified authored Cyrillic`.

The following evidence is recorded separately in
`docs/plans/2026-10-21-polish-only-verification.md`:

- The Step 2 baseline upgrade run recorded the expected RED assertions for the
  missing, Russian, unknown, and wrong-typed legacy cases. Each seed and restore
  selector passed in that baseline run.
- The candidate upgrade GREEN run on `emulator-5562` completed with helper exit
  0. All four legacy cases completed seed, assertion, and restore: 12 one-test
  invocations passed, and the exact manifest/installed runner were verified.
- The native smoke is still blocked: its assets/runtime-safety/Gosia checks
  passed in the final run (5 tests, 1 failure), but negative case `neg-03`
  remains a preexisting accuracy failure. Greedy and modified beam widths 4 and 8 gave the same word-ending error while preserving negation; no runtime decoder change was made. Floating-widget diagnostics passed;
  capture passed and saved all 107 images. Four images were visually inspected.
- The repair full Gradle command finished `BUILD FAILED` in 10m 19s with
  8,468 tests, 3 failures, 0 errors, and 3 skipped. Two stale expectations
  were corrected; the focused rerun passed 63 tests in 36s including Detekt.
  The timezone-version-dependent Lisbon oracle was replaced with an Asuncion midnight-gap test; all 12 CalendarPeriods tests passed in the focused run. Both APK builds,
  lint, and Detekt passed. The full suite was not repeated after the test-only corrections.
- Final review completed all nine parts. The user-approved repair batch is
  implemented under `docs/plans/2026-10-22-polish-only-review-fixes.md`.
  Its earlier focused run passed 234 tests in 52s including Detekt.
- No physical-car microphone or HUD proof is recorded. Do not treat a simulator
  result as car proof.

## Setup after migration

Set up Parakeet recognition, the shared VAD asset, and the Gosia voice. Russian
model-selection or download instructions are obsolete. A missing Parakeet, VAD,
or Gosia asset leaves voice not ready; migration must not enable voice, connect an
assistant, or rewrite user phrases.
