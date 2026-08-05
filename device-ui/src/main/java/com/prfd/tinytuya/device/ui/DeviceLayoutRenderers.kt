package com.prfd.tinytuya.device.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.prfd.tinytuya.device.core.capability.CapabilityId
import com.prfd.tinytuya.device.core.capability.DeviceIntent
import com.prfd.tinytuya.device.core.profile.DeviceLayoutId

/**
 * Compile-time extension point for a genuinely different arrangement of known safe primitives.
 *
 * [prepare] is a non-composable, deterministic selection step returning the capabilities the layout
 * understands. Returning null, throwing, naming an absent capability, or returning an empty set
 * rejects the renderer and selects the generic atomic fallback. [Content] receives no schema, DPS
 * binding, transport, network, catalog, or secret-bearing model and may emit only semantic
 * [DeviceIntent] values.
 */
interface DeviceLayoutRenderer {
  val layoutId: DeviceLayoutId

  fun prepare(device: DeviceUiModel): Set<CapabilityId>?

  @Composable
  fun Content(
    device: DeviceUiModel,
    controlState: DeviceControlUiState,
    onIntent: (DeviceIntent) -> Unit,
    modifier: Modifier,
  )
}

/** Explicit renderer registry; duplicate layout IDs are rejected at assembly time. */
class DeviceLayoutRendererRegistry(renderers: List<DeviceLayoutRenderer>) {
  val renderers: List<DeviceLayoutRenderer> = renderers.toList()
  private val byLayoutId = this.renderers.associateBy(DeviceLayoutRenderer::layoutId)

  init {
    require(byLayoutId.size == this.renderers.size) { "Device layout renderer IDs must be unique." }
  }

  internal fun prepare(device: DeviceUiModel): PreparedDeviceLayout? {
    val layoutId = device.layoutId ?: return null
    val renderer = byLayoutId[layoutId] ?: return null
    val availableIds = device.capabilities.mapTo(mutableSetOf()) { capability -> capability.id }
    val consumedIds =
      runCatching { renderer.prepare(device)?.toSet() }
        .getOrNull()
        ?.takeIf { ids -> ids.isNotEmpty() && ids.all(availableIds::contains) } ?: return null
    return PreparedDeviceLayout(renderer, consumedIds)
  }

  companion object {
    val EMPTY = DeviceLayoutRendererRegistry(emptyList())
  }
}

/**
 * Renders a prepared compound/custom layout, then atomically renders capabilities it did not use.
 * Missing or rejected renderers render the complete generic capability list.
 */
@Composable
fun DeviceLayoutHost(
  device: DeviceUiModel,
  registry: DeviceLayoutRendererRegistry,
  controlState: DeviceControlUiState,
  onIntent: (DeviceIntent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val prepared = remember(device, registry) { registry.prepare(device) }
  Column(modifier = modifier) {
    if (prepared != null) {
      prepared.renderer.Content(
        device = device,
        controlState = controlState,
        onIntent = onIntent,
        modifier = Modifier,
      )
    }
    val consumedCapabilityIds = prepared?.consumedCapabilityIds.orEmpty()
    val remaining =
      device.capabilities.filter { capability -> capability.id !in consumedCapabilityIds }
    if (remaining.isNotEmpty() || prepared == null) {
      DeviceCapabilityList(
        device = device,
        controlState = controlState,
        onIntent = onIntent,
        modifier = Modifier.padding(top = 16.dp),
        capabilityIds = remaining.mapTo(linkedSetOf()) { capability -> capability.id },
        sectionTitle = remaining.genericSectionTitle(),
        showEmptyFallback = prepared == null,
      )
    }
  }
}

internal data class PreparedDeviceLayout(
  val renderer: DeviceLayoutRenderer,
  val consumedCapabilityIds: Set<CapabilityId>,
)

private fun List<CapabilityUiModel>.genericSectionTitle(): String? {
  if (isEmpty()) return null
  val writableCount = count(CapabilityUiModel::writable)
  return when {
    writableCount == 0 -> "At a glance"
    writableCount == 1 && size == 1 -> "Control"
    writableCount == size -> "Controls"
    else -> "Controls and readings"
  }
}
