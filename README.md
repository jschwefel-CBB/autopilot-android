# AutoPilot Android

Android platform runner for the AutoPilot declarative GUI test framework.

## Project structure

```
autopilot-android/
  app/src/
    main/                      ← TestHostApp (36-element surface)
      java/com/autopilot/testhostapp/
        MainActivity.kt        ← all 36 elements wired up
        FileTableAdapter.kt    ← RecyclerView adapter for file table rows
      res/
        layout/activity_main.xml
        menu/menu_main.xml     ← Toggle Flag options menu
        values/strings.xml
        values/colors.xml
    androidTest/               ← Instrumented test runner
      java/com/autopilot/testhostapp/
        PlanModel.kt           ← data classes for JSON plan
        AutoPilotRunner.kt     ← step executor (Espresso + UI Automator)
        AutoPilotRunnerTest.kt ← @RunWith(AndroidJUnit4) entry point
      assets/
        test-all-capabilities.json  ← copy of the unified plan (see below)
```

## First-time setup

The Gradle wrapper binary (`gradle-wrapper.jar`) is not committed. Generate it once:

```sh
# Requires Gradle 8.9 installed locally (via SDKMAN, Homebrew, etc.)
cd /Users/jschwefel/repositories/autopilot-android
gradle wrapper --gradle-version 8.9
```

After that, use `./gradlew` for all subsequent commands.

## Build the TestHostApp

```sh
./gradlew :app:assembleDebug
```

## Run the instrumented tests (requires connected device or emulator)

```sh
# Run all AutoPilot tests
./gradlew :app:connectedAndroidTest

# Or install + run via adb directly after assembling the test APK
./gradlew :app:assembleDebugAndroidTest
adb shell am instrument -w \
  com.autopilot.testhostapp.test/androidx.test.runner.AndroidJUnitRunner
```

Results appear in `app/build/outputs/androidTest-results/`.

## Plan JSON

`app/src/androidTest/assets/test-all-capabilities.json` is a **copy** of:

```
../autopilot/Fixtures/TestHostApp/test-all-capabilities.json
```

Keep them in sync whenever the unified plan changes. The runner reads the asset
file at test runtime via `InstrumentationRegistry.getInstrumentation().context.assets`.

## How the runner works

1. `AutoPilotRunnerTest.runUnifiedPlan()` calls `AutoPilotRunner.run()`
2. `AutoPilotRunner` loads the plan JSON, then iterates every step
3. Each `action` maps to a UI Automator / Espresso call (see `AutoPilotRunner.kt`)
4. Steps that are macOS-specific (`assertPixel`, `assertRegion`, `snapshot`) are
   skipped with a log message — they do not count as failures
5. After all steps complete, any non-skipped failures are collected and the test
   fails with the list of failing step IDs

## Key implementation notes

- **Content descriptions** are the primary element locator — every view has
  `android:contentDescription` matching the plan identifier exactly
- `uploadProgress` exposes its value (`"0.5"` / `"1.0"`) via `AccessibilityDelegateCompat`
  overriding `onInitializeAccessibilityNodeInfo` → `info.text`
- `flagCheckbox` value is `"1"` (checked) / `"0"` (unchecked) to match the macOS
  plan convention (`"1"` maps to `NSControlStateValueOn`)
- `dblButton` uses `GestureDetector.onDoubleTap` — the runner simulates a double-tap
  by sending two rapid `.click()` calls with a 50 ms gap
- `rightClickTarget` uses `setOnLongClickListener` + `PopupMenu`
- Alert dialog buttons are found by text (`"Confirm"`, `"Cancel"`) as fallback when
  the identifier-based lookup fails on system dialog views
- The `toggleFlag` menu item lives in `res/menu/menu_main.xml` and is toggled via
  `onOptionsItemSelected`; the runner opens it with `device.pressMenu()`

## Minimum requirements

- Android SDK compileSdk 36 / minSdk 26
- Kotlin 2.0 / JVM 17
- Gradle 8.9 (via wrapper after first-time setup above)
- A connected Android device or AVD running API 26+
