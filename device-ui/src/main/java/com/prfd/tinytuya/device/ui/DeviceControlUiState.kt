package com.prfd.tinytuya.device.ui

import com.prfd.tinytuya.device.core.capability.CapabilityId
import com.prfd.tinytuya.device.core.capability.DeviceIntent

sealed interface DeviceControlUiState {
    data object Unavailable : DeviceControlUiState

    data object Ready : DeviceControlUiState

    data class Sending(val intent: DeviceIntent) : DeviceControlUiState {
        val deviceId: String get() = intent.deviceId
        val capabilityId: CapabilityId get() = intent.capabilityId
        override fun toString(): String = "Sending(intent=$intent)"
    }

    data class Confirmed(val intent: DeviceIntent) : DeviceControlUiState {
        val deviceId: String get() = intent.deviceId
        val capabilityId: CapabilityId get() = intent.capabilityId
        override fun toString(): String = "Confirmed(intent=$intent)"
    }

    data class Error(
        val intent: DeviceIntent,
        val code: String,
        val message: String,
    ) : DeviceControlUiState {
        val deviceId: String get() = intent.deviceId
        val capabilityId: CapabilityId get() = intent.capabilityId
        override fun toString(): String = "Error(intent=$intent, code=$code, message=$message)"
    }
}
