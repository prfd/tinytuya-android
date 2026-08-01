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
        return _failure("CLOUD_CREDENTIALS_INVALID", "Tuya rejected the Client ID or Client Secret.")
    if "1106" in text or "permission deny" in text or "permission denied" in text:
        return _failure(
            "CLOUD_PERMISSION_DENIED",
            "The cloud project is not authorized or the Smart Life account is not linked.",
        )
    if "28841004" in text or "quota" in text and "exhaust" in text:
        return _failure("CLOUD_QUOTA_EXHAUSTED", "The Tuya Cloud trial quota has been exhausted.")
    if "1010" in text or "subscription" in text and "expir" in text:
        return _failure("CLOUD_SUBSCRIPTION_INACTIVE", "The Tuya Cloud service is inactive or expired.")
    if any(marker in text for marker in ("connection", "network", "dns", "name resolution", "unreachable")):
        return _failure("CLOUD_NETWORK_ERROR", "The app could not reach Tuya Cloud.")
    return _failure("CLOUD_IMPORT_FAILED", "Tuya Cloud could not import the linked devices.")


def _parse_cloud_input(credentials_json, previous_devices_json):
    try:
        credentials = json.loads(credentials_json)
    except (TypeError, ValueError):
        return None, None, _failure("CLOUD_INPUT_INVALID", "Cloud credentials are not valid JSON.")

    if not isinstance(credentials, dict):
        return None, None, _failure("CLOUD_INPUT_INVALID", "Cloud credentials must be a JSON object.")

    region = str(credentials.get("region") or "").strip().lower()
    client_id = str(credentials.get("client_id") or "").strip()
    client_secret = str(credentials.get("client_secret") or "").strip()
    device_id = str(credentials.get("device_id") or "").strip()

    if region not in SUPPORTED_CLOUD_REGIONS:
        return None, None, _failure("CLOUD_REGION_INVALID", "Select a supported Tuya Cloud data center.")
    if not client_id or not client_secret:
        return None, None, _failure("CLOUD_CREDENTIALS_REQUIRED", "Client ID and Client Secret are required.")
    if max(len(client_id), len(client_secret), len(device_id)) > 512:
        return None, None, _failure("CLOUD_INPUT_INVALID", "A cloud credential field is unexpectedly long.")

    try:
        previous_devices = json.loads(previous_devices_json or "[]")
    except (TypeError, ValueError):
        return None, None, _failure("PREVIOUS_DEVICES_INVALID", "The saved device catalog is invalid.")

    if not isinstance(previous_devices, list) or len(previous_devices) > 1000:
        return None, None, _failure("PREVIOUS_DEVICES_INVALID", "The saved device catalog is invalid.")

    old_devices = []
    for item in previous_devices:
        if not isinstance(item, dict) or not item.get("id"):
            return None, None, _failure("PREVIOUS_DEVICES_INVALID", "The saved device catalog is invalid.")
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
        return None, None, _failure("LAN_INPUT_INVALID", "Local discovery input has an invalid shape.")

    try:
        local_address = ipaddress.IPv4Address(str(network.get("local_ipv4") or ""))
        prefix_length = int(network.get("prefix_length"))
        broadcast_address = ipaddress.IPv4Address(str(network.get("broadcast_ipv4") or ""))
        timeout_seconds = int(network.get("timeout_seconds"))
    except (TypeError, ValueError, ipaddress.AddressValueError):
        return None, None, _failure("LAN_NETWORK_INVALID", "The selected Wi-Fi network is invalid.")

    interface_name = str(network.get("interface_name") or "").strip()
    if not interface_name or len(interface_name) > 64:
        return None, None, _failure("LAN_NETWORK_INVALID", "The selected Wi-Fi interface is invalid.")
    if prefix_length not in range(1, 31):
        return None, None, _failure("LAN_NETWORK_INVALID", "The selected Wi-Fi prefix cannot broadcast.")
    if timeout_seconds not in range(LAN_MIN_SCAN_SECONDS, LAN_MAX_SCAN_SECONDS + 1):
        return None, None, _failure("LAN_TIMEOUT_INVALID", "The local discovery interval is out of range.")

    interface = ipaddress.IPv4Interface(f"{local_address}/{prefix_length}")
    if (
        local_address.is_loopback
        or local_address.is_multicast
        or local_address.is_unspecified
        or local_address in (interface.network.network_address, interface.network.broadcast_address)
        or broadcast_address != interface.network.broadcast_address
    ):
        return None, None, _failure("LAN_NETWORK_INVALID", "The selected Wi-Fi addresses are inconsistent.")

    if not known_devices or len(known_devices) > LAN_MAX_DEVICE_COUNT:
        return None, None, _failure("LAN_KNOWN_DEVICES_INVALID", "The encrypted device catalog is empty or too large.")

    scanner_devices = []
    known_ids = set()
    for device in known_devices:
        if not isinstance(device, dict):
            return None, None, _failure("LAN_KNOWN_DEVICES_INVALID", "The encrypted device catalog is invalid.")
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
            return None, None, _failure("LAN_KNOWN_DEVICES_INVALID", "The encrypted device catalog is invalid.")
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
            return _failure("CRYPTO_SELF_TEST_FAILED", "The bundled AES implementation failed its self-test.")
        if not gcm_available:
            return _failure("GCM_UNAVAILABLE", "The bundled crypto library cannot operate Tuya 3.5 devices.")

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
        return _failure("BRIDGE_HEALTH_FAILED", "The embedded TinyTuya runtime could not be initialized.")


def import_cloud(credentials_json, previous_devices_json="[]"):
    """Import linked devices and DP mappings from the user's Tuya project."""

    config, previous_devices, input_error = _parse_cloud_input(credentials_json, previous_devices_json)
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
            # six seconds. That second request lands at our scan deadline, so a
            # sleeping or briefly busy device may only appear on the next scan.
            # Keep the same packet and bounded listener, but give the device
            # three opportunities during this six-second Android scan.
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
        if exc.errno in (errno.EADDRNOTAVAIL, errno.ENETDOWN, errno.ENETUNREACH, errno.EHOSTUNREACH):
            return _failure("LAN_NETWORK_UNAVAILABLE", "The selected Wi-Fi network became unavailable.")
        return _failure("LAN_SCAN_FAILED", "Local Tuya discovery could not be completed.")
    except Exception:
        return _failure("LAN_SCAN_FAILED", "Local Tuya discovery could not be completed.")
