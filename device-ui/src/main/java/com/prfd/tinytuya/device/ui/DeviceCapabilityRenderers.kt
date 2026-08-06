package com.prfd.tinytuya.device.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.prfd.tinytuya.device.core.capability.CapabilityId
import com.prfd.tinytuya.device.core.capability.CapabilityTone
import com.prfd.tinytuya.device.core.capability.DeviceIntent
import com.prfd.tinytuya.device.core.capability.TuyaHsvColor
import kotlin.math.roundToInt

/**
 * Generic renderer for the bounded capability vocabulary.
 *
 * The renderer knows only safe display models and emits semantic intents. It cannot select a DPS,
 * transport, device address, or credential.
 */
@Composable
fun DeviceCapabilityList(
  device: DeviceUiModel,
  controlState: DeviceControlUiState,
  onIntent: (DeviceIntent) -> Unit,
  modifier: Modifier = Modifier,
  capabilityIds: Set<CapabilityId>? = null,
  sectionTitle: String? = null,
  showEmptyFallback: Boolean = false,
) {
  val capabilities =
    if (capabilityIds == null) {
      device.capabilities
    } else {
      device.capabilities.filter { capability -> capability.id in capabilityIds }
    }
  Column(modifier = modifier.fillMaxWidth()) {
    sectionTitle?.let { title ->
      Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(bottom = 5.dp),
      )
    }
    capabilities.forEach { capability ->
      CapabilityRenderer(
        deviceId = device.deviceId,
        capability = capability,
        controlState = controlState,
        onIntent = onIntent,
      )
    }
    if (capabilities.isEmpty() && showEmptyFallback) {
      EmptyCapabilityFallback()
    }
  }
}

/** A safe default card for profiles which do not need a compound or custom arrangement. */
@Composable
fun GenericDeviceCard(
  title: String,
  device: DeviceUiModel,
  controlState: DeviceControlUiState,
  onIntent: (DeviceIntent) -> Unit,
  modifier: Modifier = Modifier,
) {
  OutlinedCard(modifier = modifier.fillMaxWidth()) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
      Text(text = title, style = MaterialTheme.typography.titleMedium)
      DeviceCapabilityList(
        device = device,
        controlState = controlState,
        onIntent = onIntent,
        modifier = Modifier.padding(top = 8.dp),
        showEmptyFallback = true,
      )
    }
  }
}

@Composable
fun ToggleCapability(
  deviceId: String,
  capability: ToggleUiModel,
  controlState: DeviceControlUiState,
  onIntent: (DeviceIntent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val presentation = controlPresentation(deviceId, capability, controlState)
  CapabilityRow(
    label = capability.label,
    supportingText = presentation.supportingText,
    isError = presentation.isError,
    progress = presentation.showProgress,
    modifier = modifier,
  ) {
    Switch(
      checked = capability.currentValue,
      onCheckedChange = { requestedValue ->
        onIntent(DeviceIntent.SetToggle(deviceId, capability.id, requestedValue))
      },
      enabled = presentation.enabled,
      modifier =
        Modifier.semantics {
            contentDescription =
              "${capability.label}, ${if (capability.currentValue) "on" else "off"}"
          }
          .testTag(capabilityTag("toggle", capability.id)),
    )
  }
}

@Composable
fun RangeCapability(
  deviceId: String,
  capability: RangeUiModel,
  controlState: DeviceControlUiState,
  onIntent: (DeviceIntent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val presentation = controlPresentation(deviceId, capability, controlState)
  var pendingValue by
    remember(capability.id, capability.currentValue) { mutableIntStateOf(capability.currentValue) }
  val intervals =
    (capability.maximum.toLong() - capability.minimum.toLong()) / capability.step.toLong()
  val progress =
    ((pendingValue.toLong() - capability.minimum.toLong()).toDouble() /
        (capability.maximum.toLong() - capability.minimum.toLong()).toDouble())
      .toFloat()
  Column(modifier = modifier.fillMaxWidth().padding(top = 9.dp, bottom = 3.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = capability.label,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.weight(1f),
      )
      if (presentation.showProgress) {
        CapabilityProgress()
      }
      Text(
        text =
          if (pendingValue == capability.currentValue) {
            capability.displayValue
          } else {
            pendingValue.toString()
          },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Slider(
      value = progress,
      onValueChange = { requestedProgress ->
        pendingValue = alignRangeProgress(requestedProgress, capability)
      },
      onValueChangeFinished = {
        if (pendingValue != capability.currentValue) {
          onIntent(DeviceIntent.SetRange(deviceId, capability.id, pendingValue))
        }
      },
      valueRange = 0f..1f,
      steps = rangeSliderVisualSteps(intervals),
      enabled = presentation.enabled,
      modifier =
        Modifier.semantics { contentDescription = capability.label }
          .testTag(capabilityTag("range", capability.id)),
    )
    CapabilitySupportingText(presentation)
  }
}

@Composable
fun ChoiceCapability(
  deviceId: String,
  capability: ChoiceUiModel,
  controlState: DeviceControlUiState,
  onIntent: (DeviceIntent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val presentation = controlPresentation(deviceId, capability, controlState)
  CapabilityGroup(
    label = capability.label,
    currentValue = capability.currentLabel,
    presentation = presentation,
    modifier = modifier.testTag(capabilityTag("choice", capability.id)),
  ) {
    capability.choices.forEach { choice ->
      val selected = choice.wireValue == capability.currentWireValue
      if (selected) {
        Button(
          onClick = { onIntent(DeviceIntent.SetChoice(deviceId, capability.id, choice.wireValue)) },
          enabled = presentation.enabled,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(choice.label)
        }
      } else {
        OutlinedButton(
          onClick = { onIntent(DeviceIntent.SetChoice(deviceId, capability.id, choice.wireValue)) },
          enabled = presentation.enabled,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(choice.label)
        }
      }
    }
  }
}

@Composable
fun ActionGroupCapability(
  deviceId: String,
  capability: ActionGroupUiModel,
  controlState: DeviceControlUiState,
  onIntent: (DeviceIntent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val presentation = controlPresentation(deviceId, capability, controlState)
  CapabilityGroup(
    label = capability.label,
    currentValue = null,
    presentation = presentation,
    modifier = modifier.testTag(capabilityTag("actions", capability.id)),
  ) {
    capability.actions.forEach { action ->
      OutlinedButton(
        onClick = {
          onIntent(DeviceIntent.InvokeAction(deviceId, capability.id, action.wireValue))
        },
        enabled = presentation.enabled,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(action.label)
      }
    }
  }
}

@Composable
fun ColorCapability(
  deviceId: String,
  capability: ColorUiModel,
  controlState: DeviceControlUiState,
  onIntent: (DeviceIntent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val presentation = controlPresentation(deviceId, capability, controlState)
  var hue by remember(capability.id, capability.hue) { mutableIntStateOf(capability.hue) }
  var saturation by
    remember(capability.id, capability.saturation) { mutableIntStateOf(capability.saturation) }
  var brightness by
    remember(capability.id, capability.brightness) { mutableIntStateOf(capability.brightness) }
  fun submit() {
    if (
      hue == capability.hue &&
        saturation == capability.saturation &&
        brightness == capability.brightness
    ) {
      return
    }
    onIntent(
      DeviceIntent.SetColor(
        deviceId,
        capability.id,
        TuyaHsvColor(hue, saturation, brightness),
      )
    )
  }
  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .padding(top = 9.dp, bottom = 3.dp)
        .testTag(capabilityTag("color", capability.id))
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = capability.label,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.weight(1f),
      )
      if (presentation.showProgress) CapabilityProgress()
      Text(
        text = "H $hue · S ${saturation / 10}% · B ${brightness / 10}%",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    ColorSlider("Hue", hue, 360, presentation.enabled, { hue = it }, ::submit)
    ColorSlider(
      "Saturation",
      saturation,
      1_000,
      presentation.enabled,
      { saturation = it },
      ::submit,
    )
    ColorSlider(
      "Brightness",
      brightness,
      1_000,
      presentation.enabled,
      { brightness = it },
      ::submit,
    )
    CapabilitySupportingText(presentation)
  }
}

@Composable
fun MeasurementCapability(
  capability: MeasurementUiModel,
  modifier: Modifier = Modifier,
) {
  ReadOnlyCapabilityRow(
    label = capability.label,
    value = capability.displayValue,
    modifier = modifier.testTag(capabilityTag("measurement", capability.id)),
  )
}

@Composable
fun BinaryStateCapability(
  capability: BinaryStateUiModel,
  modifier: Modifier = Modifier,
) {
  val color =
    when (capability.tone) {
      CapabilityTone.ALERT -> MaterialTheme.colorScheme.error
      CapabilityTone.ACTIVE -> MaterialTheme.colorScheme.primary
      CapabilityTone.NEUTRAL,
      CapabilityTone.NORMAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
  ReadOnlyCapabilityRow(
    label = capability.label,
    value = capability.value,
    valueColor = color,
    modifier = modifier.testTag(capabilityTag("binary", capability.id)),
  )
}

@Composable
fun SafeTextCapability(
  capability: SafeTextUiModel,
  modifier: Modifier = Modifier,
) {
  ReadOnlyCapabilityRow(
    label = capability.label,
    value = capability.value,
    modifier = modifier.testTag(capabilityTag("text", capability.id)),
  )
}

@Composable
private fun CapabilityRenderer(
  deviceId: String,
  capability: CapabilityUiModel,
  controlState: DeviceControlUiState,
  onIntent: (DeviceIntent) -> Unit,
) {
  when (capability) {
    is ToggleUiModel -> ToggleCapability(deviceId, capability, controlState, onIntent)
    is RangeUiModel -> RangeCapability(deviceId, capability, controlState, onIntent)
    is ChoiceUiModel -> ChoiceCapability(deviceId, capability, controlState, onIntent)
    is ActionGroupUiModel -> ActionGroupCapability(deviceId, capability, controlState, onIntent)
    is ColorUiModel -> ColorCapability(deviceId, capability, controlState, onIntent)
    is MeasurementUiModel -> MeasurementCapability(capability)
    is BinaryStateUiModel -> BinaryStateCapability(capability)
    is SafeTextUiModel -> SafeTextCapability(capability)
  }
}

@Composable
private fun CapabilityRow(
  label: String,
  supportingText: String?,
  isError: Boolean,
  progress: Boolean,
  modifier: Modifier,
  trailing: @Composable () -> Unit,
) {
  Row(
    modifier = modifier.fillMaxWidth().padding(top = 7.dp, bottom = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(text = label, style = MaterialTheme.typography.titleMedium)
      supportingText?.let { text ->
        Text(
          text = text,
          style = MaterialTheme.typography.bodySmall,
          color =
            if (isError) {
              MaterialTheme.colorScheme.error
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
          modifier = Modifier.padding(top = 2.dp),
        )
      }
    }
    Spacer(Modifier.width(10.dp))
    if (progress) {
      CapabilityProgress()
      Spacer(Modifier.width(10.dp))
    }
    trailing()
  }
}

@Composable
private fun CapabilityGroup(
  label: String,
  currentValue: String?,
  presentation: ControlPresentation,
  modifier: Modifier,
  content: @Composable () -> Unit,
) {
  Column(
    modifier = modifier.fillMaxWidth().padding(top = 9.dp, bottom = 3.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.weight(1f),
      )
      if (presentation.showProgress) CapabilityProgress()
      currentValue?.let { value ->
        Text(
          text = value,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 8.dp),
        )
      }
    }
    content()
    CapabilitySupportingText(presentation)
  }
}

@Composable
private fun ColorSlider(
  label: String,
  value: Int,
  maximum: Int,
  enabled: Boolean,
  onValueChange: (Int) -> Unit,
  onValueChangeFinished: () -> Unit,
) {
  Text(
    text = label,
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
  Slider(
    value = value.toFloat(),
    onValueChange = { onValueChange(it.roundToInt().coerceIn(0, maximum)) },
    onValueChangeFinished = onValueChangeFinished,
    valueRange = 0f..maximum.toFloat(),
    enabled = enabled,
    modifier = Modifier.semantics { contentDescription = label },
  )
}

@Composable
private fun ReadOnlyCapabilityRow(
  label: String,
  value: String,
  modifier: Modifier,
  valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
  Row(
    modifier =
      modifier.fillMaxWidth().padding(vertical = 7.dp).semantics {
        contentDescription = "$label, $value"
      },
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(text = label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
    Text(text = value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
  }
}

@Composable
private fun CapabilitySupportingText(presentation: ControlPresentation) {
  presentation.supportingText?.let { text ->
    Text(
      text = text,
      style = MaterialTheme.typography.bodySmall,
      color =
        if (presentation.isError) {
          MaterialTheme.colorScheme.error
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
      modifier = Modifier.padding(top = 2.dp),
    )
  }
}

@Composable
private fun CapabilityProgress() {
  CircularProgressIndicator(
    modifier = Modifier.size(22.dp).testTag("capability_control_progress"),
    strokeWidth = 2.5.dp,
  )
}

@Composable
private fun EmptyCapabilityFallback() {
  Text(
    text = "No compatible controls or readings are available for this device profile.",
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 4.dp).testTag("device_capability_fallback"),
  )
}

internal data class ControlPresentation(
  val enabled: Boolean,
  val showProgress: Boolean,
  val supportingText: String?,
  val isError: Boolean,
)

internal fun controlPresentation(
  deviceId: String,
  capability: CapabilityUiModel,
  state: DeviceControlUiState,
): ControlPresentation {
  val applies = state.appliesTo(deviceId, capability.id)
  val sending = state is DeviceControlUiState.Sending
  val supportingText =
    when {
      !capability.writable -> "Read only"
      state is DeviceControlUiState.Unavailable -> "Refresh status to enable control."
      applies && state is DeviceControlUiState.Sending -> state.intent.sendingMessage
      applies && state is DeviceControlUiState.Confirmed -> "Confirmed directly by the device."
      applies && state is DeviceControlUiState.Error -> state.message
      else -> null
    }
  return ControlPresentation(
    enabled = capability.writable && state !is DeviceControlUiState.Unavailable && !sending,
    showProgress = applies && sending,
    supportingText = supportingText,
    isError = applies && state is DeviceControlUiState.Error,
  )
}

private fun DeviceControlUiState.appliesTo(deviceId: String, capabilityId: CapabilityId): Boolean =
  when (this) {
    is DeviceControlUiState.Sending ->
      this.deviceId == deviceId && this.capabilityId == capabilityId
    is DeviceControlUiState.Confirmed ->
      this.deviceId == deviceId && this.capabilityId == capabilityId
    is DeviceControlUiState.Error -> this.deviceId == deviceId && this.capabilityId == capabilityId
    DeviceControlUiState.Ready,
    DeviceControlUiState.Unavailable -> false
  }

private fun alignRangeProgress(progress: Float, capability: RangeUiModel): Int {
  val span = capability.maximum.toLong() - capability.minimum.toLong()
  val approximateValue = capability.minimum.toDouble() + span.toDouble() * progress
  val stepIndex =
    ((approximateValue - capability.minimum.toDouble()) / capability.step.toDouble()).roundToInt()
  return (capability.minimum.toLong() + stepIndex.toLong() * capability.step.toLong())
    .coerceIn(capability.minimum.toLong(), capability.maximum.toLong())
    .toInt()
}

private fun capabilityTag(kind: String, id: CapabilityId): String =
  "capability_${kind}_${id.value.replace('.', '_')}"

private val DeviceIntent.sendingMessage: String
  get() =
    when (this) {
      is DeviceIntent.SetToggle ->
        if (value) {
          "Turning on and confirming…"
        } else {
          "Turning off and confirming…"
        }
      is DeviceIntent.SetRange -> "Updating value and confirming…"
      is DeviceIntent.SetChoice -> "Changing selection and confirming…"
      is DeviceIntent.InvokeAction -> "Sending action and confirming…"
      is DeviceIntent.SetColor -> "Updating color and confirming…"
    }

/**
 * Material renders one tick for every discrete slider step. Fine-grained ranges still snap through
 * [alignRangeProgress], but rendering all of their ticks creates a dense dotted track.
 */
internal fun rangeSliderVisualSteps(intervals: Long): Int =
  if (intervals in 2L..MAX_VISIBLE_SLIDER_INTERVALS) {
    (intervals - 1L).toInt()
  } else {
    0
  }

private const val MAX_VISIBLE_SLIDER_INTERVALS = 10L
