# Project

TinyTuya is an Android app to control Tuya devices over a local network, it's powered by the TinyTuya Python library using the Chaquopy SDK. 
Tuya Cloud is used only during an explicit import or sync to obtain device metadata, capability mappings, device IDs, and local keys.
Discovery, status polling, and control use the local network after setup.

# Repository instructions

These instructions apply to the entire repository.

## Toolchain and device

- If you don't know your current environment, try to read `.environment`.
- If you are on WSL, read `WSL.md`.
- To use Android SDK build-tools (adb, etc.), invoke them from the SDK path derived from `sdk.dir` in untracked `local.properties`.
- Never edit or commit `local.properties`, SDK paths, signing material, credentials, device identifiers.

## Workflow

- Inspect `git status` and relevant diffs before and after changes.
- After finishing your work, format your code: `./gradlew ktfmtFormatAll -q`

## Project guardrails

- Read `SOURCE_GUIDE.md` to get a grasp of the app architecture.
