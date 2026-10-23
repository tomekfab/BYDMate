#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 --serial SERIAL --baseline BASELINE.apk --candidate CANDIDATE.apk --test-apk TEST.apk [--expect-red]" >&2
  exit 64
}

serial=''
baseline=''
candidate=''
test_apk=''
expect_red=false
while (($#)); do
  case "$1" in
    --serial) serial=${2:-}; shift 2 ;;
    --baseline) baseline=${2:-}; shift 2 ;;
    --candidate) candidate=${2:-}; shift 2 ;;
    --test-apk) test_apk=${2:-}; shift 2 ;;
    --expect-red) expect_red=true; shift ;;
    *) usage ;;
  esac
done

[[ -n "$serial" && -f "$baseline" && -f "$candidate" && -f "$test_apk" ]] || usage
adb=${ADB:-/Users/tomaszkopycki/Library/Android/sdk/platform-tools/adb}
package=com.bydmate.app
runner=com.bydmate.app.test/androidx.test.runner.AndroidJUnitRunner
test_class=com.bydmate.app.voice.PolishOnlyUpgradeTest
cases=(missing ru unknown wrong-type)

instrumentation_outcome=''
instrumentation_output=''
restore_required=false
current_case=''

run_method() {
  local method=$1 case_name=$2 shell_status
  set +e
  instrumentation_output=$("$adb" -s "$serial" shell am instrument -w -r \
    -e class "$test_class#$method" \
    -e legacyCase "$case_name" \
    "$runner" 2>&1)
  shell_status=$?
  set -e
  printf '%s\n' "$instrumentation_output"

  if (( shell_status != 0 )); then
    instrumentation_outcome=transport_failure
    return 1
  fi
  # AndroidJUnitRunner reports -1 from `am instrument` even after a complete successful test.
  # Accept only the complete result shape captured from the baseline runner, not its shell code.
  if grep -qx 'INSTRUMENTATION_STATUS: test='"$method" <<<"$instrumentation_output" &&
    grep -qx 'INSTRUMENTATION_STATUS_CODE: 0' <<<"$instrumentation_output" &&
    grep -qx 'INSTRUMENTATION_RESULT: stream=' <<<"$instrumentation_output" &&
    grep -Eq '^OK \([1-9][0-9]* tests?\)$' <<<"$instrumentation_output" &&
    grep -qx 'INSTRUMENTATION_CODE: -1' <<<"$instrumentation_output" &&
    ! grep -Eq '^FAILURES!!!$|^Tests run: [0-9]+, +Failures: [1-9][0-9]*$|^Error in |^INSTRUMENTATION_STATUS: stack=' <<<"$instrumentation_output"; then
    instrumentation_outcome=ok
    return 0
  fi
  if grep -qx 'INSTRUMENTATION_RESULT: stream=' <<<"$instrumentation_output" &&
    grep -qx 'FAILURES!!!' <<<"$instrumentation_output" &&
    grep -Eq '^Tests run: [0-9]+, +Failures: [1-9][0-9]*$' <<<"$instrumentation_output" &&
    grep -qx 'INSTRUMENTATION_CODE: -1' <<<"$instrumentation_output"; then
    instrumentation_outcome=assertion_failure
  else
    instrumentation_outcome=runner_failure
  fi
  return 1
}

is_expected_migration_failure() {
  [[ "$instrumentation_outcome" == assertion_failure ]] &&
    grep -Eq 'startup migration must persist Polish|voice profile must resolve to Polish|legacy voice language type was not migrated safely|legacy TTS selection must migrate to Gosia|restored online TTS must not remain selectable|disabled voice must stay disabled|disabled TTS must stay disabled|OpenClaw opt-in must stay disabled|OpenClaw identity must remain byte-for-byte unchanged' <<<"$instrumentation_output"
}

install_apk() {
  "$adb" -s "$serial" install -r "$1"
}

restore_current_snapshot() {
  echo "== $current_case: restoreSnapshot =="
  if run_method restoreSnapshot "$current_case"; then
    restore_required=false
    return 0
  fi
  echo "restore failed for $current_case ($instrumentation_outcome)" >&2
  return 1
}

on_exit() {
  local status=$?
  trap - EXIT
  if [[ "$restore_required" == true ]]; then
    echo "== $current_case: restoreSnapshot (EXIT trap) ==" >&2
    if ! restore_current_snapshot; then
      echo "snapshot was retained because restoration did not complete" >&2
      status=1
    fi
  fi
  exit "$status"
}
trap on_exit EXIT

overall=0
for case_name in "${cases[@]}"; do
  current_case=$case_name
  echo "== $case_name: install baseline =="
  if ! install_apk "$baseline" || ! install_apk "$test_apk"; then
    echo "baseline install failed for $case_name" >&2
    overall=1
    break
  fi

  # Set this before seed: a seed can durably publish its snapshot before it fails.
  restore_required=true
  echo "== $case_name: snapshotAndSeed =="
  if ! run_method snapshotAndSeed "$case_name"; then
    echo "seed failed for $case_name ($instrumentation_outcome)" >&2
    overall=1
    if ! restore_current_snapshot; then
      break
    fi
    break
  fi

  if ! "$adb" -s "$serial" shell am force-stop "$package"; then
    echo "could not stop baseline after seed for $case_name" >&2
    overall=1
    break
  fi
  echo "== $case_name: install candidate =="
  if ! install_apk "$candidate" || ! install_apk "$test_apk"; then
    echo "candidate install failed for $case_name" >&2
    overall=1
    break
  fi
  if ! "$adb" -s "$serial" shell am force-stop "$package"; then
    echo "could not stop candidate before assertion for $case_name" >&2
    overall=1
    break
  fi

  echo "== $case_name: assertMigrated =="
  assertion_ok=false
  if run_method assertMigrated "$case_name"; then
    assertion_ok=true
  elif [[ "$expect_red" == true ]] && is_expected_migration_failure; then
    assertion_ok=true
  else
    echo "migration assertion failed for $case_name ($instrumentation_outcome)" >&2
  fi
  assertion_outcome=$instrumentation_outcome

  # Restore even when assertion failed; a failed restore is terminal and its snapshot remains.
  if ! restore_current_snapshot; then
    overall=1
    break
  fi

  if [[ "$expect_red" == true ]]; then
    if [[ "$assertion_outcome" == ok ]]; then
      echo "expected RED assertion failure for $case_name, but it passed" >&2
      overall=1
    elif [[ "$assertion_ok" != true ]]; then
      overall=1
    fi
  elif [[ "$assertion_ok" != true ]]; then
    overall=1
  fi
done

exit "$overall"
