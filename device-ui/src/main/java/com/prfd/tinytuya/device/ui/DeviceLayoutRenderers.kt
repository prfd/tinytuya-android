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
 * Every renderer implements both the full inventory surface and the compact card surface.
 * [prepareFull] and [prepareCompact] are non-composable, deterministic selection steps; returning
 * null, throwing, naming an absent capability, or returning an empty set rejects the renderer for
 * that surface. A rejected full surface selects the generic atomic fallback; a rejected compact
 * surface renders no compact controls. Content composables receive no schema, DPS binding,
 * transport, network, catalog, or secret-bearing model and may emit only semantic [DeviceIntent]
 * values.
 */
interface DeviceLayoutRenderer {
  val layoutId: DeviceLayoutId

  fun prepareFull(device: DeviceUiModel): Set<CapabilityId>?

  fun prepareCompact(device: DeviceUiModel): Set<CapabilityId>?

  @Composable
  fun FullContent(
    device: DeviceUiModel,
    controlState: DeviceControlUiState,
    onIntent: (DeviceIntent) -> Unit,
    modifier: Modifier,
  )

  @Composable
  fun CompactContent(
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

  internal fun prepareFull(device: DeviceUiModel): PreparedDeviceLayout? =
    prepare(device) { renderer -> renderer.prepareFull(device) }

  internal fun prepareCompact(device: DeviceUiModel): PreparedDeviceLayout? =
    prepare(device) { renderer -> renderer.prepareCompact(device) }

  private fun prepare(
    device: DeviceUiModel,
    select: (DeviceLayoutRenderer) -> Set<CapabilityId>?,
  ): PreparedDeviceLayout? {
    val layoutId = device.layoutId ?: return null
    val renderer = byLayoutId[layoutId] ?: return null
    val availableIds = device.capabilities.mapTo(mutableSetOf()) { capability -> capability.id }
    val consumedIds =
      runCatching { select(renderer)?.toSet() }
        .getOrNull()
        ?.takeIf { ids -> ids.isNotEmpty() && ids.all(availableIds::contains) } ?: return null
    return PreparedDeviceLayout(renderer, consumedIds)
  }

  companion object {
    val EMPTY = DeviceLayoutRendererRegistry(emptyList())
  }
}

/**
 * Renders a prepared compound/custom full layout, then atomically renders capabilities it did not
 * use. Missing or rejected renderers render the complete generic capability list.
 */
@Composable
fun DeviceLayoutHost(
  device: DeviceUiModel,
  registry: DeviceLayoutRendererRegistry,
  controlState: DeviceControlUiState,
  onIntent: (DeviceIntent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val prepared = remember(device, registry) { registry.prepareFull(device) }
  Column(modifier = modifier) {
    prepared
      ?.renderer
      ?.FullContent(
        device = device,
        controlState = controlState,
        onIntent = onIntent,
        modifier = Modifier,
      )
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

/**
 * Renders the compact-card surface of a prepared renderer. Missing or rejected renderers render
 * nothing, preserving the compact card's summary-only behavior.
 */
@Composable
fun CompactDeviceLayoutHost(
  device: DeviceUiModel,
  registry: DeviceLayoutRendererRegistry,
  controlState: DeviceControlUiState,
  onIntent: (DeviceIntent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val prepared = remember(device, registry) { registry.prepareCompact(device) } ?: return
  prepared.renderer.CompactContent(
    device = device,
    controlState = controlState,
    onIntent = onIntent,
    modifier = modifier,
  )
}

internal data class PreparedDeviceLayout(
  val renderer: DeviceLayoutRenderer,
  val consumedCapabilityIds: Set<CapabilityId>,
)

internal fun List<CapabilityUiModel>.genericSectionTitle(): String? {
  if (isEmpty()) return null
  val writableCount = count(CapabilityUiModel::writable)
  return when (writableCount) {
    0 -> "At a glance"
    1 if size == 1 -> "Control"
    size -> "Controls"
    else -> "Controls and readings"
  }
}
