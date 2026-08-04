# Supported devices

TinyTuya Android recognizes device families from imported, sanitized Tuya metadata and mapping
schemas. A recognized family is not automatically writable: local control additionally requires a
direct-Wi-Fi device, a current discovery generation, a fresh independent DPS observation, compatible
mapping types and bounds, and confirmed read-back.

## Profile evidence

The following block is verified against `BuiltinDeviceFamilies.definitions` by a host JVM test. Edit
the profile metadata first; the test will print the required documentation update if this table
drifts.

<!-- BEGIN GENERATED DEVICE SUPPORT -->
| Profile | Family ID | Evidence | Profile claim |
| --- | --- | --- | --- |
| Switch or outlet | `switch_or_outlet` | Real hardware | Switch and outlet control validated on representative local hardware. |
| Smart light | `light` | Real hardware | Power and first-release light controls validated on a category dj bulb. |
| Curtain or cover | `cover` | Synthetic only | Mapped open, stop, close, and optional position controls have synthetic coverage only. |
| Temperature and humidity sensor | `sensor_climate` | Synthetic only | Temperature and humidity sensor presentation has synthetic coverage without a real-hardware compatibility claim. |
| Contact sensor | `sensor_contact` | Synthetic only | Contact sensor presentation has synthetic coverage without a real-hardware compatibility claim. |
| Motion sensor | `sensor_motion` | Synthetic only | Motion sensor presentation has synthetic coverage without a real-hardware compatibility claim. |
| Presence sensor | `sensor_presence` | Synthetic only | Presence sensor presentation has synthetic coverage without a real-hardware compatibility claim. |
| Water leak sensor | `sensor_water_leak` | Synthetic only | Water leak sensor presentation has synthetic coverage without a real-hardware compatibility claim. |
| Smoke alarm | `sensor_smoke` | Synthetic only | Smoke alarm presentation has synthetic coverage without a real-hardware compatibility claim. |
| Gas alarm | `sensor_gas` | Synthetic only | Gas alarm presentation has synthetic coverage without a real-hardware compatibility claim. |
<!-- END GENERATED DEVICE SUPPORT -->

## First-release behavior

| Device class | Local behavior | Evidence boundary |
| --- | --- | --- |
| Switches and outlets | Status, switching, and bounded electrical readings when mapped | Representative switch hardware; multi-gang is synthetic only |
| Lights | Power, White/Color mode, brightness, color temperature, and validated HSV v2 color | Representative category `dj` bulb |
| Selected sensors | Bounded read-only measurements and state summaries | Synthetic fixtures only |
| Covers | Open, stop, close, target position, and current position when independently mapped and observed | Synthetic fixtures only; no cover hardware claim |

Cover command labels are enabled only for imported Enum mappings whose declared values explicitly
contain every value in a reviewed open/stop/close vocabulary. Alternate DPS IDs and `_2` mapping codes are allowed;
unknown vocabularies, missing Stop support, malformed position bounds, stale observations, and
gateway children stay unavailable or read-only. The app does not use TinyTuya's heuristic cover-type
fallback as write authority.

Support is intentionally narrower than category recognition. Firmware, protocol, vendor mapping,
gateway topology, and product revisions can differ under the same marketing name. Synthetic-only
means automated behavior is covered without claiming successful operation on representative physical
hardware.
