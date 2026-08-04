package com.prfd.tinytuya.device.core.capability

enum class DeviceIntentKind {
    SET_TOGGLE,
    SET_RANGE,
    SET_CHOICE,
    INVOKE_ACTION,
    SET_COLOR,
}

/**
 * A semantic UI request. It deliberately contains no DPS binding or transport information.
 *
 * Device IDs route the request in the app but are redacted from every string representation.
 */
sealed interface DeviceIntent {
    val deviceId: String
    val capabilityId: CapabilityId
    val kind: DeviceIntentKind

    data class SetToggle(
        override val deviceId: String,
        override val capabilityId: CapabilityId,
        val value: Boolean,
    ) : DeviceIntent {
        override val kind = DeviceIntentKind.SET_TOGGLE

        init {
            requireValidDeviceId(deviceId)
        }

        override fun toString(): String =
            "SetToggle(deviceId=[REDACTED], capabilityId=$capabilityId, value=$value)"
    }

    data class SetRange(
        override val deviceId: String,
        override val capabilityId: CapabilityId,
        val value: Int,
    ) : DeviceIntent {
        override val kind = DeviceIntentKind.SET_RANGE

        init {
            requireValidDeviceId(deviceId)
        }

        override fun toString(): String =
            "SetRange(deviceId=[REDACTED], capabilityId=$capabilityId, value=$value)"
    }

    data class SetChoice(
        override val deviceId: String,
        override val capabilityId: CapabilityId,
        val wireValue: String,
    ) : DeviceIntent {
        override val kind = DeviceIntentKind.SET_CHOICE

        init {
            requireValidDeviceId(deviceId)
        }

        override fun toString(): String =
            "SetChoice(deviceId=[REDACTED], capabilityId=$capabilityId, wireValue=[REDACTED])"
    }

    data class InvokeAction(
        override val deviceId: String,
        override val capabilityId: CapabilityId,
        val wireValue: String,
    ) : DeviceIntent {
        override val kind = DeviceIntentKind.INVOKE_ACTION

        init {
            requireValidDeviceId(deviceId)
        }

        override fun toString(): String =
            "InvokeAction(deviceId=[REDACTED], capabilityId=$capabilityId, wireValue=[REDACTED])"
    }

    data class SetColor(
        override val deviceId: String,
        override val capabilityId: CapabilityId,
        val color: TuyaHsvColor,
    ) : DeviceIntent {
        override val kind = DeviceIntentKind.SET_COLOR

        init {
            requireValidDeviceId(deviceId)
        }

        override fun toString(): String =
            "SetColor(deviceId=[REDACTED], capabilityId=$capabilityId, color=$color)"
    }
}

private fun requireValidDeviceId(deviceId: String) {
    require(
        deviceId.length in 1..MAX_DEVICE_ID_LENGTH &&
            deviceId.none(Char::isISOControl)
    ) {
        "Device intent IDs must be bounded single-line text."
    }
}

private const val MAX_DEVICE_ID_LENGTH = 128
