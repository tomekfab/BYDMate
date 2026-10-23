#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/.." && pwd)
helper="$repo_root/tools/run-polish-only-upgrade.sh"
temporary=$(mktemp -d)
trap 'rm -rf "$temporary"' EXIT

fake_adb="$temporary/adb"
fake_log="$temporary/adb.log"
baseline="$temporary/baseline.apk"
candidate="$temporary/candidate.apk"
test_apk="$temporary/test.apk"
touch "$baseline" "$candidate" "$test_apk"

cat > "$fake_adb" <<'EOF'
#!/usr/bin/env bash
set -u
printf '%s\n' "$*" >> "$FAKE_ADB_LOG"

success() {
  local method=$1
  # Exact successful AndroidJUnitRunner framing captured in
  # /tmp/bydmate-polish-only-step2-upgrade-red.log.
  printf '%s\n' \
    'INSTRUMENTATION_STATUS: class=com.bydmate.app.voice.PolishOnlyUpgradeTest' \
    'INSTRUMENTATION_STATUS: current=1' \
    'INSTRUMENTATION_STATUS: id=AndroidJUnitRunner' \
    'INSTRUMENTATION_STATUS: numtests=1' \
    'INSTRUMENTATION_STATUS: stream=' \
    'com.bydmate.app.voice.PolishOnlyUpgradeTest:' \
    "INSTRUMENTATION_STATUS: test=$method" \
    'INSTRUMENTATION_STATUS_CODE: 1' \
    'INSTRUMENTATION_STATUS: class=com.bydmate.app.voice.PolishOnlyUpgradeTest' \
    'INSTRUMENTATION_STATUS: current=1' \
    'INSTRUMENTATION_STATUS: id=AndroidJUnitRunner' \
    'INSTRUMENTATION_STATUS: numtests=1' \
    'INSTRUMENTATION_STATUS: stream=.' \
    "INSTRUMENTATION_STATUS: test=$method" \
    'INSTRUMENTATION_STATUS_CODE: 0' \
    'INSTRUMENTATION_RESULT: stream=' \
    '' 'Time: 0.011' '' 'OK (1 test)' '' '' \
    'INSTRUMENTATION_CODE: -1'
}

assertion_failure() {
  # Same failure framing as the captured restoreSnapshot failure; only the assertion text is
  # the fixture's intended migration RED assertion.
  printf '%s\n' \
    'INSTRUMENTATION_STATUS: class=com.bydmate.app.voice.PolishOnlyUpgradeTest' \
    'INSTRUMENTATION_STATUS: current=1' \
    'INSTRUMENTATION_STATUS: id=AndroidJUnitRunner' \
    'INSTRUMENTATION_STATUS: numtests=1' \
    'INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: startup migration must persist Polish' \
    'INSTRUMENTATION_STATUS: stream=' \
    'Error in assertMigrated(com.bydmate.app.voice.PolishOnlyUpgradeTest):' \
    'java.lang.AssertionError: startup migration must persist Polish' \
    'INSTRUMENTATION_STATUS: test=assertMigrated' \
    'INSTRUMENTATION_STATUS_CODE: -2' \
    'INSTRUMENTATION_RESULT: stream=' \
    '' 'Time: 0.007' 'There was 1 failure:' \
    '1) assertMigrated(com.bydmate.app.voice.PolishOnlyUpgradeTest)' \
    'java.lang.AssertionError: startup migration must persist Polish' \
    '' 'FAILURES!!!' 'Tests run: 1,  Failures: 1' '' '' \
    'INSTRUMENTATION_CODE: -1'
}

runner_failure() {
  # A transport failure is represented by adb's real non-zero exit status, not invented runner
  # output. The helper must not mistake it for a completed assertion failure.
  exit 1
}

if [[ " $* " == *' install -r '* ]]; then
  last=${!#}
  if [[ "$FAKE_ADB_SCENARIO" == install-interruption && "$last" == *candidate.apk ]]; then
    echo 'INSTALL_FAILED_INTERRUPTED' >&2
    exit 1
  fi
  echo Success
  exit 0
fi

if [[ " $* " == *' force-stop '* ]]; then
  exit 0
fi

case "$*" in
  *'#snapshotAndSeed'*)
    if [[ "$FAKE_ADB_SCENARIO" == seed-failure ]]; then runner_failure; else success snapshotAndSeed; fi
    ;;
  *'#assertMigrated'*) assertion_failure ;;
  *'#restoreSnapshot'*)
    if [[ "$FAKE_ADB_SCENARIO" == restore-disconnect ]]; then runner_failure; else success restoreSnapshot; fi
    ;;
  *) echo "unexpected fake adb call: $*" >&2; exit 2 ;;
esac
EOF
chmod +x "$fake_adb"

run_helper() {
  local scenario=$1
  FAKE_ADB_LOG="$fake_log" FAKE_ADB_SCENARIO="$scenario" ADB="$fake_adb" \
    bash "$helper" --serial fake --baseline "$baseline" --candidate "$candidate" --test-apk "$test_apk" --expect-red
}

expect_success() {
  local scenario=$1 output
  : > "$fake_log"
  if ! output=$(run_helper "$scenario" 2>&1); then
    echo "expected helper success for $scenario, got:" >&2
    echo "$output" >&2
    exit 1
  fi
}

expect_failure() {
  local scenario=$1 output
  : > "$fake_log"
  if output=$(run_helper "$scenario" 2>&1); then
    echo "expected helper failure for $scenario" >&2
    exit 1
  fi
}

# am instrument itself exits 0 for an assertion failure; --expect-red must accept only the
# parsed test failure, not treat it as a passed instrumentation run.
expect_success assertion-failure-exit-zero

# A failed seed can have written its durable snapshot. It must restore before stopping and must
# never run the candidate assertion.
expect_failure seed-failure
grep -q '#restoreSnapshot' "$fake_log"
if grep -q '#assertMigrated' "$fake_log"; then
  echo 'assertion ran after failed seed' >&2
  exit 1
fi

# A failed restore is terminal: do not seed another legacy case.
expect_failure restore-disconnect
grep -q '#restoreSnapshot' "$fake_log"
if grep -q 'legacyCase ru' "$fake_log"; then
  echo 'helper continued after failed restore' >&2
  exit 1
fi

# Candidate install happens only after baseline snapshot+seed. An interruption must still invoke
# restoration via the host EXIT trap.
expect_failure install-interruption
grep -q '#snapshotAndSeed' "$fake_log"
grep -q '#restoreSnapshot' "$fake_log"

echo 'PASS: fake-adb upgrade helper checks'
