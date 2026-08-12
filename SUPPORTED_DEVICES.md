# Supported devices

TinyTuya Android selects a device family only from the category imported from Tuya Cloud. Unknown
and retired categories remain visible after cloud import but receive no local polling, DPS
presentation, or controls, even if their mappings reuse familiar DP codes. A registered category is
not automatically writable: local control additionally requires a direct-Wi-Fi device, a current
discovery generation, a fresh independent DPS observation, compatible mapping types and bounds, and
confirmed read-back.

## Profile evidence

This table is maintained alongside the built-in category registry and its nearby KDoc.

| Profile | Cloud categories | Evidence | Profile claim |
| --- | --- | --- | --- |
| Outlet | `kg` (Switch), `pc` (Power strip), `cz` (Socket) | Representative `kg` switch hardware | Local switching validated on representative hardware; multi-channel behavior and electrical readings have synthetic coverage only. |
| Smart light | `dj`, `xdd`, `fwd`, `dc`, `dd`, `gyd`, `fsd`, `tyndj` | Real hardware | Power and first-release light controls validated on a category `dj` bulb. |
| Curtain or cover | `cl`, `clkg` | Synthetic only | Mapped open, stop, close, and optional position controls have synthetic coverage only. |

## First-release behavior

| Device class | Local behavior | Evidence boundary |
| --- | --- | --- |
| Outlet | Status, switching, and bounded electrical readings when mapped | Representative `kg` switch hardware; multi-channel behavior and electrical readings are synthetic only |
| Lights | Power, White/Color mode, brightness, color temperature, and validated HSV v2 color | Representative category `dj` bulb |
| Covers | Open, stop, close, target position, and current position when independently mapped and observed | Synthetic fixtures only; no cover hardware claim |


Cover command labels are enabled only for imported Enum mappings whose declared values explicitly
contain every value in a reviewed open/stop/close vocabulary. Alternate DPS IDs and `_2` mapping codes are allowed;
unknown vocabularies, missing Stop support, malformed position bounds, stale observations, and
gateway children stay unavailable or read-only. The app does not use TinyTuya's heuristic cover-type
fallback as write authority.