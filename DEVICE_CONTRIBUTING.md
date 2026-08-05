# Contributing device support

TinyTuya Android uses compile-time device profiles built from a small semantic capability vocabulary.
Most products should require an explicit Cloud category, capability specs, and sanitized fixtures;
they must not add a ViewModel callback, Python operation, socket path, or vendor-specific copy of a
standard control.

The build enforces that trust boundary. `:device-profiles` and `:device-ui` may depend only on
`:device-core`, and device modules may import only first-party packages below
`com.prfd.tinytuya.device.*`. Do not work around `verifyDeviceModuleBoundaries`; a contribution which
needs app transport, persistence, Python, or secret models is proposing a new architecture boundary.

Read [SUPPORTED_DEVICES.md](SUPPORTED_DEVICES.md)
for the evidence language used publicly.

## Choose the contribution level

1. **Known primitives, standard layout:** add a family definition, capability specs, registration,
   and fixtures. This is the ordinary path.
2. **Known primitives, different arrangement:** do the above and add one safe `DeviceLayoutRenderer`,
   its explicit app registration, accessibility semantics, and Compose tests. The complete atomic
   fallback must remain useful when the renderer is removed.
3. **New semantics:** propose a bounded core model, codec, authorization rules, confirmation policy,
   privacy review, and reusable UI before writing device-specific code.

Before adding semantics, inspect the pinned TinyTuya core, built-in wrappers, and relevant contrib
implementations. Reuse protocol behavior which TinyTuya already provides. Wrapper constants or
conversions may be reference evidence or parity-test oracles, but wrapper guesses about DPS IDs,
ranges, command vocabularies, or side effects are never write authority.

## Profile template

Add a stable ID and one entry to `BuiltinDeviceFamilies.definitions`. A family must claim explicit
normalized categories imported from Tuya Cloud; mapping codes never infer a family. Record the
validation boundary in nearby KDoc and `SUPPORTED_DEVICES.md`:

```kotlin
// In BuiltinDeviceFamilyIds:
val EXAMPLE_FAN = DeviceFamilyId("example_fan")

DeclarativeDeviceFamily(
    id = BuiltinDeviceFamilyIds.EXAMPLE_FAN,
    presentation = DevicePresentation(
        StandardDeviceLayoutIds.GENERIC_CONTROLS,
        typeLabel = "Example fan",
        symbol = "F",
    ),
    categories = setOf("sanitized_category"),
)
```

Register its specs in `BuiltinCapabilitySpecs.forFamily` and compose them from existing primitives:

```kotlin
private fun exampleFanSpecs(): List<CapabilitySpec> = listOf(
    ToggleCapabilitySpec(
        id = CapabilityId("power"),
        label = "Power",
        codeCandidates = listOf("switch"),
        writable = true,
    ),
    ChoiceCapabilitySpec(
        id = CapabilityId("fan.speed"),
        label = "Speed",
        codeCandidates = listOf("fan_speed"),
        writable = true,
        choices = listOf(
            CapabilityChoice("low", "Low"),
            CapabilityChoice("high", "High"),
        ),
    ),
)
```

Capability IDs are stable semantic identifiers. DPS IDs are selected from the imported mapping at
runtime and must not appear in UI code or semantic intents. A writable declaration is only a request:
central access policy, an explicitly registered category, compatible schema, a fresh same-generation
primitive observation, and codec validation all still have to agree.

## Sanitized fixture template

Use pure host fixtures for category selection, capability resolution, and authorization. Values
below are invented and contain no device identity or secret:

```kotlin
val schema = DpSchema.normalize(
    listOf(
        DpDefinitionInput(id = "7", code = "switch", declaredType = "Boolean"),
        DpDefinitionInput(
            id = "11",
            code = "fan_speed",
            declaredType = "Enum",
            enumValues = listOf("low", "high"),
        ),
    )
)
val observation = DeviceObservation.normalize(
    listOf(
        ObservedDataPointInput("7", ObservedDataPointKind.BOOLEAN, "true"),
        ObservedDataPointInput("11", ObservedDataPointKind.STRING, "low"),
    ),
    isFresh = true,
)
val resolved = CapabilityResolver.resolve(
    specs = exampleFanSpecs(),
    schema = schema,
    observation = observation,
    access = CapabilityAccess.READ_WRITE,
)
```

Every writable fixture needs negative variants for missing mapping, wrong declared type, missing or
wrong observed primitive, stale observation, malformed constraints, unsupported values, duplicate or
ambiguous bindings, out-of-range intent, read-only/denied access, and changed mapping before dispatch.
Test alternate DPS numbers to prove semantic IDs do not depend on them. Test confirmation and
rollback at the generic coordinator boundary without adding a device-specific gateway method.

## Privacy and evidence checklist

- [ ] No device ID, UUID, MAC, address, local key, token, credential, request header, raw payload, or
      Python traceback appears in source, fixtures, logs, screenshots, errors, or commit messages.
- [ ] Product/category/mapping samples are minimized and sanitized; fixture names and DPS numbers are
      invented where their exact values are not behaviorally important.
- [ ] Protected gateways, children, cameras, and locks cannot be promoted by the profile.
- [ ] Every writable value is bounded by imported schema plus a fresh independent observation and is
      reauthorized from the latest catalog immediately before the generic write.
- [ ] UI extensions receive only safe UI models, emit only semantic intents, retain the atomic
      fallback, and include accessibility and fault/absence coverage.
- [ ] Host tests cover category registration/resolution/authorization; Android tests are added only
      for Android, Compose, persistence, lifecycle, Python, or network behavior.
- [ ] `verifyDeviceModuleBoundaries` passes, with no new app dependency or app-owned first-party
      import in a device module.
- [ ] `SUPPORTED_DEVICES.md` lists the registered categories and honestly distinguishes real hardware,
      synthetic-only, experimental, and unsupported evidence.
- [ ] A real-hardware claim names only behavior actually exercised on representative hardware. A
      screenshot proves presentation, not protocol compatibility.
- [ ] Windows Gradle verification, in-place device installation, and the repository screenshot
      workflow are completed without clearing encrypted app state.
