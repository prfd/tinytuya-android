# TinyTuya Android source guide

This is a guided route through the codebase, not a list of every file. Read it in short passes and stop at the checkpoints. The goal is to understand one complete feature at a time before reading visual details or protocol hardening.

The most important fact to keep in mind is that the app has three layers:

```text
Compose screens
    events down, immutable UI state up
ViewModels and Kotlin coordinators
    typed requests and results
Chaquopy gateway <-> versioned JSON <-> tuya_bridge.py <-> TinyTuya
                                |                 |
                          Tuya Cloud HTTPS   local UDP/TCP
```

Kotlin owns Android lifecycle, UI state, Wi-Fi selection, policy, and encrypted persistence. Python owns the calls into TinyTuya. Neither side is trusted blindly: both validate inputs and results at their boundary.

## Start here: the eight-file tour

Do not begin with either of the 1,000-line Compose files. Read these files in order:

1. [MainActivity.kt](app/src/main/java/com/prfd/tinytuya/MainActivity.kt) — the composition root. It constructs the real stores, gateway, network resolver, coordinators, and ViewModels, then reports foreground entry from `onStart`.
2. [AppScreen.kt](app/src/main/java/com/prfd/tinytuya/ui/app/AppScreen.kt) — the small top-level router which turns `AppUiState` into onboarding, inventory, loading, recovery, or the local settings UI.
3. [AppViewModel.kt](app/src/main/java/com/prfd/tinytuya/ui/app/AppViewModel.kt) — the main application state machine. Initially read only the state types, `refreshCatalog`, `refreshKnownDevices`, `discoverLan`, and `setBooleanControl`.
4. [CloudImportModels.kt](app/src/main/java/com/prfd/tinytuya/data/python/CloudImportModels.kt) — cloud credentials, imported devices, and the deliberately redacted `SensitiveString`.
5. [CloudCredentialStore.kt](app/src/main/java/com/prfd/tinytuya/data/local/CloudCredentialStore.kt) and [DeviceCatalogStore.kt](app/src/main/java/com/prfd/tinytuya/data/local/DeviceCatalogStore.kt) — the separate encrypted security domains for Tuya Cloud credentials and locally usable device data. Initially read only their models and interfaces.
6. [LanDiscoveryModels.kt](app/src/main/java/com/prfd/tinytuya/data/lan/LanDiscoveryModels.kt), [LocalStatusModels.kt](app/src/main/java/com/prfd/tinytuya/data/lan/LocalStatusModels.kt), and [LocalControlModels.kt](app/src/main/java/com/prfd/tinytuya/data/lan/LocalControlModels.kt) — the small typed vocabulary used by the coordinators and bridge.
7. [TuyaPythonGateway.kt](app/src/main/java/com/prfd/tinytuya/data/python/TuyaPythonGateway.kt) — the Kotlin side of Chaquopy. Read its interface, the five public methods, and `parseResponse`; skip the detailed JSON fields on the first pass.
8. [tuya_bridge.py](app/src/main/python/tuya_bridge.py) — the Python boundary. Read the module comment, `_success`, `_failure`, then only the five public functions: `health`, `import_cloud`, `discover_lan`, `poll_local`, and `set_values`.

Checkpoint: after this tour, you should be able to explain why the ViewModels and coordinators can receive a fake gateway or store in a test, and why Compose never calls TinyTuya directly.

## The four kinds of device state

Several lists coexist in `DeviceCatalog`. They are not duplicates:

| State | Type and owner | What it means | Freshness rule |
| --- | --- | --- | --- |
| Imported identity | `CloudImportedDevice` in `DeviceCatalog.devices` | Tuya ID, local key, mapping, category, and product metadata imported from the user's cloud project | Replaced by an explicit cloud import |
| LAN observation | `LanDeviceRecord` plus `lastDiscoveryNetwork` in `DeviceCatalog` | An ID was heard at an IP and protocol version on one exact Android network | Current only when its timestamp belongs to the latest generation and the observed Android network handle still matches |
| Local status | `LocalStatusRecord` in `DeviceCatalog.localStatus` | The latest normalized DPS values or offline/error result | Safe for current controls only when it was polled after the current discovery |
| Control session | `controlNetwork` and `controlDiscoveryAtEpochMillis` in `AppViewModel` | The exact Wi-Fi network and discovery generation authorized for writes | Process-only; cleared on reload, navigation, a new scan, deletion, or a default-network change |

This split explains an important UI behavior: an old address can remain encrypted as history without being treated as a device found by the latest scan. A control is available only after a fresh discovery and status read in the current app process.

## Pass 1: startup and routing

Follow this path without entering the large screen implementations:

```text
MainActivity.onCreate
  -> construct separate encrypted credential and device-catalog stores
  -> construct ChaquopyTuyaPythonGateway
  -> construct quick-refresh, discovery, status, and control coordinators
  -> obtain AppViewModel and OnboardingViewModel
  -> AppRoute
  -> collect AppViewModel.state
  -> render Loading, Onboarding, Inventory, Settings, or Recovery

MainActivity.onStart
  -> AppViewModel.onAppForegrounded
  -> wait until settings, catalog, and an Android network observation are ready
  -> quick status refresh, discovery fallback, or no-op according to saved state
```

Read:

- All of [MainActivity.kt](app/src/main/java/com/prfd/tinytuya/MainActivity.kt). It is intentionally small manual dependency injection.
- `AppRoute` in [AppScreen.kt](app/src/main/java/com/prfd/tinytuya/ui/app/AppScreen.kt). Notice that callbacks are passed down; screens do not own repositories or sockets. Settings is a lightweight subdestination over a valid inventory, not a socket-owning state.
- `AppUiState`, `refreshCatalog`, and `maybeStartForegroundRefresh` in [AppViewModel.kt](app/src/main/java/com/prfd/tinytuya/ui/app/AppViewModel.kt). A missing or empty catalog routes to onboarding; a valid catalog routes to inventory; a decryption/storage problem routes to recovery instead of silently deleting data.
- `CloudAccountUiState`, `refreshCloudAccount`, and `forgetCloudCredentials` in `AppViewModel`. Settings receives only a region and masked Client ID; forgetting the vault does not delete the catalog.
- [AppSettingsStore.kt](app/src/main/java/com/prfd/tinytuya/data/local/AppSettingsStore.kt). The default-enabled foreground preference is non-sensitive, excluded from backup with all other app data, and reset by “Delete all local data.”

Ignore `AppLoadingScreen` and `CatalogRecoveryScreen` styling for now.

Checkpoint: find the call which starts initial catalog loading, the Activity callback which announces foreground entry, and the callback which moves the app from a successful import to inventory. The answers are `AppViewModel.init`, `MainActivity.onStart`, and the success callback supplied by `AppRoute`.

## Pass 2: cloud import, end to end

Cloud access happens only because the user explicitly submits the onboarding form or presses “Sync from Tuya.” Automatic foreground refresh never enters this flow.

```text
CredentialsScreen
  -> OnboardingViewModel.importDevices
  -> TuyaPythonGateway.importCloud
  -> Chaquopy callAttr("import_cloud", ...)
  -> tuya_bridge.import_cloud
  -> tinytuya.Cloud.getdevices(include_map=True)
  -> normalized JSON envelope
  -> Kotlin validation and CloudImportResult
  -> EncryptedCloudCredentialStore.save after accepted new credentials
  -> EncryptedDeviceCatalogStore.replaceFromCloud
  -> AppRoute asks AppViewModel to reload the catalog
```

The actual order around the network call is `gateway.importCloud`, credential-vault save, then non-empty catalog replacement. This means an unvalidated form can never replace the last known-good vault record. The saved-account route starts at an explicit inventory or Settings action, calls `prepareForCloudSync`, loads the vault directly inside the coroutine, and sends those values to the gateway without placing them in Compose state.

Read in this order:

1. In [OnboardingScreen.kt](app/src/main/java/com/prfd/tinytuya/ui/onboarding/OnboardingScreen.kt), read `OnboardingRoute`, `OnboardingUiState.destination`, and `CredentialsScreen`. Do not read the illustrations or previews yet.
2. In [OnboardingViewModel.kt](app/src/main/java/com/prfd/tinytuya/ui/onboarding/OnboardingViewModel.kt), read `OnboardingUiState`, `importDevices`, `prepareForCloudSync`, and `runCloudImport`. This is where validation, retained-account loading, prior catalog loading, credential clearing, and both persistence writes are sequenced.
3. Read all of [CloudImportModels.kt](app/src/main/java/com/prfd/tinytuya/data/python/CloudImportModels.kt). Notice which `toString` methods redact values.
4. In [TuyaPythonGateway.kt](app/src/main/java/com/prfd/tinytuya/data/python/TuyaPythonGateway.kt), read `importCloud`, `CloudCredentials.toBridgeJson`, `List<CloudImportedDevice>.toCloudBridgeJson`, `parseCloudImport`, and the shared `parseResponse`.
5. In [tuya_bridge.py](app/src/main/python/tuya_bridge.py), read `_parse_cloud_input`, `_bounded_cloud_requests`, `_normalize_cloud_devices`, and `import_cloud`.
6. Read [CloudCredentialStore.kt](app/src/main/java/com/prfd/tinytuya/data/local/CloudCredentialStore.kt), then return to [DeviceCatalogStore.kt](app/src/main/java/com/prfd/tinytuya/data/local/DeviceCatalogStore.kt) and read `replaceFromCloud`.

The previous device list is sent back to TinyTuya during a sync so its cloud import can preserve useful device information. Cloud credentials are deliberately absent from the catalog API and live in a smaller ciphertext with a different file, schema, authenticated-data label, and Keystore alias.

Security details worth noticing:

- Typed credential fields live in the onboarding ViewModel, not Android saved state. Retained credentials are loaded directly from the vault only for an explicit sync.
- The screen enables Android's secure-window flag while credential values are present.
- A successful Tuya result is the authority to save replacement credentials. A bridge failure keeps new values only in memory for correction and leaves the prior vault untouched.
- After a successful vault write, form fields are cleared before the catalog is saved or success UI is shown. Kotlin and Python require immutable strings at parts of their bridge, so the code minimizes their lifetime and redacts them but does not claim perfect memory zeroization.
- The Kotlin and Python layers return stable error codes and user-safe messages, never raw request data or tracebacks.
- Local keys are imported because local Tuya encryption needs them, but they are never rendered in the UI.

Checkpoint: search for `CLOUD_CREDENTIALS_INVALID` from Python to the onboarding UI. This is a useful example of one failure crossing every layer without exposing the server response.

## Pass 3: local refresh is two operations

The inventory exposes two deliberately separate actions:

- **Refresh status** is the fast path. It directly polls addresses verified by the latest discovery on the exact same Android network and never opens UDP discovery listeners.
- **Find devices** is the slower address-matching path. It listens for Tuya UDP broadcasts, saves the new discovery generation, and then polls status.

Keeping these separate prevents normal startup and future sensor refreshes from paying for a global scan every time.

### Part A: find devices and refresh their addresses

```text
AppViewModel.discoverLan
  -> DefaultLanDiscoveryCoordinator.discover
  -> AndroidLanNetworkResolver.resolve
  -> acquire Android Wi-Fi multicast lock
  -> TuyaPythonGateway.discoverLan
  -> tuya_bridge.discover_lan
  -> TinyTuya UDP scanner on the Android-selected subnet
  -> DeviceCatalogStore.mergeLanDiscovery
  -> release multicast lock in finally
```

Read:

- `discoverLan` in [AppViewModel.kt](app/src/main/java/com/prfd/tinytuya/ui/app/AppViewModel.kt).
- All of [LanDiscoveryCoordinator.kt](app/src/main/java/com/prfd/tinytuya/data/lan/LanDiscoveryCoordinator.kt). It builds a metadata-only request: no local key is given to the scanner.
- [LanNetworkResolver.kt](app/src/main/java/com/prfd/tinytuya/data/lan/LanNetworkResolver.kt). Android chooses the active Wi-Fi interface, IPv4 prefix, broadcast address, and opaque network handle; VPN and unsuitable networks are rejected. Its default-network observer later invalidates this snapshot if Android reports a different or unavailable network.
- `discoverLan` and its serializers/parser in [TuyaPythonGateway.kt](app/src/main/java/com/prfd/tinytuya/data/python/TuyaPythonGateway.kt).
- `_parse_lan_input`, `_normalize_lan_devices`, and `discover_lan` in [tuya_bridge.py](app/src/main/python/tuya_bridge.py).
- `mergeLanDiscovery` in [DeviceCatalogStore.kt](app/src/main/java/com/prfd/tinytuya/data/local/DeviceCatalogStore.kt).

The latest discovery timestamp is a generation marker. Records heard during that scan receive the new marker; retained older records do not. The encrypted catalog also saves Android's opaque network handle, which distinguishes two Wi-Fi networks even if both assign the phone the same private IP range. That makes an empty scan honestly show “not found” and a network change honestly require “Find devices” without immediately destroying useful encrypted history.

### Part B: poll current DPS values

```text
AppViewModel.discoverLan, after discovery succeeds
  -> LocalStatusCoordinator.poll
  -> choose eligible devices from this discovery generation
  -> TuyaPythonGateway.pollLocal
  -> tuya_bridge.poll_local
  -> TinyTuya TCP 6668 status calls
  -> DeviceCatalogStore.mergeLocalPoll
  -> Inventory state becomes Completed + control Ready
```

Read:

- All of [LocalStatusCoordinator.kt](app/src/main/java/com/prfd/tinytuya/data/lan/LocalStatusCoordinator.kt). Its filtering is the most important part.
- [LocalStatusModels.kt](app/src/main/java/com/prfd/tinytuya/data/lan/LocalStatusModels.kt).
- `pollLocal`, its JSON conversion, and `parseLocalPoll` in [TuyaPythonGateway.kt](app/src/main/java/com/prfd/tinytuya/data/python/TuyaPythonGateway.kt).
- `_parse_local_poll_input`, `_normalize_local_data_points`, `_poll_one_local_device`, and `poll_local` in [tuya_bridge.py](app/src/main/python/tuya_bridge.py).
- `mergeLocalPoll` in [DeviceCatalogStore.kt](app/src/main/java/com/prfd/tinytuya/data/local/DeviceCatalogStore.kt).

The coordinator supplies local keys only to the bounded status operation after an address is proven current. Python retries short reads, normalizes primitive DPS values, closes the device, and clears TinyTuya's in-memory key fields in `finally`.

### Part C: reuse verified addresses without UDP

```text
AppViewModel.refreshKnownDevices
  -> DefaultKnownDeviceRefreshCoordinator.refresh
  -> AndroidLanNetworkResolver.resolve
  -> require exact equality with DeviceCatalog.lastDiscoveryNetwork
  -> LocalStatusCoordinator.poll using the current discovery generation
  -> TinyTuya TCP 6668 status calls
  -> Inventory state becomes Completed + control Ready
```

Read [KnownDeviceRefreshCoordinator.kt](app/src/main/java/com/prfd/tinytuya/data/lan/KnownDeviceRefreshCoordinator.kt) in full. It is intentionally small: it requires at least one eligible, previously matched target, re-resolves the active Android network, compares the complete network identity including its opaque handle, and only then delegates to the same bounded status coordinator used after discovery. It does not own or call a discovery coordinator, which makes the “no UDP on quick refresh” boundary explicit.

Then read `onAppForegrounded` and `maybeStartForegroundRefresh` in [AppViewModel.kt](app/src/main/java/com/prfd/tinytuya/ui/app/AppViewModel.kt). Foreground refresh waits for settings, catalog, and a usable network observation; skips onboarding and never-matched inventories; suppresses duplicate starts for 30 seconds; uses the quick path for a trustworthy snapshot; and uses full discovery only when the prior network or address generation cannot be trusted. Neither path calls Tuya Cloud. There is no timer or background service.

`LanDiscoveryUiState.Error.phase` records whether a failure belongs to address discovery or status refresh. The inventory uses that ownership to keep discovery errors inside `FindDevicesCard` and status errors beside the compact refresh action in `DeviceInventoryHeader`.

Checkpoint: explain why a device may have a `LanDeviceRecord` but still not be polled. Common reasons are that the record belongs to an older discovery generation, the device has no key, its protocol version is unsupported, or its access kind blocks direct local access.

## Pass 4: capability policy and device cards

Cloud categories and DPS mappings are inconsistent across Tuya products, so the app separates presentation from permission:

- `LocalDeviceProfileKind` answers: “What kind of card should this look like?”
- `LocalDeviceAccessKind` answers: “What local operation may the app attempt?”
- `LocalSensorKind` selects one of the small read-only sensor summaries.

Read [LocalDeviceCapabilities.kt](app/src/main/java/com/prfd/tinytuya/data/lan/LocalDeviceCapabilities.kt) in three pieces:

1. The enums and `LocalDeviceProfile` explain the output vocabulary.
2. `profile`, `profileKind`, `sensorKind`, and `accessKind` classify a device.
3. `booleanControls` is the fail-closed write authorization rule.

A Boolean control exists only when all of these agree:

1. The device class is allowed direct control.
2. The cached cloud mapping declares the DP as Boolean with a recognized switch code.
3. A status response from the current discovery generation independently reports that DP as Boolean.

Switches/outlets and lights can currently reach `DIRECT_CONTROL`. Covers, sensors, and generic devices are `STATUS_ONLY`. Gateway children, gateways, cameras, and locks are explicitly protected and do not reach local polling or writes.

Then read the presentation pipeline:

- [LocalStatusPresentation.kt](app/src/main/java/com/prfd/tinytuya/ui/inventory/LocalStatusPresentation.kt) turns safe mapped DPS primitives into readable rows and builds the capped inspector.
- [LocalSensorPresentation.kt](app/src/main/java/com/prfd/tinytuya/ui/inventory/LocalSensorPresentation.kt) promotes a small allow-list of validated sensor values into a compact summary.
- [InventoryScreen.kt](app/src/main/java/com/prfd/tinytuya/ui/inventory/InventoryScreen.kt) renders the result.

For `InventoryScreen.kt`, search for and read only these functions at first:

1. `InventoryScreen`
2. `FindDevicesCard`
3. `DeviceInventoryHeader`
4. `InventoryDeviceCard`
5. `LocalStatusPanel`
6. `LocalSensorSummary`
7. `LocalAccessNotice`
8. `LocalDpsInspector`
9. `LocalBooleanControls`

The remaining functions are mostly reusable rows, labels, badges, previews, and styling.

Checkpoint: choose one displayed value, such as outlet power or temperature. Trace it backward from a composable, through a presentation function, to `LocalStatusRecord.dataPoints`, and finally to `_normalize_local_data_points` in Python.

## Pass 5: a confirmed local write

A switch tap is deliberately non-optimistic. The UI retains the last confirmed state while the command is pending.

```text
LocalBooleanControls callback
  -> AppViewModel.setBooleanControl
  -> DefaultLocalControlCoordinator.setBoolean
  -> re-resolve and compare the active Wi-Fi network
  -> re-authorize the mapped + observed Boolean capability
  -> TuyaPythonGateway.setLocalValues
  -> tuya_bridge.set_values
  -> send command, then read back current DPS
  -> Kotlin verifies the requested values are in a CONFIRMED result
  -> merge the observed result into the encrypted catalog
  -> show confirmed state or observed rollback/error
```

Read:

- `LocalBooleanControls` and `localControlSupportingText` in [InventoryScreen.kt](app/src/main/java/com/prfd/tinytuya/ui/inventory/InventoryScreen.kt).
- `LocalControlUiState` and `setBooleanControl` in [AppViewModel.kt](app/src/main/java/com/prfd/tinytuya/ui/app/AppViewModel.kt).
- All of [LocalControlCoordinator.kt](app/src/main/java/com/prfd/tinytuya/data/lan/LocalControlCoordinator.kt).
- [LocalControlModels.kt](app/src/main/java/com/prfd/tinytuya/data/lan/LocalControlModels.kt).
- `setLocalValues` and `parseLocalControl` in [TuyaPythonGateway.kt](app/src/main/java/com/prfd/tinytuya/data/python/TuyaPythonGateway.kt).
- `_parse_local_control_input`, `_local_control_matches`, `_set_local_values_one`, and `set_values` in [tuya_bridge.py](app/src/main/python/tuya_bridge.py).

There are checks at several levels on purpose. The ViewModel prevents conflicting UI operations, the coordinator verifies freshness/network/capability and serializes per device, Python bounds and retries the protocol operation, and the Kotlin gateway rejects a “confirmed” response which does not contain the requested observed values.

`LocalControlException.updatedCatalog` is subtle but important. A command can fail confirmation while still returning a useful observed state. The ViewModel shows the error but uses that catalog to roll the switch back to what the device actually reported.

Checkpoint: find the two mutexes involved in a write. One is per device in `DefaultLocalControlCoordinator`; the other serializes bridge operations in `ChaquopyTuyaPythonGateway`.

## Pass 6: encrypted persistence

Return to [DeviceCatalogStore.kt](app/src/main/java/com/prfd/tinytuya/data/local/DeviceCatalogStore.kt) only after the feature flows make sense.

Read it in this order:

1. `DeviceCatalog`, `LanDeviceRecord`, and `LocalStatusRecord`.
2. The `DeviceCatalogStore` interface and its four mutations.
3. `loadLocked` and `writeCatalogLocked`.
4. `encrypt`, `decrypt`, `writeAtomically`, and `keyForEncryption`.
5. `encodeCatalog` and `decodeCatalog` only when you need the on-disk schema.
6. The validation functions last.

The full schema-v4 catalog is one authenticated ciphertext in `noBackupFilesDir`. A non-exportable Android Keystore AES-256 key protects it with GCM; authenticated associated data and envelope metadata prevent silent format substitution. `AtomicFile` prevents an interrupted write from replacing the last good catalog. The plaintext byte array is wiped after use.

[CloudCredentialStore.kt](app/src/main/java/com/prfd/tinytuya/data/local/CloudCredentialStore.kt) applies the same primitives to a much smaller, independent vault. Its decrypted model redacts both identifiers from `toString`, its UI-facing summary contains only the region and a masked Client ID, and deleting its ciphertext and key leaves the device catalog usable.

The encryption envelope version and the catalog schema version solve different problems. The former describes how bytes are encrypted; the latter describes the JSON fields inside the decrypted payload.

Also read [AndroidManifest.xml](app/src/main/AndroidManifest.xml), [backup_rules.xml](app/src/main/res/xml/backup_rules.xml), and [data_extraction_rules.xml](app/src/main/res/xml/data_extraction_rules.xml) to see the no-backup policy outside Kotlin.

The small [AppSettingsStore.kt](app/src/main/java/com/prfd/tinytuya/data/local/AppSettingsStore.kt) uses private `SharedPreferences` only for non-sensitive behavior preferences. Its write uses `commit` on `Dispatchers.IO` so the UI reports success only after persistence. It is deliberately not mixed into the encrypted device-catalog schema.

Checkpoint: follow `forgetCloudCredentials` and verify that it removes only the credential ciphertext and key. Then follow `deleteAllLocalData` and verify that it attempts credential-vault, catalog, and preference deletion even if one store reports a failure.

## The Kotlin/Python contract

Every public Python function returns a JSON string with one stable envelope:

```json
{
  "ok": true,
  "contract_version": 1,
  "result": {}
}
```

or:

```json
{
  "ok": false,
  "contract_version": 1,
  "error": {
    "code": "STABLE_CODE",
    "message": "Safe message"
  }
}
```

[TuyaPythonGateway.kt](app/src/main/java/com/prfd/tinytuya/data/python/TuyaPythonGateway.kt) is more than a Chaquopy adapter. It:

- moves blocking calls to `Dispatchers.IO`;
- serializes mutable bridge operations;
- converts typed Kotlin requests into bounded JSON;
- checks the contract version and success envelope;
- validates counts, IDs, durations, DPS kinds, sizes, uniqueness, and confirmation semantics;
- converts Python failures into `PythonBridgeException` with stable codes.

[tuya_bridge.py](app/src/main/python/tuya_bridge.py) is deliberately not Android architecture code. It:

- parses and bounds untrusted JSON arguments;
- calls TinyTuya with Android-friendly timeouts and retry limits;
- constrains addresses to the Android-selected subnet;
- converts library/device output to a small primitive schema;
- closes sockets, restores patched TinyTuya globals, and clears key fields;
- maps exceptions and raw Tuya errors to stable, redacted failures.

When changing the contract, update both sides and their tests together. Increment `CONTRACT_VERSION` only for an incompatible boundary change.

## How to read the three large files

These files are large because they keep closely related UI or protocol helpers together. Use symbol search instead of scrolling from top to bottom.

### `OnboardingScreen.kt`

Read `OnboardingRoute` and `OnboardingScreen` first, then each destination independently: `WelcomeScreen`, `SetupGuideScreen`, `CredentialsScreen`, `ImportingScreen`, `ErrorScreen`, and `SuccessScreen`. Leave the illustration, small design primitives, and previews until you are modifying visuals.

The code-native app identity lives in [BrandMark.kt](app/src/main/java/com/prfd/tinytuya/ui/components/BrandMark.kt) and is shared by onboarding, inventory, Settings, and app loading. Keep it for app-level identity; device-profile badges and success/error marks communicate different meanings and should remain distinct.

### `InventoryScreen.kt`

Start with `InventoryScreen` and `InventoryDeviceCard`, then jump to the one panel you are changing. Presentation and policy should remain outside composables where possible, so inspect `LocalStatusPresentation.kt`, `LocalSensorPresentation.kt`, or `LocalDeviceCapabilities.kt` before adding logic directly to the screen.

### `tuya_bridge.py`

Treat each public function as a chapter. Read its `_parse_*` helper, its normalizer or worker, and then the public function. Do not read cloud, discovery, polling, and control internals in one sitting.

## Tests as executable documentation

After each production flow, read its nearest test instead of immediately reading another feature:

| Feature | Best tests to read next |
| --- | --- |
| Startup routing, foreground refresh, and control state | [AppViewModelInstrumentedTest.kt](app/src/androidTest/java/com/prfd/tinytuya/AppViewModelInstrumentedTest.kt) |
| Known-address quick-refresh boundary | [KnownDeviceRefreshCoordinatorTest.kt](app/src/test/java/com/prfd/tinytuya/data/lan/KnownDeviceRefreshCoordinatorTest.kt) |
| Settings persistence and UI | [AppSettingsStoreInstrumentedTest.kt](app/src/androidTest/java/com/prfd/tinytuya/data/local/AppSettingsStoreInstrumentedTest.kt) and [SettingsScreenInstrumentedTest.kt](app/src/androidTest/java/com/prfd/tinytuya/SettingsScreenInstrumentedTest.kt) |
| Onboarding state and credential lifecycle | [OnboardingViewModelInstrumentedTest.kt](app/src/androidTest/java/com/prfd/tinytuya/OnboardingViewModelInstrumentedTest.kt) and [OnboardingScreenInstrumentedTest.kt](app/src/androidTest/java/com/prfd/tinytuya/OnboardingScreenInstrumentedTest.kt) |
| Kotlin/Python validation | [TuyaPythonGatewayInstrumentedTest.kt](app/src/androidTest/java/com/prfd/tinytuya/data/python/TuyaPythonGatewayInstrumentedTest.kt) |
| Encrypted credential vault and deletion | [EncryptedCloudCredentialStoreInstrumentedTest.kt](app/src/androidTest/java/com/prfd/tinytuya/data/local/EncryptedCloudCredentialStoreInstrumentedTest.kt) |
| Encrypted catalog and recovery | [EncryptedDeviceCatalogStoreInstrumentedTest.kt](app/src/androidTest/java/com/prfd/tinytuya/data/local/EncryptedDeviceCatalogStoreInstrumentedTest.kt) |
| Discovery selection and merge | [LanDiscoveryCoordinatorInstrumentedTest.kt](app/src/androidTest/java/com/prfd/tinytuya/data/lan/LanDiscoveryCoordinatorInstrumentedTest.kt) |
| Status eligibility | [LocalStatusCoordinatorInstrumentedTest.kt](app/src/androidTest/java/com/prfd/tinytuya/data/lan/LocalStatusCoordinatorInstrumentedTest.kt) |
| Write authorization and rollback | [LocalControlCoordinatorInstrumentedTest.kt](app/src/androidTest/java/com/prfd/tinytuya/data/lan/LocalControlCoordinatorInstrumentedTest.kt) |
| Profiles and protected devices | [LocalDeviceCapabilitiesInstrumentedTest.kt](app/src/androidTest/java/com/prfd/tinytuya/data/lan/LocalDeviceCapabilitiesInstrumentedTest.kt) |
| Inventory behavior and callbacks | [InventoryScreenInstrumentedTest.kt](app/src/androidTest/java/com/prfd/tinytuya/InventoryScreenInstrumentedTest.kt) |
| DPS and sensor formatting | [LocalStatusPresentationInstrumentedTest.kt](app/src/androidTest/java/com/prfd/tinytuya/ui/inventory/LocalStatusPresentationInstrumentedTest.kt) and [LocalSensorPresentationInstrumentedTest.kt](app/src/androidTest/java/com/prfd/tinytuya/ui/inventory/LocalSensorPresentationInstrumentedTest.kt) |

Most feature tests are instrumentation tests because Compose, Android Keystore, Android networking types, and Chaquopy need an Android runtime. The small tests under `app/src/test` are host JVM tests for code which has no Android dependency.

## Files to postpone

You can safely skip these during the architecture tour:

- `ui/theme/Color.kt`, `Theme.kt`, and `Type.kt` unless changing the visual system.
- launcher icons, `strings.xml`, and the XML theme unless changing packaging or startup appearance.
- Compose previews and small private drawing helpers.
- the detailed JSON encode/decode loops in the catalog store until changing its schema.
- long category and DPS code sets until adding a device profile.
- Gradle version-catalog plumbing unless changing a dependency.

Do read [app/build.gradle.kts](app/build.gradle.kts) once. It records the essential runtime constraints: min SDK 29, target SDK 36, arm64 today, Python 3.11, and pinned TinyTuya 1.20.0.

## Glossary

- **DPS / DP** — Tuya data points. A device exposes values under numeric IDs such as `1`; the meaning differs by product.
- **Mapping** — cloud metadata which associates a numeric DP ID with a code, type, range, scale, unit, or allowed enum values.
- **Local key** — the per-device secret TinyTuya needs to encrypt and authenticate local protocol traffic.
- **Protocol version** — the Tuya local protocol generation, currently accepted from 3.1 through 3.5.
- **Profile kind** — the card/presentation family inferred from category and mapping.
- **Access kind** — the independent fail-closed policy for polling or writing locally.
- **Gateway child** — a Zigbee/BLE-style child reached through a Tuya gateway, not a direct TCP 6668 device.
- **Discovery generation** — the latest `lastDiscoveryAtEpochMillis` marker used to distinguish current addresses from history.
- **Network handle** — Android's opaque identity for one exact `Network`; it stays in Kotlin and encrypted storage and is never sent to Python or displayed.
- **Bridge envelope** — the versioned success/error JSON shared by Kotlin and Python.
- **Catalog** — the encrypted local aggregate of imported identity, LAN observations, and local status.

## Useful navigation and verification commands

From WSL, `rg` is the quickest way to jump to a symbol:

```bash
rg -n 'fun (refreshCatalog|refreshKnownDevices|discoverLan|setBooleanControl)' app/src/main/java
rg -n '^def (import_cloud|discover_lan|poll_local|set_values)' app/src/main/python/tuya_bridge.py
rg -n 'LOCAL_CONTROL_UNCONFIRMED' app/src
```

Because this checkout and Android toolchain live on Windows, run Gradle through Windows when verifying changes:

```bash
/mnt/c/Windows/System32/cmd.exe /d /c gradlew.bat testDebugUnitTest
/mnt/c/Windows/System32/cmd.exe /d /c gradlew.bat connectedDebugAndroidTest
```

The second command needs a connected device and may require accepting installation on the phone.

## A practical learning loop

For each new feature, use the same loop:

1. Start at the user event in a screen.
2. Follow the callback into a ViewModel.
3. Identify the coordinator and the policy it enforces.
4. Follow the typed request through `TuyaPythonGateway`.
5. Read only the matching Python parser, worker, and public function.
6. Follow the result back into the encrypted catalog and immutable UI state.
7. Read the closest test and change a fake input mentally to predict the outcome.

That route is the architecture. The rest of the code is validation, safety, and presentation detail around it.
