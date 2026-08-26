package com.prfd.tinytuya.device.ui.layouts

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.prfd.tinytuya.device.core.capability.CapabilityId
import com.prfd.tinytuya.device.core.capability.DeviceIntent
import com.prfd.tinytuya.device.core.profile.StandardDeviceLayoutIds
import com.prfd.tinytuya.device.ui.CapabilityUiModel
import com.prfd.tinytuya.device.ui.CompactToggleButton
import com.prfd.tinytuya.device.ui.DeviceCapabilityList
import com.prfd.tinytuya.device.ui.DeviceControlUiState
import com.prfd.tinytuya.device.ui.DeviceLayoutRenderer
import com.prfd.tinytuya.device.ui.DeviceUiModel
import com.prfd.tinytuya.device.ui.ToggleUiModel
import com.prfd.tinytuya.device.ui.genericSectionTitle

/**
 * Generic layout for ordinary profiles: the full surface renders every resolved capability as the
 * standard atomic list, while the compact surface exposes only Boolean toggles.
 */
object GenericDeviceLayoutRenderer : DeviceLayoutRenderer {
  override val layoutId = StandardDeviceLayoutIds.GENERIC_CONTROLS

  override fun prepareFull(device: DeviceUiModel): Set<CapabilityId>? =
    device.capabilities
      .mapTo(linkedSetOf(), CapabilityUiModel::id)
      .takeIf(Set<CapabilityId>::isNotEmpty)

  override fun prepareCompact(device: DeviceUiModel): Set<CapabilityId>? =
    device.capabilities
      .filterIsInstance<ToggleUiModel>()
      .mapTo(linkedSetOf(), ToggleUiModel::id)
      .takeIf(Set<CapabilityId>::isNotEmpty)

  @Composable
  override fun FullContent(
    device: DeviceUiModel,
    controlState: DeviceControlUiState,
    onIntent: (DeviceIntent) -> Unit,
    modifier: Modifier,
  ) {
    val capabilityIds = requireNotNull(prepareFull(device))
    DeviceCapabilityList(
      device = device,
      controlState = controlState,
      onIntent = onIntent,
      modifier = modifier.padding(top = 16.dp),
      capabilityIds = capabilityIds,
      sectionTitle = device.capabilities.genericSectionTitle(),
    )
  }

  @Composable
  override fun CompactContent(
    device: DeviceUiModel,
    controlState: DeviceControlUiState,
    onIntent: (DeviceIntent) -> Unit,
    modifier: Modifier,
  ) {
    val capabilityIds = requireNotNull(prepareCompact(device))
    val toggles =
      device.capabilities.filterIsInstance<ToggleUiModel>().filter { capability ->
        capability.id in capabilityIds
      }
    Row(
      modifier = modifier.horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.Top,
    ) {
      toggles.forEach { capability ->
        CompactToggleButton(
          deviceId = device.deviceId,
          capability = capability,
          controlState = controlState,
          showLabel = toggles.size > 1,
          onIntent = onIntent,
        )
      }
    }
  }
}
