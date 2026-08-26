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
import com.prfd.tinytuya.device.ui.ActionGroupUiModel
import com.prfd.tinytuya.device.ui.CapabilityUiModel
import com.prfd.tinytuya.device.ui.CompactActionButton
import com.prfd.tinytuya.device.ui.DeviceCapabilityList
import com.prfd.tinytuya.device.ui.DeviceControlUiState
import com.prfd.tinytuya.device.ui.DeviceLayoutRenderer
import com.prfd.tinytuya.device.ui.DeviceUiModel
import com.prfd.tinytuya.device.ui.MeasurementUiModel
import com.prfd.tinytuya.device.ui.RangeUiModel

/** Reusable cover arrangement composed entirely from action, range, and measurement primitives. */
object CoverDeviceLayoutRenderer : DeviceLayoutRenderer {
  override val layoutId = StandardDeviceLayoutIds.COVER

  override fun prepareFull(device: DeviceUiModel): Set<CapabilityId>? = buildSet {
    device.capability<ActionGroupUiModel>(ACTIONS_ID)?.let { capability -> add(capability.id) }
    device.capability<RangeUiModel>(POSITION_ID)?.let { capability -> add(capability.id) }
    device.capability<MeasurementUiModel>(POSITION_READING_ID)?.let { capability ->
      add(capability.id)
    }
  }
    .takeIf(Set<CapabilityId>::isNotEmpty)

  @Composable
  override fun FullContent(
    device: DeviceUiModel,
    controlState: DeviceControlUiState,
    onIntent: (DeviceIntent) -> Unit,
    modifier: Modifier,
  ) {
    val capabilityIds = requireNotNull(prepareFull(device))
    val hasWritableControl =
      device.capabilities.any { capability ->
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

  /** Compact cover layout keeps the complete reviewed Open / Stop / Close safety vocabulary. */
  override fun prepareCompact(device: DeviceUiModel): Set<CapabilityId>? =
    device.capabilities
      .filterIsInstance<ActionGroupUiModel>()
      .singleOrNull { capability -> capability.id == ACTIONS_ID }
      ?.takeIf { capability -> capability.actions.size == 3 }
      ?.let { capability -> setOf(capability.id) }

  @Composable
  override fun CompactContent(
    device: DeviceUiModel,
    controlState: DeviceControlUiState,
    onIntent: (DeviceIntent) -> Unit,
    modifier: Modifier,
  ) {
    val capability =
      requireNotNull(
        device.capabilities.filterIsInstance<ActionGroupUiModel>().singleOrNull { actionGroup ->
          actionGroup.id == ACTIONS_ID
        }
      )
    Row(
      modifier = modifier.horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.Top,
    ) {
      capability.actions.forEach { action ->
        CompactActionButton(
          deviceId = device.deviceId,
          capability = capability,
          action = action,
          controlState = controlState,
          onIntent = onIntent,
        )
      }
    }
  }
}

private inline fun <reified T : CapabilityUiModel> DeviceUiModel.capability(id: CapabilityId): T? =
  capabilities.filterIsInstance<T>().singleOrNull { capability -> capability.id == id }

private val ACTIONS_ID = CapabilityId("cover.actions")
private val POSITION_ID = CapabilityId("cover.position")
private val POSITION_READING_ID = CapabilityId("cover.position.reading")
