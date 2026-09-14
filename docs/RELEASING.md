# Releasing TinyTuya Android

Releases are cut from `master`, built and signed locally, and published as GitHub Releases.

The canonical version lives in `gradle.properties` (`tinytuya.version`, `tinytuya.versionCode`).
`versionName` resolves from git at build time: exactly on a matching `v<version>` tag → `X.Y.Z`,
any other commit → `X.Y.Z-g<short-hash>`. A tag that doesn't match `tinytuya.version` fails the
build. `versionCode` is manual and must only ever increase. The APK is named
`tinytuya-android-<abi>-<versionName>.apk` (currently `arm64-v8a`).

## Steps

1. Land everything on `master`; verify checks and tests.
2. Bump `tinytuya.version` and `tinytuya.versionCode` in `gradle.properties`, commit, and push.
3. Tag and push the tag (the tag defines the release version name):

   ```bash
   git tag -a v0.1.0 -m "TinyTuya Android 0.1.0"
   git push origin v0.1.0
   ```

4. Build the signed release APK.
5. Publish with auto-generated notes:

   ```bash
   gh release create v0.1.0 \
     app/build/outputs/apk/release/tinytuya-android-arm64-v8a-0.1.0.apk \
     --title "v0.1.0" --generate-notes
   ```
