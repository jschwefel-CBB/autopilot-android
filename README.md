# AutoPilot Android

Android platform runner for the AutoPilot declarative GUI test framework.

Runs the same JSON plan format used by [`autopilot-macos`](https://github.com/jschwefel-CBB/autopilot-macos) and [`autopilot-ios`](https://github.com/jschwefel-CBB/autopilot-ios). Plans are human-readable JSON, but designed to be authored by AI agents — connect an agent to the AutoPilot MCP server, describe what you want tested, and it produces a ready-to-run plan.

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
git clone https://github.com/jschwefel-CBB/autopilot-android.git
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

This runner implements the AutoPilot plan format defined by [`autopilot-core`](https://github.com/jschwefel-CBB/autopilot-core). The plan model (`PlanModel.kt`) mirrors the core schema. `autopilot-core` is a Swift package; the Android runner re-implements the same model in Kotlin.

## Cross-platform

The same JSON plan format runs across platforms:

| Platform | Repo | Result |
|---|---|---|
| macOS | [`autopilot-macos`](https://github.com/jschwefel-CBB/autopilot-macos) | 78 PASS (supports the 3 visual steps) |
| iOS | [`autopilot-ios`](https://github.com/jschwefel-CBB/autopilot-ios) | 75 PASS + 3 SKIP |
| Android | this repo | 75 PASS + 3 SKIP |

## Requirements

- Android SDK compileSdk 36 / minSdk 26
- Kotlin 2.0 / JVM 17
- Gradle 8.9 (wrapper included)
- Connected Android device or AVD running API 26+

## License

MIT
