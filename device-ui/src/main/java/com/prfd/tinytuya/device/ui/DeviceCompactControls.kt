/** Reusable compact controls and the compact-card surface of device layouts. */
package com.prfd.tinytuya.device.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prfd.tinytuya.device.core.capability.CapabilityId
import com.prfd.tinytuya.device.core.capability.DeviceIntent
import com.prfd.tinytuya.device.core.profile.StandardDeviceLayoutIds

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

@Composable
fun CompactToggleButton(
  deviceId: String,
  capability: ToggleUiModel,
  controlState: DeviceControlUiState,
  showLabel: Boolean,
  onIntent: (DeviceIntent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val presentation = controlPresentation(deviceId, capability, controlState)
  Column(
    modifier = modifier,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(modifier = Modifier.size(COMPACT_CONTROL_SIZE), contentAlignment = Alignment.Center) {
      Surface(
        modifier =
          Modifier.size(COMPACT_CONTROL_SIZE)
            .clip(CircleShape)
            .semantics { contentDescription = capability.label }
            .toggleable(
              value = capability.currentValue,
              enabled = presentation.enabled,
              role = Role.Switch,
              onValueChange = { requestedValue ->
                onIntent(DeviceIntent.SetToggle(deviceId, capability.id, requestedValue))
              },
            )
            .testTag(compactToggleTag(capability.id)),
        shape = CircleShape,
        color =
          if (capability.currentValue) {
            MaterialTheme.colorScheme.primaryContainer
          } else {
            MaterialTheme.colorScheme.surface
          },
        contentColor =
          if (capability.currentValue) {
            MaterialTheme.colorScheme.onPrimaryContainer
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
        border =
          BorderStroke(
            1.dp,
            if (presentation.isError) {
              MaterialTheme.colorScheme.error
            } else if (capability.currentValue) {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.outline
            },
          ),
      ) {
        Box(contentAlignment = Alignment.Center) { CompactPowerIcon(Modifier.size(27.dp)) }
      }
      if (presentation.showProgress) {
        CircularProgressIndicator(
          modifier = Modifier.size(COMPACT_CONTROL_SIZE).testTag("compact_control_progress"),
          strokeWidth = 2.5.dp,
        )
      }
    }
    if (showLabel) {
      Text(
        text = capability.label,
        style = MaterialTheme.typography.labelSmall,
        fontSize = 9.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp),
      )
    }
  }
}

@Composable
internal fun CompactActionButton(
  deviceId: String,
  capability: ActionGroupUiModel,
  action: ChoiceUiOption,
  controlState: DeviceControlUiState,
  onIntent: (DeviceIntent) -> Unit,
) {
  val presentation = controlPresentation(deviceId, capability, controlState)
  val isPending =
    controlState is DeviceControlUiState.Sending &&
      controlState.deviceId == deviceId &&
      controlState.capabilityId == capability.id &&
      (controlState.intent as? DeviceIntent.InvokeAction)?.wireValue == action.wireValue
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(modifier = Modifier.size(COMPACT_CONTROL_SIZE), contentAlignment = Alignment.Center) {
      Surface(
        modifier =
          Modifier.size(COMPACT_CONTROL_SIZE)
            .clip(CircleShape)
            .semantics {
              contentDescription = action.label
              role = Role.Button
            }
            .clickable(enabled = presentation.enabled) {
              onIntent(DeviceIntent.InvokeAction(deviceId, capability.id, action.wireValue))
            }
            .testTag(compactActionTag(action.label)),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
        border =
          BorderStroke(
            1.dp,
            if (presentation.isError) {
              MaterialTheme.colorScheme.error
            } else {
              MaterialTheme.colorScheme.outline
            },
          ),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Text(text = action.compactSymbol, style = MaterialTheme.typography.titleMedium)
        }
      }
      if (isPending) {
        CircularProgressIndicator(
          modifier = Modifier.size(COMPACT_CONTROL_SIZE).testTag("compact_control_progress"),
          strokeWidth = 2.5.dp,
        )
      }
    }
    Text(
      text = action.label,
      style = MaterialTheme.typography.labelSmall,
      fontSize = 9.sp,
      maxLines = 1,
      modifier = Modifier.padding(top = 2.dp),
    )
  }
}

@Composable
private fun CompactPowerIcon(modifier: Modifier = Modifier) {
  val color = LocalContentColor.current
  Canvas(modifier = modifier) {
    val strokeWidth = size.minDimension * 0.09f
    drawArc(
      color = color,
      startAngle = -40f,
      sweepAngle = 260f,
      useCenter = false,
      topLeft = Offset(size.width * 0.12f, size.height * 0.18f),
      size = Size(size.width * 0.76f, size.height * 0.76f),
      style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
    )
    drawLine(
      color = color,
      start = Offset(size.width * 0.5f, size.height * 0.08f),
      end = Offset(size.width * 0.5f, size.height * 0.48f),
      strokeWidth = strokeWidth,
      cap = StrokeCap.Round,
    )
  }
}

private val ChoiceUiOption.compactSymbol: String
  get() =
    when (label.lowercase()) {
      "open" -> "↑"
      "stop" -> "■"
      "close" -> "↓"
      else -> "•"
    }

private fun compactToggleTag(id: CapabilityId): String =
  "compact_toggle_${id.value.replace('.', '_')}"

private fun compactActionTag(label: String): String =
  "compact_action_${label.lowercase().replace(' ', '_')}"

private val COMPACT_CONTROL_SIZE = 48.dp
