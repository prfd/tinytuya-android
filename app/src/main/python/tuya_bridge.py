"""Stable, JSON-only boundary between the Android app and TinyTuya.

Keep Android lifecycle and persistence concerns out of this module. Public
functions must return a versioned JSON envelope and must never print secrets.
"""

from contextlib import contextmanager
import errno
import importlib
import ipaddress
import json
import logging
import platform
import threading
import time

CONTRACT_VERSION = 1
SUPPORTED_CLOUD_REGIONS = frozenset(("cn", "us", "us-e", "eu", "eu-w", "in", "sg"))
CLOUD_CONNECT_TIMEOUT_SECONDS = 5
CLOUD_READ_TIMEOUT_SECONDS = 15
LAN_MIN_SCAN_SECONDS = 1
LAN_MAX_SCAN_SECONDS = 15
LAN_MAX_DEVICE_COUNT = 1000
LAN_DISCOVERY_BROADCAST_INTERVAL_SECONDS = 2

_CLOUD_IMPORT_LOCK = threading.Lock()
_LAN_DISCOVERY_LOCK = threading.Lock()


def _encode(payload):
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True)


def _failure(code, message):
    return _encode(
        {
            "ok": False,
            "contract_version": CONTRACT_VERSION,
            "error": {
                "code": code,
                "message": message,
            },
        }
    )


def _success(result):
    return _encode(
        {
            "ok": True,
            "contract_version": CONTRACT_VERSION,
            "result": result,
        }
    )


def _cloud_failure(details):
    """Map TinyTuya/Tuya details to a stable response without echoing them."""

    text = str(details).lower()
    if "timeout" in text or "timed out" in text:
        return _failure("CLOUD_TIMEOUT", "Tuya Cloud did not respond in time.")
    if "1004" in text or "sign invalid" in text:
        return _failure("CLOUD_CREDENTIALS_INVALID",
                        "Tuya rejected the Client ID or Client Secret.")
    if "1106" in text or "permission deny" in text or "permission denied" in text:
        return _failure(
            "CLOUD_PERMISSION_DENIED",
            "The cloud project is not authorized or the Smart Life account is not linked.",
        )
    if "28841004" in text or "quota" in text and "exhaust" in text:
        return _failure("CLOUD_QUOTA_EXHAUSTED", "The Tuya Cloud trial quota has been exhausted.")
    if "1010" in text or "subscription" in text and "expir" in text:
        return _failure("CLOUD_SUBSCRIPTION_INACTIVE",
                        "The Tuya Cloud service is inactive or expired.")
    if any(marker in text for marker in
           ("connection", "network", "dns", "name resolution", "unreachable")):
        return _failure("CLOUD_NETWORK_ERROR", "The app could not reach Tuya Cloud.")
    return _failure("CLOUD_IMPORT_FAILED", "Tuya Cloud could not import the linked devices.")


def _parse_cloud_input(credentials_json, previous_devices_json):
    try:
        credentials = json.loads(credentials_json)
    except (TypeError, ValueError):
        return None, None, _failure("CLOUD_INPUT_INVALID", "Cloud credentials are not valid JSON.")

    if not isinstance(credentials, dict):
        return None, None, _failure("CLOUD_INPUT_INVALID",
                                    "Cloud credentials must be a JSON object.")

    region = str(credentials.get("region") or "").strip().lower()
    client_id = str(credentials.get("client_id") or "").strip()
    client_secret = str(credentials.get("client_secret") or "").strip()
    device_id = str(credentials.get("device_id") or "").strip()

    if region not in SUPPORTED_CLOUD_REGIONS:
        return None, None, _failure("CLOUD_REGION_INVALID",
                                    "Select a supported Tuya Cloud data center.")
    if not client_id or not client_secret:
        return None, None, _failure("CLOUD_CREDENTIALS_REQUIRED",
                                    "Client ID and Client Secret are required.")
    if max(len(client_id), len(client_secret), len(device_id)) > 512:
        return None, None, _failure("CLOUD_INPUT_INVALID",
                                    "A cloud credential field is unexpectedly long.")

    try:
        previous_devices = json.loads(previous_devices_json or "[]")
    except (TypeError, ValueError):
        return None, None, _failure("PREVIOUS_DEVICES_INVALID",
                                    "The saved device catalog is invalid.")

    if not isinstance(previous_devices, list) or len(previous_devices) > 1000:
        return None, None, _failure("PREVIOUS_DEVICES_INVALID",
                                    "The saved device catalog is invalid.")

    old_devices = []
    for item in previous_devices:
        if not isinstance(item, dict) or not item.get("id"):
            return None, None, _failure("PREVIOUS_DEVICES_INVALID",
                                        "The saved device catalog is invalid.")
        old_item = dict(item)
        if "local_key" in old_item and "key" not in old_item:
            old_item["key"] = old_item.pop("local_key")
        old_devices.append(old_item)

    return {
        "apiRegion": region,
        "apiKey": client_id,
        "apiSecret": client_secret,
        "apiDeviceID": device_id or None,
    }, old_devices, None


@contextmanager
def _bounded_cloud_requests(cloud_module):
    """Add per-request timeouts to TinyTuya's otherwise unbounded HTTP calls."""

    requests_module = cloud_module.requests
    original_get = requests_module.get
    original_request = requests_module.request
    timeout = (CLOUD_CONNECT_TIMEOUT_SECONDS, CLOUD_READ_TIMEOUT_SECONDS)

    def bounded_get(*args, **kwargs):
        kwargs.setdefault("timeout", timeout)
        return original_get(*args, **kwargs)

    def bounded_request(*args, **kwargs):
        kwargs.setdefault("timeout", timeout)
        return original_request(*args, **kwargs)

    requests_module.get = bounded_get
    requests_module.request = bounded_request
    try:
        yield
    finally:
        requests_module.get = original_get
        requests_module.request = original_request


def _normalize_cloud_devices(devices):
    normalized = []
    missing_local_keys = 0

    for device in devices:
        if not isinstance(device, dict) or not device.get("id"):
            continue

        local_key = str(device.get("key") or device.get("local_key") or "")
        if not local_key:
            missing_local_keys += 1

        mapping = device.get("mapping")
        if not isinstance(mapping, dict):
            mapping = {}

        normalized.append(
            {
                "id": str(device["id"]),
                "name": str(device.get("name") or "").strip(),
                "local_key": local_key,
                "category": str(device.get("category") or ""),
                "product_id": str(device.get("product_id") or ""),
                "product_name": str(device.get("product_name") or ""),
                "model": str(device.get("model") or ""),
                "mac": str(device.get("mac") or ""),
                "uuid": str(device.get("uuid") or ""),
                "sub_device": bool(device.get("sub", False)),
                "gateway_id": str(device.get("gateway_id") or device.get("parent") or ""),
                "node_id": str(device.get("node_id") or ""),
                "protocol_version": str(device.get("version") or ""),
                "last_ip": str(device.get("last_ip") or device.get("ip") or ""),
                "mapping": mapping,
            }
        )

    normalized.sort(key=lambda item: (item["name"].casefold(), item["id"]))
    warnings = []
    if not normalized:
        warnings.append("NO_DEVICES")
    if missing_local_keys:
        warnings.append("MISSING_LOCAL_KEYS")

    return normalized, missing_local_keys, warnings


def _parse_lan_input(network_json, known_devices_json):
    try:
        network = json.loads(network_json)
        known_devices = json.loads(known_devices_json)
    except (TypeError, ValueError):
        return None, None, _failure("LAN_INPUT_INVALID", "Local discovery input is not valid JSON.")

    if not isinstance(network, dict) or not isinstance(known_devices, list):
        return None, None, _failure("LAN_INPUT_INVALID",
                                    "Local discovery input has an invalid shape.")

    try:
        local_address = ipaddress.IPv4Address(str(network.get("local_ipv4") or ""))
        prefix_length = int(network.get("prefix_length"))
        broadcast_address = ipaddress.IPv4Address(str(network.get("broadcast_ipv4") or ""))
        timeout_seconds = int(network.get("timeout_seconds"))
    except (TypeError, ValueError, ipaddress.AddressValueError):
        return None, None, _failure("LAN_NETWORK_INVALID", "The selected Wi-Fi network is invalid.")

    interface_name = str(network.get("interface_name") or "").strip()
    if not interface_name or len(interface_name) > 64:
        return None, None, _failure("LAN_NETWORK_INVALID",
                                    "The selected Wi-Fi interface is invalid.")
    if prefix_length not in range(1, 31):
        return None, None, _failure("LAN_NETWORK_INVALID",
                                    "The selected Wi-Fi prefix cannot broadcast.")
    if timeout_seconds not in range(LAN_MIN_SCAN_SECONDS, LAN_MAX_SCAN_SECONDS + 1):
        return None, None, _failure("LAN_TIMEOUT_INVALID",
                                    "The local discovery interval is out of range.")

    interface = ipaddress.IPv4Interface(f"{local_address}/{prefix_length}")
    if (
            local_address.is_loopback
            or local_address.is_multicast
            or local_address.is_unspecified
            or local_address in (interface.network.network_address,
                                 interface.network.broadcast_address)
            or broadcast_address != interface.network.broadcast_address
    ):
        return None, None, _failure("LAN_NETWORK_INVALID",
                                    "The selected Wi-Fi addresses are inconsistent.")

    if not known_devices or len(known_devices) > LAN_MAX_DEVICE_COUNT:
        return None, None, _failure("LAN_KNOWN_DEVICES_INVALID",
                                    "The encrypted device catalog is empty or too large.")

    scanner_devices = []
    known_ids = set()
    for device in known_devices:
        if not isinstance(device, dict):
            return None, None, _failure("LAN_KNOWN_DEVICES_INVALID",
                                        "The encrypted device catalog is invalid.")
        device_id = str(device.get("id") or "").strip()
        name = str(device.get("name") or "").strip()
        mac = str(device.get("mac") or "").strip()
        if (
                not device_id
                or len(device_id) > 128
                or len(name) > 512
                or len(mac) > 64
                or device_id in known_ids
        ):
            return None, None, _failure("LAN_KNOWN_DEVICES_INVALID",
                                        "The encrypted device catalog is invalid.")
        known_ids.add(device_id)
        # TinyTuya uses this lookup to label broadcasts. Polling is disabled,
        # so local keys never need to cross this discovery boundary.
        scanner_devices.append({"id": device_id, "name": name, "key": "", "mac": mac})

    return {
        "local_address": str(local_address),
        "broadcast_address": str(broadcast_address),
        "network": interface.network,
        "timeout_seconds": timeout_seconds,
        "known_ids": known_ids,
    }, scanner_devices, None


def _normalize_lan_devices(devices, network, known_ids):
    if not isinstance(devices, dict):
        raise ValueError("scanner response is not a dictionary")

    normalized_by_id = {}
    for key, device in devices.items():
        if not isinstance(device, dict):
            continue
        device_id = str(device.get("gwId") or device.get("id") or key or "").strip()
        ip_text = str(device.get("ip") or "").strip()
        if not device_id or len(device_id) > 128:
            continue
        try:
            address = ipaddress.IPv4Address(ip_text)
        except ipaddress.AddressValueError:
            continue
        if address not in network:
            continue

        protocol_version = str(device.get("version") or "").strip()
        product_key = str(device.get("productKey") or "").strip()
        mac = str(device.get("mac") or "").strip()
        if max(len(protocol_version), len(product_key), len(mac)) > 128:
            continue
        normalized_by_id[device_id] = {
            "id": device_id,
            "ip": str(address),
            "protocol_version": protocol_version,
            "product_key": product_key,
            "mac": mac,
            "origin": "broadcast",
        }
        if len(normalized_by_id) >= LAN_MAX_DEVICE_COUNT:
            break

    normalized = sorted(normalized_by_id.values(), key=lambda item: item["id"])
    matched_count = sum(1 for item in normalized if item["id"] in known_ids)
    unmatched_count = len(normalized) - matched_count
    warnings = []
    if not normalized:
        warnings.append("NO_LAN_DEVICES")
    if unmatched_count:
        warnings.append("UNMATCHED_LAN_DEVICES")
    return normalized, matched_count, unmatched_count, warnings


def health():
    """Return runtime versions and perform a small AES self-test."""

    try:
        import tinytuya

        aes = tinytuya.AESCipher(b"0123456789abcdef")
        plaintext = b"tinytuya-android-health"
        ciphertext = aes.encrypt(plaintext, use_base64=False)
        decrypted = aes.decrypt(
            ciphertext,
            use_base64=False,
            decode_text=False,
            verify_padding=True,
        )
        crypto_self_test = decrypted == plaintext
        gcm_available = bool(tinytuya.AESCipher.CRYPTOLIB_HAS_GCM)

        if not crypto_self_test:
            return _failure("CRYPTO_SELF_TEST_FAILED",
                            "The bundled AES implementation failed its self-test.")
        if not gcm_available:
            return _failure("GCM_UNAVAILABLE",
                            "The bundled crypto library cannot operate Tuya 3.5 devices.")

        return _success(
            {
                "python_version": platform.python_version(),
                "tinytuya_version": str(tinytuya.__version__),
                "crypto": {
                    "library": str(tinytuya.AESCipher.CRYPTOLIB),
                    "version": str(tinytuya.AESCipher.CRYPTOLIB_VER),
                    "gcm_available": gcm_available,
                    "self_test_passed": crypto_self_test,
                },
                "supported_protocols": ["3.1", "3.2", "3.3", "3.4", "3.5"],
            }
        )
    except Exception:
        # Do not expose implementation details or a traceback across the public
        # bridge. Detailed diagnostics may be added later behind a local-only,
        # explicitly enabled debug facility with redaction.
        return _failure("BRIDGE_HEALTH_FAILED",
                        "The embedded TinyTuya runtime could not be initialized.")


def import_cloud(credentials_json, previous_devices_json="[]"):
    """Import linked devices and DP mappings from the user's Tuya project."""

    config, previous_devices, input_error = _parse_cloud_input(credentials_json,
                                                               previous_devices_json)
    if input_error:
        return input_error

    try:
        import requests
        import tinytuya

        logging.getLogger("tinytuya").setLevel(logging.WARNING)
        cloud_module = importlib.import_module("tinytuya.Cloud")

        # TinyTuya stores `requests` as a module global. Serialize this section
        # while its functions are temporarily wrapped with Android-friendly
        # timeouts, and always restore them in the context manager's finally.
        with _CLOUD_IMPORT_LOCK:
            with _bounded_cloud_requests(cloud_module):
                cloud = tinytuya.Cloud(**config)
                if cloud.error:
                    return _cloud_failure(cloud.error)

                devices = cloud.getdevices(
                    verbose=False,
                    oldlist=previous_devices,
                    include_map=True,
                )

        if not isinstance(devices, list):
            return _cloud_failure(devices)

        normalized, missing_local_keys, warnings = _normalize_cloud_devices(devices)
        return _success(
            {
                "region": config["apiRegion"],
                "device_count": len(normalized),
                "missing_local_key_count": missing_local_keys,
                "warnings": warnings,
                "devices": normalized,
            }
        )
    except requests.Timeout:
        return _failure("CLOUD_TIMEOUT", "Tuya Cloud did not respond in time.")
    except requests.ConnectionError:
        return _failure("CLOUD_NETWORK_ERROR", "The app could not reach Tuya Cloud.")
    except Exception as exc:
        # Match known server/library failures internally, but never return the
        # exception text because it may contain request data.
        return _cloud_failure(exc)


def discover_lan(network_json, known_devices_json):
    """Listen for Tuya UDP broadcasts on an Android-selected Wi-Fi network."""

    config, known_devices, input_error = _parse_lan_input(network_json, known_devices_json)
    if input_error:
        return input_error

    try:
        scanner = importlib.import_module("tinytuya.scanner")
        started_at = time.monotonic()

        # TinyTuya's desktop scanner discovers interfaces itself. Android has
        # already selected the Wi-Fi LinkProperties, so inject that exact pair
        # for the duration of this serialized call and restore it in finally.
        with _LAN_DISCOVERY_LOCK:
            original_get_ip_to_broadcast = scanner.get_ip_to_broadcast
            original_broadcast_time = scanner.BROADCASTTIME
            scanner_log_level = scanner.log.level
            scanner.get_ip_to_broadcast = lambda: {
                config["broadcast_address"]: config["local_address"]
            }
            # TinyTuya normally repeats its active v3.5 discovery request every
            # six seconds, while older devices may advertise only periodically.
            # Android uses a bounded twelve-second listener which exits early
            # after every known ID is found. Repeat the same upstream packet
            # every two seconds so a sleeping or briefly busy device gets more
            # than one opportunity without requiring another user-initiated scan.
            scanner.BROADCASTTIME = LAN_DISCOVERY_BROADCAST_INTERVAL_SECONDS
            scanner.log.setLevel(logging.CRITICAL)
            try:
                devices = scanner.devices(
                    verbose=False,
                    scantime=config["timeout_seconds"],
                    color=False,
                    poll=False,
                    forcescan=False,
                    byID=True,
                    show_timer=False,
                    discover=True,
                    wantids=list(config["known_ids"]),
                    tuyadevices=known_devices,
                    maxdevices=LAN_MAX_DEVICE_COUNT,
                )
            finally:
                scanner.get_ip_to_broadcast = original_get_ip_to_broadcast
                scanner.BROADCASTTIME = original_broadcast_time
                scanner.log.setLevel(scanner_log_level)

        normalized, matched_count, unmatched_count, warnings = _normalize_lan_devices(
            devices,
            config["network"],
            config["known_ids"],
        )
        duration_ms = max(0, int((time.monotonic() - started_at) * 1000))
        return _success(
            {
                "device_count": len(normalized),
                "matched_device_count": matched_count,
                "unmatched_device_count": unmatched_count,
                "duration_ms": duration_ms,
                "warnings": warnings,
                "devices": normalized,
            }
        )
    except PermissionError:
        return _failure("LAN_PERMISSION_DENIED", "Android blocked local network discovery.")
    except OSError as exc:
        if exc.errno in (errno.EACCES, errno.EPERM):
            return _failure("LAN_PERMISSION_DENIED", "Android blocked local network discovery.")
        if exc.errno == errno.EADDRINUSE:
            return _failure("LAN_PORT_UNAVAILABLE", "A Tuya discovery port is already in use.")
        if exc.errno in (errno.EADDRNOTAVAIL, errno.ENETDOWN, errno.ENETUNREACH,
                         errno.EHOSTUNREACH):
            return _failure("LAN_NETWORK_UNAVAILABLE",
                            "The selected Wi-Fi network became unavailable.")
        return _failure("LAN_SCAN_FAILED", "Local Tuya discovery could not be completed.")
    except Exception:
        return _failure("LAN_SCAN_FAILED", "Local Tuya discovery could not be completed.")


# Local status polling intentionally lives after discovery so its additions can
# be reviewed independently from TinyTuya's scanner integration. Imports stay
# local to these helpers to keep this boundary easy to package and test.
LOCAL_POLL_MAX_DEVICE_COUNT = 32
LOCAL_POLL_MAX_DATA_POINT_COUNT = 256
# Real devices can legitimately take longer than 1.5 seconds to finish a status
# exchange, especially while negotiating a 3.4/3.5 session key. Keep well below
# TinyTuya's five-second default, but leave enough margin that a slow response is
# not turned into a false offline result.
LOCAL_POLL_SOCKET_TIMEOUT_SECONDS = 2.5
# A Tuya device accepts only one local TCP connection at a time, and aggressive
# reconnects can prolong a transient busy period. These are delays after the
# preceding attempt completes, not a polling cadence for devices which already
# responded.
LOCAL_POLL_ATTEMPT_DELAYS_SECONDS = (0.0, 2.0, 5.0)
LOCAL_POLL_WORKER_COUNT = 4
LOCAL_POLL_PROTOCOLS = frozenset(("3.1", "3.2", "3.3", "3.4", "3.5"))


def _parse_local_poll_input(network_json, devices_json):
    try:
        network = json.loads(network_json)
        devices = json.loads(devices_json)
    except (TypeError, ValueError):
        return None, None, _failure(
            "LOCAL_POLL_INPUT_INVALID",
            "Local status input is not valid JSON.",
        )

    if not isinstance(network, dict) or not isinstance(devices, list):
        return None, None, _failure(
            "LOCAL_POLL_INPUT_INVALID",
            "Local status input has an invalid shape.",
        )

    try:
        local_address = ipaddress.IPv4Address(str(network.get("local_ipv4") or ""))
        prefix_length = int(network.get("prefix_length"))
        broadcast_address = ipaddress.IPv4Address(str(network.get("broadcast_ipv4") or ""))
    except (TypeError, ValueError, ipaddress.AddressValueError):
        return None, None, _failure(
            "LOCAL_POLL_NETWORK_INVALID",
            "The selected Wi-Fi network is invalid.",
        )

    interface_name = str(network.get("interface_name") or "").strip()
    if not interface_name or len(interface_name) > 64 or prefix_length not in range(1, 31):
        return None, None, _failure(
            "LOCAL_POLL_NETWORK_INVALID",
            "The selected Wi-Fi network cannot be used for local status.",
        )

    interface = ipaddress.IPv4Interface(f"{local_address}/{prefix_length}")
    if (
        local_address.is_loopback
        or local_address.is_multicast
        or local_address.is_unspecified
        or local_address in (interface.network.network_address, interface.network.broadcast_address)
        or broadcast_address != interface.network.broadcast_address
    ):
        return None, None, _failure(
            "LOCAL_POLL_NETWORK_INVALID",
            "The selected Wi-Fi addresses are inconsistent.",
        )

    if not devices or len(devices) > LOCAL_POLL_MAX_DEVICE_COUNT:
        return None, None, _failure(
            "LOCAL_POLL_DEVICES_INVALID",
            "The local status request is empty or too large.",
        )

    normalized = []
    seen_ids = set()
    for item in devices:
        if not isinstance(item, dict):
            return None, None, _failure(
                "LOCAL_POLL_DEVICES_INVALID",
                "The local status request contains an invalid device.",
            )
        device_id = str(item.get("id") or "").strip()
        local_key = str(item.get("local_key") or "")
        protocol_version = str(item.get("protocol_version") or "").strip()
        try:
            address = ipaddress.IPv4Address(str(item.get("ip") or ""))
            local_key.encode("latin1")
        except (UnicodeEncodeError, ipaddress.AddressValueError):
            return None, None, _failure(
                "LOCAL_POLL_DEVICES_INVALID",
                "The local status request contains an invalid device.",
            )

        if (
            not device_id
            or len(device_id) > 128
            or device_id in seen_ids
            or len(local_key) != 16
            or protocol_version not in LOCAL_POLL_PROTOCOLS
            or address not in interface.network
            or address in (local_address, interface.network.network_address, interface.network.broadcast_address)
        ):
            return None, None, _failure(
                "LOCAL_POLL_DEVICES_INVALID",
                "The local status request contains an invalid device.",
            )
        seen_ids.add(device_id)
        normalized.append(
            {
                "id": device_id,
                "ip": str(address),
                "local_key": local_key,
                "protocol_version": protocol_version,
            }
        )

    return interface.network, normalized, None


def _normalize_local_data_points(dps):
    import math

    if not isinstance(dps, dict):
        return [], ["NO_DATA_POINTS"]

    normalized = []
    warnings = []
    sortable = sorted(
        dps.items(),
        key=lambda item: (
            0,
            int(str(item[0])),
        ) if str(item[0]).isdigit() else (1, str(item[0])),
    )
    for raw_id, raw_value in sortable:
        data_point_id = str(raw_id).strip()
        if (
            not data_point_id.isdigit()
            or len(data_point_id) > 8
            or int(data_point_id) <= 0
        ):
            warnings.append("UNSUPPORTED_DATA_POINT")
            continue

        kind = None
        value = None
        if isinstance(raw_value, bool):
            kind = "boolean"
            value = "true" if raw_value else "false"
        elif isinstance(raw_value, int):
            kind = "integer"
            value = str(raw_value)
        elif isinstance(raw_value, float) and math.isfinite(raw_value):
            kind = "decimal"
            value = repr(raw_value)
        elif isinstance(raw_value, str) and len(raw_value) <= 4096:
            kind = "string"
            value = raw_value
        elif raw_value is None:
            kind = "null"
            value = ""
        elif isinstance(raw_value, (dict, list)):
            try:
                encoded = json.dumps(
                    raw_value,
                    ensure_ascii=False,
                    separators=(",", ":"),
                    sort_keys=True,
                )
            except (TypeError, ValueError):
                encoded = ""
            if encoded and len(encoded) <= 8192:
                kind = "json"
                value = encoded

        if kind is None:
            warnings.append("UNSUPPORTED_DATA_POINT")
            continue
        if len(value) > 8192:
            warnings.append("UNSUPPORTED_DATA_POINT")
            continue
        normalized.append({"id": data_point_id, "kind": kind, "value": value})
        if len(normalized) >= LOCAL_POLL_MAX_DATA_POINT_COUNT:
            warnings.append("DATA_POINTS_TRUNCATED")
            break

    if not normalized:
        warnings.append("NO_DATA_POINTS")
    return normalized, sorted(set(warnings))


def _local_poll_error(error_number):
    error_number = str(error_number or "")
    if error_number in ("901", "905"):
        return "offline", "LOCAL_DEVICE_OFFLINE"
    if error_number == "902":
        return "offline", "LOCAL_DEVICE_TIMEOUT"
    if error_number == "914":
        return "error", "LOCAL_KEY_OR_VERSION_INVALID"
    if error_number in ("900", "904", "908"):
        return "error", "LOCAL_PROTOCOL_ERROR"
    return "error", "LOCAL_STATUS_FAILED"


def _poll_one_local_device(tinytuya, config):
    import socket

    started_at = time.monotonic()
    state = "error"
    error_code = "LOCAL_STATUS_FAILED"
    data_points = []
    warnings = set()
    attempt_count = 0
    for delay_seconds in LOCAL_POLL_ATTEMPT_DELAYS_SECONDS:
        if delay_seconds:
            time.sleep(delay_seconds)
        attempt_count += 1

        device = None
        attempt_warnings = []
        try:
            device = tinytuya.Device(
                config["id"],
                address=config["ip"],
                local_key=config["local_key"],
                version=float(config["protocol_version"]),
                persist=False,
                connection_timeout=LOCAL_POLL_SOCKET_TIMEOUT_SECONDS,
                connection_retry_limit=1,
                connection_retry_delay=0,
            )
            device.set_retry(False)
            status = device.status()
            if isinstance(status, dict) and status.get("Err") is not None:
                state, error_code = _local_poll_error(status.get("Err"))
            elif isinstance(status, dict):
                state = "responded"
                error_code = ""
                data_points, attempt_warnings = _normalize_local_data_points(status.get("dps"))
            else:
                state = "offline"
                error_code = "LOCAL_DEVICE_NO_RESPONSE"
                data_points = []
        except (socket.timeout, TimeoutError):
            state = "offline"
            error_code = "LOCAL_DEVICE_TIMEOUT"
            data_points = []
        except OSError:
            state = "offline"
            error_code = "LOCAL_DEVICE_OFFLINE"
            data_points = []
        except Exception:
            state = "error"
            error_code = "LOCAL_STATUS_FAILED"
            data_points = []
        finally:
            if device is not None:
                try:
                    device.close()
                except Exception:
                    pass
                try:
                    device.local_key = b""
                    device.real_local_key = b""
                except Exception:
                    pass

        warnings.update(attempt_warnings)
        if state == "responded" or error_code == "LOCAL_KEY_OR_VERSION_INVALID":
            break

    duration_ms = max(0, int((time.monotonic() - started_at) * 1000))
    return {
        "id": config["id"],
        "state": state,
        "error_code": error_code,
        "duration_ms": duration_ms,
        "attempt_count": attempt_count,
        "data_points": data_points,
    }, sorted(warnings)


def poll_local(network_json, devices_json):
    """Read current DPS from freshly discovered direct Wi-Fi devices."""

    network, devices, input_error = _parse_local_poll_input(network_json, devices_json)
    if input_error:
        return input_error

    try:
        from concurrent.futures import ThreadPoolExecutor
        import tinytuya

        del network  # Validation boundary only; every target was checked against it.
        logging.getLogger("tinytuya").setLevel(logging.WARNING)
        started_at = time.monotonic()
        worker_count = min(LOCAL_POLL_WORKER_COUNT, len(devices))
        with ThreadPoolExecutor(
            max_workers=worker_count,
            thread_name_prefix="tinytuya-local-poll",
        ) as executor:
            polled = list(
                executor.map(
                    lambda config: _poll_one_local_device(tinytuya, config),
                    devices,
                )
            )

        results = [item[0] for item in polled]
        warnings = {warning for item in polled for warning in item[1]}
        responded_count = sum(1 for item in results if item["state"] == "responded")
        offline_count = sum(1 for item in results if item["state"] == "offline")
        error_count = len(results) - responded_count - offline_count
        if offline_count or error_count:
            warnings.add("PARTIAL_LOCAL_STATUS")
        if not responded_count:
            warnings.add("NO_LOCAL_RESPONSES")

        duration_ms = max(0, int((time.monotonic() - started_at) * 1000))
        return _success(
            {
                "device_count": len(results),
                "responded_device_count": responded_count,
                "offline_device_count": offline_count,
                "error_device_count": error_count,
                "duration_ms": duration_ms,
                "warnings": sorted(warnings),
                "devices": results,
            }
        )
    except Exception:
        return _failure(
            "LOCAL_POLL_FAILED",
            "Local device status could not be read.",
        )
    finally:
        for device in devices:
            device["local_key"] = ""


# Local writes are deliberately narrower than status polling. The first app
# profile sends one verified Boolean switch value, while this boundary accepts
# a small primitive batch so light and cover profiles can be added without
# changing the transport contract.
LOCAL_CONTROL_MAX_CHANGE_COUNT = 8
LOCAL_CONTROL_MAX_SEND_ATTEMPTS = 2
LOCAL_CONTROL_RETRY_DELAY_SECONDS = 0.35
LOCAL_CONTROL_CONFIRM_DELAYS_SECONDS = (0.25, 0.75, 1.25)
LOCAL_CONTROL_WRITABLE_KINDS = frozenset(("boolean", "integer", "decimal", "string"))


def _parse_local_control_input(network_json, device_json, changes_json):
    try:
        device = json.loads(device_json)
        changes = json.loads(changes_json)
    except (TypeError, ValueError):
        return None, None, None, _failure(
            "LOCAL_CONTROL_INPUT_INVALID",
            "Local control input is not valid JSON.",
        )

    if not isinstance(device, dict) or not isinstance(changes, list):
        return None, None, None, _failure(
            "LOCAL_CONTROL_INPUT_INVALID",
            "Local control input has an invalid shape.",
        )

    network, devices, device_error = _parse_local_poll_input(
        network_json,
        json.dumps([device], ensure_ascii=False, separators=(",", ":")),
    )
    if device_error:
        return None, None, None, _failure(
            "LOCAL_CONTROL_DEVICE_INVALID",
            "The local control target is invalid or outside the selected Wi-Fi.",
        )

    if not changes or len(changes) > LOCAL_CONTROL_MAX_CHANGE_COUNT:
        return None, None, None, _failure(
            "LOCAL_CONTROL_CHANGES_INVALID",
            "The local control request is empty or too large.",
        )

    normalized = []
    seen_ids = set()
    for change in changes:
        if not isinstance(change, dict):
            return None, None, None, _failure(
                "LOCAL_CONTROL_CHANGES_INVALID",
                "The local control request contains an invalid value.",
            )
        data_point_id = str(change.get("id") or "").strip()
        kind = str(change.get("kind") or "").strip().lower()
        value = str(change.get("value") or "")
        if (
            not data_point_id.isdigit()
            or len(data_point_id) > 8
            or int(data_point_id) <= 0
            or data_point_id in seen_ids
            or kind not in LOCAL_CONTROL_WRITABLE_KINDS
            or len(value) > 4096
        ):
            return None, None, None, _failure(
                "LOCAL_CONTROL_CHANGES_INVALID",
                "The local control request contains an invalid value.",
            )

        wire_value = None
        normalized_value = value
        try:
            if kind == "boolean" and value in ("true", "false"):
                wire_value = value == "true"
            elif kind == "integer":
                wire_value = int(value)
                normalized_value = str(wire_value)
            elif kind == "decimal":
                import math

                wire_value = float(value)
                if not math.isfinite(wire_value):
                    wire_value = None
                else:
                    normalized_value = repr(wire_value)
            elif kind == "string":
                wire_value = value
        except (TypeError, ValueError, OverflowError):
            wire_value = None

        if wire_value is None:
            return None, None, None, _failure(
                "LOCAL_CONTROL_CHANGES_INVALID",
                "The local control request contains an invalid value.",
            )
        seen_ids.add(data_point_id)
        normalized.append(
            {
                "id": data_point_id,
                "kind": kind,
                "value": normalized_value,
                "wire_value": wire_value,
            }
        )

    return network, devices[0], normalized, None


def _local_control_matches(data_points, changes):
    actual = {
        item["id"]: (item["kind"], item["value"])
        for item in data_points
    }
    return all(
        actual.get(change["id"]) == (change["kind"], change["value"])
        for change in changes
    )


def _set_local_values_one(tinytuya, config, changes):
    import socket

    started_at = time.monotonic()
    state = "error"
    error_code = "LOCAL_CONTROL_FAILED"
    data_points = []
    observed_data_points = None
    terminal_error = False
    try:
        requested = {change["id"]: change["wire_value"] for change in changes}
        # Some Tuya devices apply CONTROL but never send its optional response.
        # Waiting for that packet creates a false timeout even though the relay
        # has already changed. Each delivery attempt sends only once and treats
        # independent status reads as the confirmation authority. If delivery
        # or read-back is missed, resend the same idempotent target state once
        # on a fresh socket.
        for send_attempt in range(LOCAL_CONTROL_MAX_SEND_ATTEMPTS):
            if send_attempt:
                time.sleep(LOCAL_CONTROL_RETRY_DELAY_SECONDS)

            device = None
            observed_data_points = None
            state = "offline"
            error_code = "LOCAL_CONTROL_UNCONFIRMED"
            data_points = []
            try:
                device = tinytuya.Device(
                    config["id"],
                    address=config["ip"],
                    local_key=config["local_key"],
                    version=float(config["protocol_version"]),
                    persist=False,
                    connection_timeout=LOCAL_POLL_SOCKET_TIMEOUT_SECONDS,
                    connection_retry_limit=1,
                    connection_retry_delay=0,
                )
                device.set_retry(False)
                control_response = device.set_multiple_values(requested, nowait=True)
                if isinstance(control_response, dict) and control_response.get("Err") is not None:
                    state, error_code = _local_poll_error(control_response.get("Err"))
                    terminal_error = error_code == "LOCAL_KEY_OR_VERSION_INVALID"

                if not terminal_error:
                    for delay_seconds in LOCAL_CONTROL_CONFIRM_DELAYS_SECONDS:
                        time.sleep(delay_seconds)
                        try:
                            status = device.status()
                        except (socket.timeout, TimeoutError):
                            state = "offline"
                            error_code = "LOCAL_DEVICE_TIMEOUT"
                            continue
                        except OSError:
                            state = "offline"
                            error_code = "LOCAL_DEVICE_OFFLINE"
                            continue

                        if isinstance(status, dict) and status.get("Err") is not None:
                            state, error_code = _local_poll_error(status.get("Err"))
                            if error_code == "LOCAL_KEY_OR_VERSION_INVALID":
                                terminal_error = True
                                break
                            continue
                        if not isinstance(status, dict):
                            state = "offline"
                            error_code = "LOCAL_CONTROL_UNCONFIRMED"
                            continue

                        data_points, unused_warnings = _normalize_local_data_points(status.get("dps"))
                        del unused_warnings
                        observed_data_points = data_points
                        if _local_control_matches(data_points, changes):
                            state = "confirmed"
                            error_code = ""
                            break
                        state = "rejected"
                        error_code = "LOCAL_CONTROL_NOT_APPLIED"
            except (socket.timeout, TimeoutError):
                state = "offline"
                error_code = "LOCAL_DEVICE_TIMEOUT"
                data_points = []
            except OSError:
                state = "offline"
                error_code = "LOCAL_DEVICE_OFFLINE"
                data_points = []
            except Exception:
                state = "error"
                error_code = "LOCAL_CONTROL_FAILED"
                data_points = []
            finally:
                if device is not None:
                    try:
                        device.close()
                    except Exception:
                        pass
                    try:
                        device.local_key = b""
                        device.real_local_key = b""
                    except Exception:
                        pass

            if state == "confirmed" or terminal_error:
                break

        if state != "confirmed":
            if observed_data_points is not None:
                state = "rejected"
                error_code = "LOCAL_CONTROL_NOT_APPLIED"
                data_points = observed_data_points
            elif error_code not in (
                "LOCAL_KEY_OR_VERSION_INVALID",
                "LOCAL_PROTOCOL_ERROR",
                "LOCAL_CONTROL_FAILED",
            ):
                state = "offline"
                error_code = "LOCAL_CONTROL_UNCONFIRMED"
                data_points = []
    except Exception:
        state = "error"
        error_code = "LOCAL_CONTROL_FAILED"
        data_points = []

    duration_ms = max(0, int((time.monotonic() - started_at) * 1000))
    return {
        "id": config["id"],
        "state": state,
        "error_code": error_code,
        "duration_ms": duration_ms,
        "data_points": data_points,
    }


def set_values(network_json, device_json, changes_json):
    """Set a bounded primitive DPS batch and confirm it with a fresh status read."""

    network, device, changes, input_error = _parse_local_control_input(
        network_json,
        device_json,
        changes_json,
    )
    if input_error:
        return input_error

    try:
        import tinytuya

        del network  # Validation boundary only; the target was checked against it.
        logging.getLogger("tinytuya").setLevel(logging.WARNING)
        result = _set_local_values_one(tinytuya, device, changes)
        return _success(result)
    except Exception:
        return _failure(
            "LOCAL_CONTROL_FAILED",
            "The local command could not be completed safely.",
        )
    finally:
        device["local_key"] = ""
        for change in changes:
            change["wire_value"] = None
