# Supported devices

TinyTuya Android selects a device family from the category reported by Tuya Cloud during import.
Unknown and retired categories remain visible after import, but the app performs no local polling
and offers no controls for them, even when their data points reuse familiar codes.

A supported family is not automatically controllable. Local control requires the device to be on
the same Wi-Fi network, a recent status read, mappings that support the command, and confirmation
that the device applied it.

| Device | Cloud categories | Supported local behavior |
| --- | --- | --- |
| Outlet | `kg`, `pc`, `cz` | Status, on/off switching, and electrical readings when mapped |
| Light | `dj`, `xdd`, `fwd`, `dc`, `dd`, `gyd`, `fsd`, `tyndj` | Power, white/color mode, brightness, color temperature, and color (HSV) |
| Cover | `cl`, `clkg` | Open, stop, close, and position when the device reports a known open/stop/close vocabulary and an independently mapped position; otherwise read-only |
