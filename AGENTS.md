# Repository instructions

These instructions apply to the entire repository.

## Environment and toolchain

- The agent shell runs in WSL on Ubuntu 24.04, while Android Studio, the Android SDK, the JDK, and the primary Gradle environment live on Windows.
- Treat the Windows toolchain as authoritative for builds and device operations. A command failing under Linux does not establish that the Android project is broken.
- Run Gradle from the repository root through Windows Command Prompt:

  ```bash
  /mnt/c/Windows/System32/cmd.exe /d /c gradlew.bat <tasks>
  ```

- Use the Windows Android SDK's `adb.exe` for physical-device work. On the current development machine it is available at:

  ```bash
  /mnt/c/Users/paulo/AppData/Local/Android/Sdk/platform-tools/adb.exe
  ```

  If that path changes, read `sdk.dir` from the untracked `local.properties` and translate the Windows SDK path to its `/mnt/<drive>/...` WSL form.
- Quote paths passed between WSL and Windows carefully. Prefer `cmd.exe /d /c gradlew.bat ...` from the repository root so Gradle resolves the checkout consistently.
- Do not edit or commit `local.properties`, SDK paths, signing keys, credentials, device identifiers, Tuya keys, or other machine-local/secrets-bearing files.

## Git workflow

- Use Git confidently. Inspect `git status` and the relevant diff before and after making changes.
- Commit completed, verified work in focused commits with clear imperative messages. Do not leave a finished implementation uncommitted unless the user asks you to.
- Preserve unrelated user changes in a dirty worktree. Stage and commit only files that belong to the current task.
- Do not rewrite, amend, squash, reset, or otherwise alter existing history unless the user explicitly requests it.
- Never commit generated build output, captured device data, secrets, local configuration, or IDE churn unrelated to the task.

## Testing and verification

- Tests are part of the implementation, not optional follow-up. Add or update tests for behavior changes and regressions.
- Use host JVM tests for pure Kotlin logic and fast feedback. Run them through the Windows toolchain:

  ```bash
  /mnt/c/Windows/System32/cmd.exe /d /c gradlew.bat testDebugUnitTest
  ```

- Android behavior needs instrumentation coverage. Add Compose/instrumentation tests for UI, lifecycle, Android APIs, Keystore, persistence, networking coordination, and Kotlin/Python integration where applicable.
- Run connected instrumentation tests on a physical Android device for changes that affect Android behavior:

  ```bash
  /mnt/c/Windows/System32/cmd.exe /d /c gradlew.bat connectedDebugAndroidTest
  ```

- A connected test command may require an unlocked device and approval of the test APK installation. Check device availability with Windows `adb.exe` before diagnosing a Gradle failure as a code failure.
- Run the narrowest relevant tests while iterating, then proportionate final verification. For a substantial change, this normally includes host tests, connected instrumentation tests, lint, and APK assembly:

  ```bash
  /mnt/c/Windows/System32/cmd.exe /d /c gradlew.bat testDebugUnitTest connectedDebugAndroidTest lint assembleDebug
  ```

- Do not claim physical-device or real-Tuya validation unless it actually ran. If a device, account, network condition, or representative hardware is unavailable, report that limitation clearly and leave the corresponding plan item partially validated.
- Preserve the installed app's real encrypted catalog and settings unless a test explicitly requires a clean install or the user authorizes destructive device-state changes.

## Project invariants

- Read `SOURCE_GUIDE.md` for the progressive code-reading path.
- Keep runtime behavior local-first. Tuya Cloud access may occur only after an explicit user import or sync action.
- Never log, render, persist in plaintext, or return through errors any Client Secret, local key, token, request header, raw device payload, or Python traceback.
- Keep Python bridge inputs and work bounded. Blocking Python and network operations must stay off the Android main thread.
- Local control is fail-closed: only fresh, independently observed DPS backed by recognized mappings may become writable.
- Preserve the stable, versioned Kotlin/Python JSON contract or update both sides and their tests together.
