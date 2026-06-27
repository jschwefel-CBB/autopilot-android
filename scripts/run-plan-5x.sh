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
# OutlinedTextField-in-AlertDialog find-after-type case (the ScopeDOPE shape) —
# included so that Compose-specific behavior is caught HERE, not on a downstream
# real-app run.
CLASSES="com.autopilot.testhostapp.AutoPilotRunnerTest,com.autopilot.testhostapp.ComposeFixtureTest"

./gradlew :app:installDebug :app:installDebugAndroidTest

for i in $(seq 1 "$RUNS"); do
  echo "::group::Instrumentation run ${i}/${RUNS}"
  OUT="$(adb shell am instrument -w -e class "$CLASSES" "$RUNNER" 2>&1)"
  echo "$OUT"
  echo "::endgroup::"
  # am instrument prints "OK (N tests)" only when ALL tests in the run pass; any
  # failure prints "FAILURES!!!" / "Process crashed" instead.
  if ! echo "$OUT" | grep -qE 'OK \([0-9]+ test'; then
    echo "::error::Instrumentation FAILED on run ${i}/${RUNS} (intermittent flake or regression)"
    exit 1
  fi
done

echo "All instrumentation passed ${RUNS}/${RUNS} consecutive runs."
