# TinyTuya Android source guide

```text
[Compose UI]
    ->[ViewModels]
        ->[Coordinators]
            ->[Chaquopy gateway]
                ->[JSON contract]
                    ->[tuya_bridge.py]
                        ->[TinyTuya Python]
```

Device extensibility crosses that runtime flow through a separate, enforced dependency graph. Arrows
mean “depends on”:

```text
:app --> :device-core
:app --> :device-profiles --> :device-core
:app --> :device-ui --------> :device-core
```

The ownership boundary is:

| Module                    | Owns                                                                                                                                                     | 
|---------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| `:device-core`            | Normalized DP schemas, family contracts, protected-device policy, semantic capability specs and intents, resolution, codecs, and command authorization   |
| `:device-profiles`        | Built-in category registration, presentation metadata, and capability-spec assembly                                                                      | 
| `:device-ui`              | Safe UI models, atomic controls, compound layout contracts, and reusable layouts                                                                         |
| `:app`                    | Raw Tuya mapping adaptation, fresh observations, runtime policy application, explicit profile/layout composition, ViewModels, persistence, and transport |

`:app` is the only module allowed to join these domains. [MainActivity.kt](app/src/main/java/com/prfd/tinytuya/MainActivity.kt)
is the runtime dependency-composition root; `inventoryDeviceLayoutRegistry` in
[InventoryScreen.kt](app/src/main/java/com/prfd/tinytuya/ui/inventory/InventoryScreen.kt) is the
explicit UI-layout composition point. Registries are compile-time lists—there is no reflection,
classpath scanning, or runtime device plugin loading.

`:device-profiles` and `:device-ui` depend only on `:device-core`. Every device-module Kotlin compile
and `check` runs `verifyDeviceModuleBoundaries`, which rejects other project dependencies and any
import from an app-owned first-party package.

## Start here: the eight-stop tour

Read these files in order:

1. [MainActivity.kt](app/src/main/java/com/prfd/tinytuya/MainActivity.kt) — the runtime composition root. It wires the real stores, app gateway, network resolver, coordinators, and ViewModel factories, then reports foreground entry from `onStart`.
2. [AppScreen.kt](app/src/main/java/com/prfd/tinytuya/ui/app/AppScreen.kt) — the small top-level router which turns `AppUiState` into onboarding, inventory, loading, or recovery and layers the local Settings subdestination over a valid inventory.
3. [AppViewModel.kt](app/src/main/java/com/prfd/tinytuya/ui/app/AppViewModel.kt) — the main application state machine. Initially read only the state types, `refreshCatalog`, `refreshKnownDevices`, `discoverLan`, and `submitControl`.
4. [CloudImportModels.kt](app/src/main/java/com/prfd/tinytuya/data/python/CloudImportModels.kt) — cloud credentials, imported devices, and the deliberately redacted `SensitiveString`.
5. [CloudCredentialStore.kt](app/src/main/java/com/prfd/tinytuya/data/local/CloudCredentialStore.kt) and [DeviceCatalogStore.kt](app/src/main/java/com/prfd/tinytuya/data/local/DeviceCatalogStore.kt) — the encrypted credential vault and the plaintext device-catalog store. Initially read only their models and interfaces.
6. [LanDiscoveryModels.kt](app/src/main/java/com/prfd/tinytuya/data/lan/LanDiscoveryModels.kt), [LocalStatusModels.kt](app/src/main/java/com/prfd/tinytuya/data/lan/LocalStatusModels.kt), and [LocalControlModels.kt](app/src/main/java/com/prfd/tinytuya/data/lan/LocalControlModels.kt) — the small typed vocabulary used by the coordinators and bridge.
7. [TuyaPythonGateway.kt](app/src/main/java/com/prfd/tinytuya/data/python/TuyaPythonGateway.kt) — the Kotlin side of Chaquopy. Read its interface, the five public methods, and `parseResponse`; skip the detailed JSON fields on the first pass.
8. [tuya_bridge.py](app/src/main/python/tuya_bridge.py) — the Python boundary. Read the module comment, `_success`, `_failure`, then only the five public functions: `health`, `import_cloud`, `discover_lan`, `poll_local`, and `set_values`.

## The four kinds of device state

Several lists coexist in `DeviceCatalog`. They are not duplicates:

#### **Imported identity**
* **Type & Owner:** `CloudImportedDevice` in `DeviceCatalog.devices`
* **What it means:** Tuya ID, local key, mapping, category, and product metadata imported from the user's cloud project.
* **Freshness rule:** Replaced by an explicit cloud import.

#### **LAN observation**
* **Type & Owner:** `LanDeviceRecord` plus `lastDiscoveryNetwork` in `DeviceCatalog`
* **What it means:** An ID was heard at an IP and protocol version on one exact Android network.
* **Freshness rule:** Current only when its timestamp belongs to the latest generation and the observed Android network handle still matches.

#### **Local status**
* **Type & Owner:** `LocalStatusRecord` in `DeviceCatalog.localStatus`
* **What it means:** The latest normalized DPS values or offline/error result.
* **Freshness rule:** Safe for current controls only when it is a successful response polled at or after the current discovery generation.

#### **Control session**
* **Type & Owner:** `controlNetwork` and `controlDiscoveryAtEpochMillis` in `AppViewModel`
* **What it means:** The exact Wi-Fi network and discovery generation authorized for writes.
* **Freshness rule:** Process-only; cleared on catalog reload, leaving inventory for onboarding, a new refresh/scan, deletion, or a default-network change.

This split explains an important UI behavior: an old address can remain saved as history without
being treated as a device found by the latest scan. A control is available only when the saved
discovery generation still belongs to the exact active Android network and a fresh status read has
opened a matching control session in the current app process. A trustworthy saved address may reach
that state through quick refresh without repeating UDP discovery.

## Pass 1: startup and routing

Follow this path without entering the large screen implementations:

```text
MainActivity.onCreate
  -> construct the encrypted credential store and plaintext catalog store
  -> construct ChaquopyTuyaPythonGateway
  -> construct quick-refresh, discovery, status, and control coordinators
  -> obtain AppViewModel and OnboardingViewModel
  -> AppRoute
  -> collect AppViewModel.state
  -> render Loading, Onboarding, Inventory, Settings, or Recovery

MainActivity.onStart
  -> AppViewModel.onAppForegrounded
  -> wait until settings, catalog, and an Android network observation are ready
  -> quick status refresh or no-op according to saved state
```

Read:

- All of [MainActivity.kt](app/src/main/java/com/prfd/tinytuya/MainActivity.kt). It is intentionally small manual dependency injection.
- `AppRoute` in [AppScreen.kt](app/src/main/java/com/prfd/tinytuya/ui/app/AppScreen.kt). Notice that callbacks are passed down; screens do not own repositories or sockets. Settings is a lightweight subdestination over a valid inventory, not a socket-owning state.
- `AppUiState`, `refreshCatalog`, and `maybeStartForegroundRefresh` in [AppViewModel.kt](app/src/main/java/com/prfd/tinytuya/ui/app/AppViewModel.kt). A missing or empty catalog routes to onboarding; a valid catalog routes to inventory; a decryption/storage problem routes to recovery instead of silently deleting data.
- `CloudAccountUiState`, `refreshCloudAccount`, and `forgetCloudCredentials` in `AppViewModel`. Settings receives only a region and masked Client ID; forgetting the vault does not delete the catalog.
- [AppSettingsStore.kt](app/src/main/java/com/prfd/tinytuya/data/local/AppSettingsStore.kt). The default-enabled foreground preference is non-sensitive, excluded from backup with all other app data, and reset by “Delete all local data.”

Ignore `AppLoadingScreen` and `CatalogRecoveryScreen` styling for now.

Checkpoint: find the call which starts initial catalog loading, the Activity callback which announces foreground entry, and the callback which moves the app from a successful import to inventory and starts its one automatic discovery. The answers are `AppViewModel.init`, `MainActivity.onStart`, and the success callback supplied by `AppRoute`.

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
  -> Kotlin response parsing into CloudImportResult
  -> EncryptedCloudCredentialStore.save after accepted newly entered credentials
  -> JsonDeviceCatalogStore.replaceFromCloud
  -> AppRoute asks AppViewModel to reload the catalog and run one post-import LAN discovery
```

For newly entered or replacement credentials, the actual order around the network call is
`gateway.importCloud`, credential-vault save, then non-empty catalog replacement. This means an
unvalidated form can never replace the last known-good vault record. The saved-account route starts
at an explicit inventory or Settings action, calls `prepareForCloudSync`, loads the vault directly
inside the coroutine, and sends those values to the gateway without placing them in Compose state or
rewriting an already accepted vault record.

Read in this order:

1. In [OnboardingScreen.kt](app/src/main/java/com/prfd/tinytuya/ui/onboarding/OnboardingScreen.kt), read `OnboardingRoute`, `OnboardingUiState.destination`, and `CredentialsScreen`. Do not read the illustrations or previews yet.
2. In [OnboardingViewModel.kt](app/src/main/java/com/prfd/tinytuya/ui/onboarding/OnboardingViewModel.kt), read `OnboardingUiState`, `importDevices`, `prepareForCloudSync`, and `runCloudImport`. This is where validation, retained-account loading, prior catalog loading, credential clearing, and both persistence writes are sequenced.
3. Read all of [CloudImportModels.kt](app/src/main/java/com/prfd/tinytuya/data/python/CloudImportModels.kt). Notice which `toString` methods redact values.
4. In [TuyaPythonGateway.kt](app/src/main/java/com/prfd/tinytuya/data/python/TuyaPythonGateway.kt), read `importCloud`, `CloudCredentials.toBridgeJson`, `List<CloudImportedDevice>.toCloudBridgeJson`, `parseCloudImport`, and the shared `parseResponse`.
5. In [tuya_bridge.py](app/src/main/python/tuya_bridge.py), read `_parse_cloud_input`, `_bounded_cloud_requests`, `_normalize_cloud_devices`, and `import_cloud`.
6. Read [CloudCredentialStore.kt](app/src/main/java/com/prfd/tinytuya/data/local/CloudCredentialStore.kt), then return to [DeviceCatalogStore.kt](app/src/main/java/com/prfd/tinytuya/data/local/DeviceCatalogStore.kt) and read `replaceFromCloud`.

The previous device list is sent back to TinyTuya during a sync so its cloud import can preserve useful device information. Cloud credentials are deliberately absent from the catalog API and live in a separate encrypted vault with its own file, schema, authenticated-data label, and Keystore alias.

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

Keeping these separate prevents normal startup and foreground status refreshes from paying for a
global scan every time.

The transition from a successful non-empty cloud import to the local inventory runs one automatic
discovery, followed by a status poll. After that transition, only the explicit **Find devices**
action may start discovery; foreground and **Refresh status** operations never escalate to a scan.

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

The latest discovery timestamp is a generation marker. Records heard during that scan receive the new marker; retained older records do not. The saved catalog also saves Android's opaque network handle, which distinguishes two Wi-Fi networks even if both assign the phone the same private IP range. That makes an empty scan honestly show “not found” and a network change honestly require “Find devices” without immediately destroying useful saved history.

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

The coordinator supplies local keys only to the bounded status operation after an address is proven current. Python gives slow session negotiation bounded latency headroom, backs off before retrying transient failures, normalizes primitive DPS values, closes the device, and clears TinyTuya's in-memory key fields in `finally`.

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

Then read `onAppForegrounded` and `maybeStartForegroundRefresh` in [AppViewModel.kt](app/src/main/java/com/prfd/tinytuya/ui/app/AppViewModel.kt). Foreground refresh waits for settings, catalog, and a usable network observation; skips onboarding, never-matched inventories, and untrusted network or address generations; suppresses duplicate starts for 30 seconds; and uses only the quick path for a trustworthy snapshot. It never falls back to discovery or calls Tuya Cloud. There is no timer or background service.

`LanDiscoveryUiState.Error.phase` records whether a failure belongs to address discovery or status refresh. The inventory uses that ownership to keep discovery errors inside `FindDevicesCard` and status errors beside the compact refresh action in `DeviceInventoryHeader`.

Checkpoint: explain why a device may have a `LanDeviceRecord` but still not be polled. Common reasons
are that its category is not in the supported family registry, the record belongs to an older
discovery generation, the device has no key, its protocol version is unsupported, or
`ProtectedDevicePolicy` denies direct local access.

## Pass 4: capability policy and device cards

Cloud categories select families while DPS mappings authorize capabilities, so the app separates
presentation from permission:

- The category-selected `DeviceFamilyDefinition.presentation` supplies the stable layout, type
  label, and symbol.
- `DeviceAccessRestriction` is the final protected-device deny policy.
- `CapabilityAccess` intersects that policy with the selected family's specs before any observed
  capability can become writable.

Read the capability path in four pieces:

1. [TuyaDpSchemaAdapter.kt](app/src/main/java/com/prfd/tinytuya/data/lan/TuyaDpSchemaAdapter.kt)
   is the only `:app` adapter which parses imported raw mapping JSON into the bounded core `DpSchema`.
2. [DeviceFamily.kt](device-core/src/main/kotlin/com/prfd/tinytuya/device/core/profile/DeviceFamily.kt)
   in `:device-core` and
   [BuiltinDeviceFamilies.kt](device-profiles/src/main/kotlin/com/prfd/tinytuya/device/profiles/BuiltinDeviceFamilies.kt)
   in `:device-profiles` classify secret-free identity plus normalized mapping metadata.
3. [CapabilitySpecs.kt](device-core/src/main/kotlin/com/prfd/tinytuya/device/core/capability/CapabilitySpecs.kt),
   [DeviceObservation.kt](device-core/src/main/kotlin/com/prfd/tinytuya/device/core/capability/DeviceObservation.kt),
   and [CapabilityResolver.kt](device-core/src/main/kotlin/com/prfd/tinytuya/device/core/capability/CapabilityResolver.kt)
   in `:device-core` define the reusable primitives and fail-closed
   schema/freshness/access intersection.
4. [LocalDeviceCapabilities.kt](app/src/main/java/com/prfd/tinytuya/data/lan/LocalDeviceCapabilities.kt)
   adapts imported Android models into normalized identity/schema/observation inputs, then exposes
   only family presentation metadata, core restriction/access values, and a redacted
   `ResolvedDevice`.

A Boolean control exists only when all of these agree:

1. `ProtectedDevicePolicy` returns no restriction, and the explicitly registered category family
   declares the semantic toggle writable.
2. The cached cloud mapping uniquely declares a DP as Boolean with a recognized switch code.
3. A successful status response at or after the current discovery generation independently reports
   that DP as Boolean.

Light mode, white brightness, color temperature, and HSV color follow the same rule. Their exact mapping code, declared type and bounds, and independently observed primitive value must agree. The currently validated `colour_data_v2` shape is a 12-digit hexadecimal `HHHHSSSSVVVV` string; unsupported, malformed, or differently encoded values do not resolve to a color capability or receive write authority.

Profiles declaring writable semantics can receive `CapabilityAccess.READ_WRITE`; individual
capabilities still become writable only after the complete schema/fresh-observation intersection.
A registered family with only read-only specs can receive `READ_ONLY`. Unknown and retired
categories receive `DENIED`, resolve no semantic capabilities, expose no cached local DPS, and do
not reach local polling or writes. Gateway children, gateways, cameras, and locks are denied by the
protected-device policy independently of profile selection.

Cover actions deserve a close read in `BuiltinCapabilitySpecs.kt`. The profile accepts only an
imported Enum which explicitly contains every value in one reviewed open/stop/close vocabulary.
Alternate DPS IDs, `_2` codes, and several vocabularies documented by pinned TinyTuya 1.20.0 are
supported declaratively; TinyTuya's heuristic cover type detection, default DPS 1, and fallback type
never become write authority. Optional `percent_control[_2]` and `percent_state[_2]` mappings resolve
to the ordinary range and measurement primitives.

Then read the presentation pipeline:

- [DeviceUiModels.kt](device-ui/src/main/java/com/prfd/tinytuya/device/ui/DeviceUiModels.kt) and
  [DeviceCapabilityRenderers.kt](device-ui/src/main/java/com/prfd/tinytuya/device/ui/DeviceCapabilityRenderers.kt)
  in `:device-ui` map `ResolvedDevice` into bounded display-only models and render toggle, range,
  choice, action, color, measurement, binary, and safe-text primitives. This module sees no catalog,
  mapping JSON, network, key, or Python type.
- [StandardDeviceLayoutIds.kt](device-core/src/main/kotlin/com/prfd/tinytuya/device/core/profile/StandardDeviceLayoutIds.kt)
  in `:device-core`, followed by
  [DeviceLayoutRenderers.kt](device-ui/src/main/java/com/prfd/tinytuya/device/ui/DeviceLayoutRenderers.kt),
  [LightDeviceLayoutRenderer.kt](device-ui/src/main/java/com/prfd/tinytuya/device/ui/LightDeviceLayoutRenderer.kt),
  [CoverDeviceLayoutRenderer.kt](device-ui/src/main/java/com/prfd/tinytuya/device/ui/CoverDeviceLayoutRenderer.kt),
  and [DeviceCompactControls.kt](device-ui/src/main/java/com/prfd/tinytuya/device/ui/DeviceCompactControls.kt)
  in `:device-ui` define stable arrangement hints and the single renderer/fallback contract with
  full and compact surfaces. A compound renderer consumes only safe capability IDs; all
  unconsumed capabilities remain atomic. Compact cards select through the same registry with
  `CompactDeviceLayoutHost`; a renderer that rejects a device on the compact surface simply shows
  no card controls.
- [LocalDataPointInspection.kt](app/src/main/java/com/prfd/tinytuya/data/lan/LocalDataPointInspection.kt)
  builds the safe inspector from already bounded local-status data and the same normalized schema
  boundary; the app-side helper parses raw mapping JSON, while the `:device-ui` module does not.
- [InventoryScreen.kt](app/src/main/java/com/prfd/tinytuya/ui/inventory/InventoryScreen.kt) assembles
  the renderer registry and hosts the selected compound or complete generic atomic fallback.

That is the complete ordinary extension seam. A product covered by existing primitives changes
`:device-profiles`, sanitized host fixtures, KDoc, and one explicit category registry entry;
it does not add an app callback or transport operation. A genuinely different arrangement may add a
safe `:device-ui` layout plus one explicit app registry entry, while the atomic fallback remains
complete. Follow [DEVICE_CONTRIBUTING.md](DEVICE_CONTRIBUTING.md) for the contribution checklist.

For `InventoryScreen.kt`, search for and read only these functions at first:

1. `InventoryScreen`
2. `FindDevicesCard`
3. `DeviceInventoryHeader`
4. `InventoryDeviceCard`
5. `LocalStatusPanel`
6. `LocalAccessNotice`
7. `LocalDpsInspector`

The remaining functions are reusable rows, labels, badges, previews, and styling. There is no
app-owned device-family renderer branch.

Checkpoint: choose one displayed value, such as Outlet power draw or temperature. Trace it backward from
an atomic/compound renderer through `ResolvedDevice`, `CapabilityResolver`,
`LocalStatusRecord.dataPoints`, and finally `_normalize_local_data_points` in Python.

## Pass 5: a confirmed local write

A switch tap, light adjustment, or cover command is deliberately non-optimistic. The UI retains the
last confirmed state while the command is pending.

```text
DeviceLayoutHost
  -> compound renderer or atomic DeviceCapabilityList callback
  -> DeviceIntent(device ID, semantic capability ID, semantic value)
  -> AppViewModel.submitControl
  -> DefaultLocalControlCoordinator.execute
  -> reload the latest saved catalog and compare the Wi-Fi/discovery session
  -> re-resolve the mapped + observed capability and encode a bounded primitive write
  -> TuyaPythonGateway.setLocalValues
  -> tuya_bridge.set_values
  -> send command, then read back current DPS
  -> Kotlin verifies the requested values are in a CONFIRMED result
  -> merge the observed result into the saved catalog
  -> show confirmed state or observed rollback/error
```

Read:

- `DeviceControlUiState`, `DeviceLayoutHost`, and the atomic or compound renderer matching the intent
  in `:device-ui`. The renderer sees only safe UI models and emits the same semantic intent vocabulary.
- `inventoryDeviceLayoutRegistry` and `LocalStatusPanel` in
  [InventoryScreen.kt](app/src/main/java/com/prfd/tinytuya/ui/inventory/InventoryScreen.kt). Inventory
  explicitly registers layouts but does not interpret their capabilities or choose a transport action.
- `DeviceIntent.kt` and `CapabilityCommandAuthorizer.kt` in `:device-core`.
- `AppUiState` and `submitControl` in [AppViewModel.kt](app/src/main/java/com/prfd/tinytuya/ui/app/AppViewModel.kt).
- All of [LocalControlCoordinator.kt](app/src/main/java/com/prfd/tinytuya/data/lan/LocalControlCoordinator.kt).
- [LocalControlModels.kt](app/src/main/java/com/prfd/tinytuya/data/lan/LocalControlModels.kt).
- `setLocalValues` and `parseLocalControl` in [TuyaPythonGateway.kt](app/src/main/java/com/prfd/tinytuya/data/python/TuyaPythonGateway.kt).
- `_parse_local_control_input`, `_local_control_matches`, `_set_local_values_one`, and `set_values` in [tuya_bridge.py](app/src/main/python/tuya_bridge.py).

There are checks at several levels on purpose. The ViewModel prevents conflicting UI operations, the coordinator verifies freshness/network/capability and serializes per device, Python bounds and retries the protocol operation, and the Kotlin gateway rejects a “confirmed” response which does not contain the requested observed values.

`LocalControlException.updatedCatalog` is subtle but important. A command can fail confirmation while still returning a useful observed state. The ViewModel shows the error but uses that catalog to roll the switch back to what the device actually reported.

Checkpoint: find the two mutexes involved in a write. One is per device in `DefaultLocalControlCoordinator`; the other serializes bridge operations in `ChaquopyTuyaPythonGateway`.

## Pass 6: catalog persistence

Return to [DeviceCatalogStore.kt](app/src/main/java/com/prfd/tinytuya/data/local/DeviceCatalogStore.kt) only after the feature flows make sense.

Read it in this order:

1. `DeviceCatalog`, `LanDeviceRecord`, and `LocalStatusRecord`.
2. The `DeviceCatalogStore` interface and its four mutations.
3. `loadLocked` and `writeCatalogLocked`.
4. `writeAtomically`, `encodeCatalog`, and `decodeCatalog`.
5. `encodeCatalog` and `decodeCatalog` only when you need the on-disk schema.
6. The validation functions last.

The full schema-v4 catalog is one plaintext JSON document in `noBackupFilesDir`. It is not encrypted; app sandboxing and the backup exclusions protect it. `AtomicFile` prevents an interrupted write from replacing the last good catalog.

[CloudCredentialStore.kt](app/src/main/java/com/prfd/tinytuya/data/local/CloudCredentialStore.kt) keeps credentials in a much smaller, independently encrypted vault. Its decrypted model redacts both identifiers from `toString`, its UI-facing summary contains only the region and a masked Client ID, and deleting its ciphertext and key leaves the device catalog usable.

The catalog schema version describes the JSON fields in the saved document. There is no separate envelope version now that the catalog is plaintext.

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
- validates the shared envelope and local discovery/status/control responses, including counts, IDs,
  durations, DPS kinds, sizes, uniqueness, and confirmation semantics;
- leaves cloud-device catalog limits and device-ID uniqueness validation to
  `JsonDeviceCatalogStore`;
- converts Python failures into `PythonBridgeException` with stable codes.

[tuya_bridge.py](app/src/main/python/tuya_bridge.py) is deliberately not Android architecture code. It:

- parses and bounds untrusted JSON arguments;
- calls TinyTuya with Android-friendly timeouts and retry limits;
- constrains addresses to the Android-selected subnet;
- converts library/device output to a small primitive schema;
- closes sockets, restores patched TinyTuya globals, and clears key fields;
- maps exceptions and raw Tuya errors to stable, redacted failures.

When changing the contract, update both sides and their tests together. Increment `CONTRACT_VERSION` only for an incompatible boundary change.

## Tests as executable documentation

After each production flow, read its nearest test instead of immediately reading another feature:

| Feature                                                      | Best tests to read next                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
|--------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Startup routing, foreground refresh, and control state       | [AppViewModelInstrumentedTest.kt](app/src/androidTest/java/com/prfd/tinytuya/AppViewModelInstrumentedTest.kt)                                                                                                                                                                                                                                                                                                                                                                                                       |
| Known-address quick-refresh boundary                         | [KnownDeviceRefreshCoordinatorTest.kt](app/src/test/java/com/prfd/tinytuya/data/lan/KnownDeviceRefreshCoordinatorTest.kt)                                                                                                                                                                                                                                                                                                                                                                                           |
| Settings persistence and UI                                  | [AppSettingsStoreInstrumentedTest.kt](app/src/androidTest/java/com/prfd/tinytuya/data/local/AppSettingsStoreInstrumentedTest.kt) and [SettingsScreenInstrumentedTest.kt](app/src/androidTest/java/com/prfd/tinytuya/SettingsScreenInstrumentedTest.kt)                                                                                                                                                                                                                                                              |
| Onboarding state and credential lifecycle                    | [OnboardingViewModelInstrumentedTest.kt](app/src/androidTest/java/com/prfd/tinytuya/OnboardingViewModelInstrumentedTest.kt) and [OnboardingScreenInstrumentedTest.kt](app/src/androidTest/java/com/prfd/tinytuya/OnboardingScreenInstrumentedTest.kt)                                                                                                                                                                                                                                                               |
| Kotlin/Python validation                                     | [TuyaPythonGatewayInstrumentedTest.kt](app/src/androidTest/java/com/prfd/tinytuya/data/python/TuyaPythonGatewayInstrumentedTest.kt)                                                                                                                                                                                                                                                                                                                                                                                 |
| Encrypted credential vault and deletion                      | [EncryptedCloudCredentialStoreInstrumentedTest.kt](app/src/androidTest/java/com/prfd/tinytuya/data/local/EncryptedCloudCredentialStoreInstrumentedTest.kt)                                                                                                                                                                                                                                                                                                                                                          |
| Catalog persistence and recovery                            | [JsonDeviceCatalogStoreInstrumentedTest.kt](app/src/androidTest/java/com/prfd/tinytuya/data/local/JsonDeviceCatalogStoreInstrumentedTest.kt)                                                                                                                                                                                                                                                                                                                                                              |
| Discovery selection and merge                                | [LanDiscoveryCoordinatorInstrumentedTest.kt](app/src/androidTest/java/com/prfd/tinytuya/data/lan/LanDiscoveryCoordinatorInstrumentedTest.kt)                                                                                                                                                                                                                                                                                                                                                                        |
| Status eligibility                                           | [LocalStatusCoordinatorInstrumentedTest.kt](app/src/androidTest/java/com/prfd/tinytuya/data/lan/LocalStatusCoordinatorInstrumentedTest.kt)                                                                                                                                                                                                                                                                                                                                                                          |
| Write authorization and rollback                             | [LocalControlCoordinatorInstrumentedTest.kt](app/src/androidTest/java/com/prfd/tinytuya/data/lan/LocalControlCoordinatorInstrumentedTest.kt)                                                                                                                                                                                                                                                                                                                                                                        |
| Core schema, resolution, authorization, and protected policy | [DpSchemaTest.kt](device-core/src/test/kotlin/com/prfd/tinytuya/device/core/schema/DpSchemaTest.kt), [CapabilityResolverTest.kt](device-core/src/test/kotlin/com/prfd/tinytuya/device/core/capability/CapabilityResolverTest.kt), [CapabilityCommandAuthorizerTest.kt](device-core/src/test/kotlin/com/prfd/tinytuya/device/core/capability/CapabilityCommandAuthorizerTest.kt), and [ProtectedDevicePolicyTest.kt](device-core/src/test/kotlin/com/prfd/tinytuya/device/core/profile/ProtectedDevicePolicyTest.kt) |
| Android profile adapter and protected devices                | [LocalDeviceCapabilitiesInstrumentedTest.kt](app/src/androidTest/java/com/prfd/tinytuya/data/lan/LocalDeviceCapabilitiesInstrumentedTest.kt)                                                                                                                                                                                                                                                                                                                                                                        |
| Built-in category profiles and cover fixtures                | [BuiltinDeviceFamiliesTest.kt](device-profiles/src/test/kotlin/com/prfd/tinytuya/device/profiles/BuiltinDeviceFamiliesTest.kt) and [CoverDeviceProfileTest.kt](device-profiles/src/test/kotlin/com/prfd/tinytuya/device/profiles/CoverDeviceProfileTest.kt)                                                                                                                                                                                                                                                         |
| Atomic controls                                              | [DeviceCapabilityRenderersInstrumentedTest.kt](device-ui/src/androidTest/java/com/prfd/tinytuya/device/ui/DeviceCapabilityRenderersInstrumentedTest.kt)                                                                                                                                                                                                                                                                                                                                                             |
| Layout registry and cover/light fallback                     | [DeviceLayoutRenderersInstrumentedTest.kt](device-ui/src/androidTest/java/com/prfd/tinytuya/device/ui/DeviceLayoutRenderersInstrumentedTest.kt)                                                                                                                                                                                                                                                                                                                                                                     |
| Inventory behavior and callbacks                             | [InventoryScreenInstrumentedTest.kt](app/src/androidTest/java/com/prfd/tinytuya/InventoryScreenInstrumentedTest.kt)                                                                                                                                                                                                                                                                                                                                                                                                 |
| Safe DPS inspection                                          | [LocalDataPointInspectionInstrumentedTest.kt](app/src/androidTest/java/com/prfd/tinytuya/data/lan/LocalDataPointInspectionInstrumentedTest.kt)                                                                                                                                                                                                                                                                                                                                                                      |

The device architecture keeps schema normalization, family selection, capability resolution, codecs,
and authorization under fast host JVM tests in `:device-core` and `:device-profiles`. Compose, Android
Keystore, Android networking types, lifecycle, app integration, and Chaquopy remain instrumentation
tests. The small tests under `app/src/test` cover app code which has no Android dependency.

## Glossary

- **DPS / DP** — Tuya data points. A device exposes values under numeric IDs such as `1`; the meaning differs by product.
- **Mapping** — cloud metadata which associates a numeric DP ID with a code, type, range, scale, unit, or allowed enum values.
- **Local key** — the per-device secret TinyTuya needs to encrypt and authenticate local protocol traffic.
- **Protocol version** — the Tuya local protocol generation, currently accepted from 3.1 through 3.5.
- **Device family** — the profile definition selected by an explicitly registered Tuya Cloud
  category; unknown categories stay generic.
- **Capability access** — the fail-closed `READ_WRITE`, `READ_ONLY`, or `DENIED` intersection applied
  before resolving observed capabilities.
- **Gateway child** — a Zigbee/BLE-style child reached through a Tuya gateway, not a direct TCP 6668 device.
- **Discovery generation** — the latest `lastDiscoveryAtEpochMillis` marker used to distinguish current addresses from history.
- **Network handle** — Android's opaque identity for one exact `Network`; it stays in Kotlin and the saved catalog and is never sent to Python or displayed.
- **Bridge envelope** — the versioned success/error JSON shared by Kotlin and Python.
- **Catalog** — the saved local aggregate of imported identity, LAN observations, and local status.
