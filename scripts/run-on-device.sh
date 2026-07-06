#!/usr/bin/env bash
# Drive the AutoPilot plan on a PHYSICAL Android device (USB or wireless).
#
# The runner code is device-agnostic: AutoPilotRunner uses UiDevice.getInstance()
# and never names a serial, so choosing WHICH device is driven is purely an
# `adb -s <serial>` concern. This wrapper owns that: it (optionally) sets up a
# wireless connection, resolves the target serial (explicit or the sole device),
# installs the two APKs to that device, and runs the instrumentation there.
#
# No Kotlin/Gradle change — this is pure host-side tooling. Same 78-step plan,
# same expected result as the emulator: 75 PASS + 3 SKIP (the 3 visual actions
# have no pixel access in an instrumented test).
#
# Selection is explicit-optional: pass --serial to target a device; otherwise the
# sole connected device is used, and zero-or-many is an error with an inventory.
#
# Examples:
#   bash scripts/run-on-device.sh                         # sole USB device, bundled plan
#   bash scripts/run-on-device.sh --serial ABC123         # a specific device
#   bash scripts/run-on-device.sh --wireless ABC123       # EASY wireless: enable it
#        over USB via mDNS (no pairing code / Settings), then unplug. Once per reboot.
#   bash scripts/run-on-device.sh --pair 192.168.1.5:41234 481500   # manual wireless pair
#   bash scripts/run-on-device.sh --connect 192.168.1.5:39000       # manual connect by IP
#   bash scripts/run-on-device.sh --connect 192.168.1.5:39000 \
#        --plan ./my-plan.json --target com.some.installedapp        # external app
#
# JDK note: the bundled Gradle 8.9 must be LAUNCHED by JDK 17 or 21. If your
# default `java` is newer, export ORG_GRADLE_JAVA_HOME to a 17/21 JDK, e.g.
#   export ORG_GRADLE_JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
set -euo pipefail
cd "$(dirname "$0")/.."

# Let adb auto-connect to any device advertising itself over mDNS (Android 11+
# Wireless Debugging), so an already-wireless phone is found with no --connect / IP.
export ADB_MDNS_AUTO_CONNECT="${ADB_MDNS_AUTO_CONNECT:-adb-tls-connect}"

RUNNER="com.autopilot.testhostapp.test/androidx.test.runner.AndroidJUnitRunner"
SERIAL=""
CONNECT=""
PAIR_ADDR=""
PAIR_CODE=""
WIRELESS_USB=""
PLAN=""
TARGET=""
NO_INSTALL=0

usage() {
  sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --serial)  SERIAL="${2:?--serial needs a value}"; shift 2 ;;
    --connect) CONNECT="${2:?--connect needs ip:port}"; shift 2 ;;
    --pair)    PAIR_ADDR="${2:?--pair needs ip:port}"; PAIR_CODE="${3:?--pair needs a code}"; shift 3 ;;
    --wireless) WIRELESS_USB="${2:?--wireless needs the USB serial}"; shift 2 ;;
    --plan)    PLAN="${2:?--plan needs a path}"; shift 2 ;;
    --target)  TARGET="${2:?--target needs a package}"; shift 2 ;;
    --no-install) NO_INSTALL=1; shift ;;
    -h|--help) usage 0 ;;
    *) echo "Unknown argument: $1" >&2; usage 1 ;;
  esac
done

# --- One-command wireless via mDNS (for an ALREADY-USB-paired phone) ----------
# --wireless <usb-serial>: while the phone is on USB, turn Wireless Debugging on
# over adb (no Settings dance) and let mDNS advertise a connectable service — the
# USB TLS-pairing trust carries over, so no pairing code is needed. Resolves the
# advertised _adb-tls-connect service as the wireless serial; you can then unplug.
# Wireless Debugging resets on reboot, so re-run --wireless once per reboot.
if [ -n "$WIRELESS_USB" ]; then
  echo "==> Enabling Wireless Debugging on $WIRELESS_USB (via USB)…" >&2
  adb -s "$WIRELESS_USB" shell settings put global adb_wifi_enabled 1 >/dev/null 2>&1 || true
  export ADB_MDNS_AUTO_CONNECT=adb-tls-connect
  # Wait (up to ~15s) for THIS phone to advertise its wireless service over mDNS.
  # The advertised name embeds the USB serial (adb-<SERIAL>-xxxx._adb-tls-connect
  # ._tcp), so match on it — otherwise, with several phones wireless, we'd grab the
  # wrong one.
  MDNS_SERIAL=""
  i=0
  while [ "$i" -lt 15 ]; do
    MDNS_SERIAL="$(adb devices 2>/dev/null \
      | awk '/_adb-tls-connect._tcp[[:space:]]+device/{print $1}' \
      | grep -- "-${WIRELESS_USB}-" | head -1)"
    [ -n "$MDNS_SERIAL" ] && break
    sleep 1; i=$((i + 1))
  done
  if [ -n "$MDNS_SERIAL" ]; then
    SERIAL="$MDNS_SERIAL"
    echo "==> Wireless: $SERIAL — you can UNPLUG the USB cable now." >&2
  else
    echo "Could not detect the wireless (mDNS) service within 15s. Is the phone on" >&2
    echo "the same Wi-Fi as this Mac? Falling back to the USB serial $WIRELESS_USB." >&2
    SERIAL="$WIRELESS_USB"
  fi
fi

# --- Wireless setup (optional) ------------------------------------------------
# First-time wireless pairing (Android 11+). The PAIRING port shown in the
# "Pair device with pairing code" dialog is NOT the connect port; after pairing
# you still --connect the connect port shown atop the Wireless-debugging screen.
if [ -n "$PAIR_ADDR" ]; then
  echo "==> Pairing with $PAIR_ADDR" >&2
  adb pair "$PAIR_ADDR" "$PAIR_CODE"
fi
if [ -n "$CONNECT" ]; then
  echo "==> Connecting to $CONNECT" >&2
  adb connect "$CONNECT"
  # A wireless device is addressed by its ip:port serial.
  [ -n "$SERIAL" ] || SERIAL="$CONNECT"
fi

# --- Resolve the target serial ------------------------------------------------
if [ -z "$SERIAL" ]; then
  # Devices in the "device" state (skip "offline"/"unauthorized"). Read without
  # `mapfile` so this works on the stock macOS bash 3.2.
  DEVICES=""
  DEVICE_COUNT=0
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    DEVICES="$DEVICES$line"$'\n'
    DEVICE_COUNT=$((DEVICE_COUNT + 1))
  done < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
  case "$DEVICE_COUNT" in
    1) SERIAL="$(printf '%s' "$DEVICES" | head -n1)" ;;
    0) echo "No connected device in the 'device' state. \`adb devices\`:" >&2
       adb devices >&2
       echo "Plug in a phone (accept the RSA prompt) or use --pair/--connect for wireless." >&2
       exit 1 ;;
    *) echo "Multiple devices connected; pass --serial <serial>. \`adb devices\`:" >&2
       adb devices >&2
       exit 1 ;;
  esac
fi
echo "==> Using serial: $SERIAL" >&2

# --- Build + install (unless --no-install) ------------------------------------
GRADLE_JAVA_HOME_FLAG=()
[ -n "${ORG_GRADLE_JAVA_HOME:-}" ] && GRADLE_JAVA_HOME_FLAG=(-Dorg.gradle.java.home="$ORG_GRADLE_JAVA_HOME")

if [ "$NO_INSTALL" -eq 0 ]; then
  echo "==> Building app + androidTest APKs" >&2
  # ${arr[@]+"${arr[@]}"} expands to nothing when the array is empty — safe under
  # `set -u` on the stock macOS bash 3.2 (a bare "${arr[@]}" would abort there).
  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest ${GRADLE_JAVA_HOME_FLAG[@]+"${GRADLE_JAVA_HOME_FLAG[@]}"}
  APP_APK="app/build/outputs/apk/debug/app-debug.apk"
  TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
  echo "==> Installing to $SERIAL" >&2
  adb -s "$SERIAL" install -r "$APP_APK"
  adb -s "$SERIAL" install -r "$TEST_APK"
fi

# --- Run the instrumentation --------------------------------------------------
adb -s "$SERIAL" logcat -c || true   # isolate this run's runner logs

if [ -n "$PLAN" ]; then
  echo "==> Pushing external plan $PLAN → /data/local/tmp/plan.json" >&2
  adb -s "$SERIAL" push "$PLAN" /data/local/tmp/plan.json
  TARGET_ARG=()
  [ -n "$TARGET" ] && TARGET_ARG=(-e target "$TARGET")
  OUT="$(adb -s "$SERIAL" shell am instrument -w \
    -e class com.autopilot.testhostapp.ExternalPlanTest \
    -e plan /data/local/tmp/plan.json \
    ${TARGET_ARG[@]+"${TARGET_ARG[@]}"} \
    "$RUNNER" 2>&1)"
else
  OUT="$(adb -s "$SERIAL" shell am instrument -w \
    -e class com.autopilot.testhostapp.AutoPilotRunnerTest \
    "$RUNNER" 2>&1)"
fi
echo "$OUT"

# --- Verify -------------------------------------------------------------------
# am instrument prints "OK (N tests)" only when every test in the run passed.
if echo "$OUT" | grep -qE 'OK \([0-9]+ test'; then
  echo "==> Per-step results ($SERIAL):" >&2
  adb -s "$SERIAL" logcat -d 2>/dev/null | grep 'AutoPilotRunner' | tail -100 || true
  echo "==> PASSED on $SERIAL" >&2
  exit 0
else
  echo "==> Instrumentation FAILED on $SERIAL — runner log:" >&2
  adb -s "$SERIAL" logcat -d 2>/dev/null | grep 'AutoPilotRunner' | tail -200 || true
  exit 1
fi
