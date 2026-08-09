# Repository instructions

These instructions apply to the entire repository.

## Toolchain and device

- Work from WSL, but treat the Windows Android SDK, JDK, and Gradle environment as authoritative.
- Run Gradle from the repository root with:

  ```bash
  /mnt/c/Windows/System32/cmd.exe /d /c gradlew.bat <tasks> -q --warning-mode=none --console=plain
  ```

- To use Android SDK tools (adb, etc.), invoke them from the SDK path derived from `sdk.dir` in untracked `local.properties`.
- Never edit or commit `local.properties`, SDK paths, signing material, credentials, device identifiers, Tuya keys, or other machine-local or secret data.

## Git workflow

- Inspect `git status` and relevant diffs before and after changes. Preserve unrelated work in a dirty tree.
- Commit completed, verified work in focused commits using `type: description`, staging only task files. Do not amend, rewrite, squash, or reset history unless asked.
- Never commit generated output, device captures, secrets, local configuration, or unrelated IDE churn.
- Before commiting your work, format your code with ktfmt: `/mnt/c/Windows/System32/cmd.exe /d /c gradlew.bat ktfmtFormatAndroidTest ktfmtFormatMain ktfmtFormatTest -q`

## Verification

- Use judgment: not every feature or change needs a new test. Skip tests for changes too simple to justify one; add or update tests for meaningful behavior, regressions, risky logic, and contracts.
- Prefer narrow host JVM tests for pure Kotlin logic. Use instrumentation tests when Android, Compose, lifecycle, Keystore, persistence, networking, or Kotlin/Python integration behavior warrants them.
- Run relevant checks through Windows Gradle. For substantial changes, normally run:

  ```bash
  /mnt/c/Windows/System32/cmd.exe /d /c gradlew.bat testDebugUnitTest connectedDebugAndroidTest lint assembleDebug installDebug -q --warning-mode=none --console=plain
  ```

- Assume the device is connected, but do not claim a test, physical-device check, or real-Tuya validation unless it actually ran. Report unavailable accounts, networks, or hardware clearly.

## Project guardrails

- Read `SOURCE_GUIDE.md` for progressive code reading.