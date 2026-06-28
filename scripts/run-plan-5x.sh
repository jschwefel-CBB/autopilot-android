#!/usr/bin/env bash
# Reliability gate: install the app + androidTest APKs once, then run the unified
# plan instrumentation N times. Fail if ANY run fails. A single run hides
# intermittent/timing flakes (a step failing ~half the time still passes a
# one-shot run often enough to go green); N-in-a-row is the signal an external
# consumer needs. Lives as a committed script so the loop is not subject to the
# CI action's line-by-line shell execution (which broke an inline for-loop).
set -euo pipefail

RUNS="${1:-5}"
RUNNER="com.autopilot.testhostapp.test/androidx.test.runner.AndroidJUnitRunner"

# Test classes under the reliability gate. AutoPilotRunnerTest = the 78-step
# unified plan (classic-View TestHostApp). ComposeFixtureTest = the Compose
# OutlinedTextField-in-AlertDialog find-after-type case (the real-app shape) —
# included so that Compose-specific behavior is caught HERE, not on a downstream
# real-app run.
CLASSES="com.autopilot.testhostapp.AutoPilotRunnerTest,com.autopilot.testhostapp.ComposeFixtureTest"

./gradlew :app:installDebug :app:installDebugAndroidTest

for i in $(seq 1 "$RUNS"); do
  echo "::group::Instrumentation run ${i}/${RUNS}"
  adb logcat -c || true   # clear so this run's runner logs are isolated
  OUT="$(adb shell am instrument -w -e class "$CLASSES" "$RUNNER" 2>&1)"
  echo "$OUT"
  echo "::endgroup::"
  # am instrument prints "OK (N tests)" only when ALL tests in the run pass; any
  # failure prints "FAILURES!!!" / "Process crashed" instead.
  if ! echo "$OUT" | grep -qE 'OK \([0-9]+ test'; then
    # Surface the runner's step results + FIND-FAIL-DUMP (logged to logcat, NOT in
    # am-instrument stdout) so a failure here yields the decisive diagnostic data.
    echo "::group::AutoPilotRunner logcat (run ${i}) — step results + FIND-FAIL-DUMP"
    adb logcat -d 2>/dev/null | grep -E 'AutoPilotRunner' | tail -200 || true
    echo "::endgroup::"
    echo "::group::Hierarchy dumps written by the runner (run ${i})"
    for pkg in com.autopilot.testhostapp.test com.autopilot.testhostapp; do
      dir="/sdcard/Android/data/${pkg}/cache"
      adb shell "ls -1 ${dir}/find-fail-*.xml 2>/dev/null" | while read -r f; do
        [ -n "$f" ] || continue
        echo "----- $f -----"
        adb shell "cat '$f'" 2>/dev/null || true
      done
    done
    echo "::endgroup::"
    echo "::error::Instrumentation FAILED on run ${i}/${RUNS} (intermittent flake or regression)"
    exit 1
  fi
done

echo "All instrumentation passed ${RUNS}/${RUNS} consecutive runs."
