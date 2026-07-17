# AutoPilot Android

Android platform runner for the AutoPilot declarative GUI test framework.

Runs the same JSON plan format used by [`autopilot-macos`](https://github.com/TestingAutoPilot/autopilot-macos) and [`autopilot-ios`](https://github.com/TestingAutoPilot/autopilot-ios). Plans are human-readable JSON, but designed to be authored by AI agents — connect an agent to the AutoPilot MCP server, describe what you want tested, and it produces a ready-to-run plan.

## What's here

```
autopilot-android/
  app/src/
    main/                        ← TestHostApp (36-element surface)
      java/com/autopilot/testhostapp/
        MainActivity.kt          ← all 36 elements wired up
        FileTableAdapter.kt      ← RecyclerView adapter for file table rows
      res/
        layout/activity_main.xml
        menu/menu_main.xml       ← Toggle Flag options menu
    androidTest/                 ← Instrumented test runner
      java/com/autopilot/testhostapp/
        PlanModel.kt             ← data classes for JSON plan
        AutoPilotRunner.kt       ← step executor (UiAutomator2)
        AutoPilotRunnerTest.kt   ← @RunWith(AndroidJUnit4) entry point
      assets/
        test-all-capabilities.json  ← unified 78-step plan
```

## Setup

Requires Android SDK and a connected device or running AVD (API 26+).

```bash
git clone https://github.com/TestingAutoPilot/autopilot-android.git
cd autopilot-android
```

The Gradle wrapper is included — no local Gradle installation needed.

## Build

> **JDK requirement.** Run Gradle with **JDK 17 or 21**. The bundled Gradle (8.9)
> cannot parse a newer system JDK (e.g. JDK 26) and fails early with a cryptic
> version error (`26.0.1`). The build pins a JVM toolchain to compile against
> JDK 17, but that does not control the JVM that *launches* Gradle. If your
> default `java` is newer than 21, point Gradle at a 17/21 JDK, e.g. on macOS
> with Android Studio installed:
>
> ```bash
> ./gradlew :app:assembleDebug \
>   -Dorg.gradle.java.home="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
> ```
>
> (Or set `org.gradle.java.home` in `~/.gradle/gradle.properties`.)

```bash
./gradlew :app:assembleDebug
```

## Run the tests

```bash
./gradlew :app:connectedDebugAndroidTest
```

Requires a connected Android device or running AVD. Results appear in `app/build/outputs/androidTest-results/`.

Or install and run via adb directly:

```bash
./gradlew :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w \
  com.autopilot.testhostapp.test/androidx.test.runner.AndroidJUnitRunner
```

## Run on a physical device (USB or wireless)

The runner is device-agnostic — it drives whatever device ADB points at — so a
real phone works with the same instrumentation. `scripts/run-on-device.sh` wraps
device selection, install, and the run. It expects **JDK 17/21** for Gradle (see
the Build note; export `ORG_GRADLE_JAVA_HOME` if your default `java` is newer).

Selection is explicit-optional: pass `--serial` to target a device, otherwise the
sole connected device is used (zero or many → it errors and prints `adb devices`).

### Over USB

Enable **Developer options → USB debugging**, plug the phone in, accept the RSA
"Allow USB debugging?" prompt (one-time), then:

```bash
bash scripts/run-on-device.sh                 # the sole connected device
bash scripts/run-on-device.sh --serial ABC123 # a specific device
```

### Wireless — the easy way (`--wireless`)

For a phone that has already been paired over USB at least once, skip the whole
pairing-code dance. Plug it in via USB and:

```bash
bash scripts/run-on-device.sh --wireless <serial>
```

This turns on Wireless Debugging over adb (no Settings, no pairing code), waits for
the phone to advertise itself over mDNS, resolves the wireless connection, and
prints `you can UNPLUG the USB cable now`. It then runs wirelessly. Because the USB
pairing trust carries over, no 6-digit code is needed. Wireless Debugging resets on
reboot, so run `--wireless` once per reboot; within the same boot, later runs
auto-reconnect over mDNS with no flags (the script sets `ADB_MDNS_AUTO_CONNECT`).

Requirements: Android 11+, and the phone + Mac on the same Wi-Fi network.

### Wireless — manual pairing (first-time, or if `--wireless` can't reach mDNS)

If the phone has never been USB-paired (or mDNS is blocked on your network), use
the OS pairing flow: **Developer options → Wireless debugging → Pair device with
pairing code**. That dialog shows an `IP:PAIRING_PORT` and a 6-digit code; the main
Wireless-debugging screen shows a *different* `IP:CONNECT_PORT` at the top.

```bash
# one-time pairing (uses the pairing port + code from the dialog)
bash scripts/run-on-device.sh --pair 192.168.1.5:41234 481500

# connect + run (uses the connect port from the main screen)
bash scripts/run-on-device.sh --connect 192.168.1.5:39000
```

The script prints `Using serial: …` so you can confirm exactly which device ran.

### Driving an external app

Point the runner at any installed package with an external plan (its
`target.bundleId` is used, or override with `--target`):

```bash
bash scripts/run-on-device.sh --connect 192.168.1.5:39000 \
  --plan ./my-plan.json --target com.some.installedapp
```

Expected result on a real device is the same as the emulator: **75 PASS + 3 SKIP**
(the 3 visual actions have no pixel access in an instrumented test).

### Device hygiene for real-hardware runs

A physical phone is a busy environment, and anything that draws over other apps
or grabs focus will disrupt a run. Before running, on the test device:

- **Close overlay apps** that use "draw over other apps" (`SYSTEM_ALERT_WINDOW`) —
  Facebook Messenger **chat heads** are the classic offender (a floating bubble
  that steals focus from the app under test). Bubbles, screen recorders, and
  floating-widget apps do the same. Force-stop them or revoke their display-over
  permission.
- Prefer a **clean/dedicated test device** with a stock launcher and few
  background apps. Heavily-personalized phones (many notifications, OEM edge
  panels, always-on assistants) are more likely to interfere.
- The runner defends itself: it re-fronts the app before each step and **fails
  fast** (instead of scroll-retrying for minutes) when the app under test is not
  the foreground app — so a focus steal produces a quick, legible failure rather
  than a hang. But it cannot dismiss an arbitrary third-party overlay for you.

## Results

The unified 78-step plan achieves **75 PASS + 3 SKIP** on Android. The 3 skipped steps require screen capture APIs not available in instrumented tests.

| Action | Status | Reason |
|---|---|---|
| `assertPixel` | SKIP | Pixel-level screen capture not available via UiAutomator2 |
| `assertRegion` | SKIP | Same |
| `snapshot` | SKIP | Same |

All other actions pass.

## Key implementation notes

- **Content descriptions** are the primary element locator — every view has `android:contentDescription` matching the plan identifier exactly.
- Focus for text input is transferred via `MainActivity.requestFocusOnField()` called on the main thread, bypassing coordinate-based tap issues near the status bar.
- Double-tap is simulated via `MainActivity.simulateDoubleTap()` on the main thread, avoiding touch-event timing sensitivity.
- `scroll-end` visibility is asserted after `MainActivity.scrollInnerScrollViewToEnd()` programmatically scrolls the inner ScrollView — UiAutomator2 only surfaces views physically within the visible viewport.
- `rightClickTarget` uses `setOnLongClickListener` + `PopupMenu`.
- Alert dialog buttons are found by text (`"Confirm"`, `"Cancel"`) as fallback when identifier-based lookup fails on system dialog views.
- The `toggleFlag` menu item lives in `res/menu/menu_main.xml` and is opened via `device.pressMenu()`.

## Core dependency

This runner implements the AutoPilot plan format defined by [`autopilot-core`](https://github.com/TestingAutoPilot/autopilot-core). The plan model (`PlanModel.kt`) mirrors the core schema. `autopilot-core` is a Swift package; the Android runner re-implements the same model in Kotlin.

## Cross-platform

The same JSON plan format runs across platforms:

| Platform | Repo | Result |
|---|---|---|
| macOS | [`autopilot-macos`](https://github.com/TestingAutoPilot/autopilot-macos) | 78 PASS (supports the 3 visual steps) |
| iOS | [`autopilot-ios`](https://github.com/TestingAutoPilot/autopilot-ios) | 75 PASS + 3 SKIP |
| Android | this repo | 75 PASS + 3 SKIP |

## Requirements

- Android SDK compileSdk 36 / minSdk 26
- Kotlin 2.0 / JVM 17
- Gradle 8.9 (wrapper included)
- Connected Android device or AVD running API 26+

## License

MIT
