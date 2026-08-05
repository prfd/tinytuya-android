# Supported devices

TinyTuya Android selects a device family only from the category imported from Tuya Cloud. Unknown
categories remain unsupported even if their mappings reuse familiar DP codes. A registered category
is not automatically writable: local control additionally requires a direct-Wi-Fi device, a current
discovery generation, a fresh independent DPS observation, compatible mapping types and bounds, and
confirmed read-back.

## Profile evidence

This table is maintained alongside the built-in category registry and its nearby KDoc.

| Profile | Cloud categories | Evidence | Profile claim |
| --- | --- | --- | --- |
| Switch or outlet | `kg`, `cz`, `pc` | Real hardware | Switch and outlet control validated on representative local hardware. |
| Smart light | `dj`, `xdd`, `fwd`, `dc`, `dd`, `gyd`, `fsd`, `tyndj` | Real hardware | Power and first-release light controls validated on a category `dj` bulb. |
| Curtain or cover | `cl`, `clkg` | Synthetic only | Mapped open, stop, close, and optional position controls have synthetic coverage only. |
| Temperature and humidity sensor | `wsdcg` | Synthetic only | Presentation has synthetic coverage without a real-hardware compatibility claim. |
| Contact sensor | `mcs` | Synthetic only | Presentation has synthetic coverage without a real-hardware compatibility claim. |
| Motion sensor | `pir` | Synthetic only | Presentation has synthetic coverage without a real-hardware compatibility claim. |
| Presence sensor | `hps` | Synthetic only | Presentation has synthetic coverage without a real-hardware compatibility claim. |
| Water leak sensor | `sj` | Synthetic only | Presentation has synthetic coverage without a real-hardware compatibility claim. |
| Smoke alarm | `ywbj` | Synthetic only | Presentation has synthetic coverage without a real-hardware compatibility claim. |
| Gas alarm | `rqbj` | Synthetic only | Presentation has synthetic coverage without a real-hardware compatibility claim. |

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

Support is intentionally narrower than category registration. Firmware, protocol, vendor mapping,
gateway topology, and product revisions can differ under the same marketing name. Synthetic-only
means automated behavior is covered without claiming successful operation on representative physical
hardware.
