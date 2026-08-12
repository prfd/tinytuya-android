# Working from WSL instructions

The ideal way to work with Android Studio from WSL is to treat the Windows Android SDK, JDK, and Gradle environment as authoritative.
Run Gradle from the repository root with:

  ```bash
  cmd.exe /d /c gradlew.bat <tasks> -q --warning-mode=none --console=plain
  ```