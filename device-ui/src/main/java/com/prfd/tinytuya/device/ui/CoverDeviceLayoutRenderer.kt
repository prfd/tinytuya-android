package com.prfd.tinytuya.device.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.prfd.tinytuya.device.core.capability.CapabilityId
import com.prfd.tinytuya.device.core.capability.DeviceIntent
import com.prfd.tinytuya.device.core.profile.StandardDeviceLayoutIds

/** Reusable cover arrangement composed entirely from action, range, and measurement primitives. */
object CoverDeviceLayoutRenderer : DeviceLayoutRenderer {
    override val layoutId = StandardDeviceLayoutIds.COVER

    override fun prepare(device: DeviceUiModel): Set<CapabilityId>? = buildSet {
        device.capability<ActionGroupUiModel>(ACTIONS_ID)?.let { capability -> add(capability.id) }
        device.capability<RangeUiModel>(POSITION_ID)?.let { capability -> add(capability.id) }
        device.capability<MeasurementUiModel>(POSITION_READING_ID)?.let { capability ->
            add(capability.id)
        }
    }.takeIf(Set<CapabilityId>::isNotEmpty)

    @Composable
    override fun Content(
        device: DeviceUiModel,
        controlState: DeviceControlUiState,
        onIntent: (DeviceIntent) -> Unit,
        modifier: Modifier,
    ) {
        val capabilityIds = requireNotNull(prepare(device))
        val hasWritableControl = device.capabilities.any { capability ->
            capability.id in capabilityIds && capability.writable
        }
        DeviceCapabilityList(
            device = device,
            controlState = controlState,
            onIntent = onIntent,
            modifier = modifier.padding(top = 16.dp),
            capabilityIds = capabilityIds,
            sectionTitle = if (hasWritableControl) "Cover controls" else "Cover position",
        )
    }
}

private inline fun <reified T : CapabilityUiModel> DeviceUiModel.capability(
    id: CapabilityId,
): T? = capabilities.filterIsInstance<T>().singleOrNull { capability -> capability.id == id }

private val ACTIONS_ID = CapabilityId("cover.actions")
private val POSITION_ID = CapabilityId("cover.position")
private val POSITION_READING_ID = CapabilityId("cover.position.reading")
