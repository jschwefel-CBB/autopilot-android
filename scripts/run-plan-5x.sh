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

./gradlew :app:installDebug :app:installDebugAndroidTest

for i in $(seq 1 "$RUNS"); do
  echo "::group::Unified plan run ${i}/${RUNS}"
  OUT="$(adb shell am instrument -w \
    -e class com.autopilot.testhostapp.AutoPilotRunnerTest "$RUNNER" 2>&1)"
  echo "$OUT"
  echo "::endgroup::"
  if ! echo "$OUT" | grep -q 'OK ('; then
    echo "::error::Unified plan FAILED on run ${i}/${RUNS} (intermittent flake or regression)"
    exit 1
  fi
done

echo "Unified plan passed ${RUNS}/${RUNS} consecutive runs."
