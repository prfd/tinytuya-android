import pathlib
import sys
import unittest
from unittest import mock


PYTHON_SOURCE = pathlib.Path(__file__).resolve().parents[2] / "main" / "python"
sys.path.insert(0, str(PYTHON_SOURCE))

import tuya_bridge  # noqa: E402


class _FakeDevice:
    def __init__(self, status):
        self._status = status
        self.local_key = b"key"
        self.real_local_key = b"key"
        self.closed = False
        self.retry = None

    def set_retry(self, enabled):
        self.retry = enabled

    def status(self):
        return self._status

    def close(self):
        self.closed = True


class _FakeTinyTuya:
    def __init__(self, statuses):
        self._statuses = iter(statuses)
        self.devices = []

    def Device(self, *args, **kwargs):
        device = _FakeDevice(next(self._statuses))
        device.args = args
        device.kwargs = kwargs
        self.devices.append(device)
        return device


class LocalPollRetryTest(unittest.TestCase):
    config = {
        "id": "test-device",
        "ip": "192.168.10.42",
        "local_key": "0123456789abcdef",
        "protocol_version": "3.5",
    }

    def test_transient_failures_use_bounded_backoff_before_success(self):
        tinytuya = _FakeTinyTuya(
            [
                {"Err": "901"},
                {"Err": "902"},
                {"dps": {"1": True}},
            ]
        )

        with mock.patch.object(tuya_bridge.time, "sleep") as sleep:
            result, warnings = tuya_bridge._poll_one_local_device(
                tinytuya,
                self.config,
            )

        self.assertEqual("responded", result["state"])
        self.assertEqual("", result["error_code"])
        self.assertEqual(3, result["attempt_count"])
        self.assertEqual([mock.call(2.0), mock.call(5.0)], sleep.call_args_list)
        self.assertEqual([], warnings)
        self.assertEqual(3, len(tinytuya.devices))
        self.assertTrue(all(device.closed for device in tinytuya.devices))
        self.assertTrue(all(device.retry is False for device in tinytuya.devices))
        self.assertTrue(all(device.local_key == b"" for device in tinytuya.devices))
        self.assertTrue(all(device.real_local_key == b"" for device in tinytuya.devices))
        self.assertTrue(
            all(
                device.kwargs["connection_timeout"] == 2.5
                for device in tinytuya.devices
            )
        )

    def test_key_or_version_failure_is_not_retried(self):
        tinytuya = _FakeTinyTuya([{"Err": "914"}])

        with mock.patch.object(tuya_bridge.time, "sleep") as sleep:
            result, _ = tuya_bridge._poll_one_local_device(tinytuya, self.config)

        self.assertEqual("error", result["state"])
        self.assertEqual("LOCAL_KEY_OR_VERSION_INVALID", result["error_code"])
        self.assertEqual(1, result["attempt_count"])
        sleep.assert_not_called()


if __name__ == "__main__":
    unittest.main()
