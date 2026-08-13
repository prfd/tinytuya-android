package com.prfd.tinytuya.device.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.prfd.tinytuya.device.core.capability.CapabilityId
import com.prfd.tinytuya.device.core.capability.DeviceIntent
import com.prfd.tinytuya.device.core.capability.TuyaHsvColor
import com.prfd.tinytuya.device.core.profile.StandardDeviceLayoutIds
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Current reusable compound arrangement for first-release Tuya HSV lights. */
object LightDeviceLayoutRenderer : DeviceLayoutRenderer {
  override val layoutId = StandardDeviceLayoutIds.LIGHT

  override fun prepareFull(device: DeviceUiModel): Set<CapabilityId>? {
    val power = device.capability<ToggleUiModel>(POWER_ID) ?: return null
    val mode = device.capability<ChoiceUiModel>(MODE_ID) ?: return null
    val modeValues = mode.choices.mapTo(mutableSetOf()) { choice -> choice.wireValue }
    if (WHITE_MODE !in modeValues || COLOR_MODE !in modeValues) return null
    return buildSet {
      add(power.id)
      add(mode.id)
      device.capability<RangeUiModel>(BRIGHTNESS_ID)?.let { add(it.id) }
      device.capability<RangeUiModel>(TEMPERATURE_ID)?.let { add(it.id) }
      device.capability<ColorUiModel>(COLOR_ID)?.let { add(it.id) }
      device.capability<MeasurementUiModel>(BRIGHTNESS_READING_ID)?.let { add(it.id) }
      device.capability<MeasurementUiModel>(TEMPERATURE_READING_ID)?.let { add(it.id) }
    }
  }

  @Composable
  override fun FullContent(
    device: DeviceUiModel,
    controlState: DeviceControlUiState,
    onIntent: (DeviceIntent) -> Unit,
    modifier: Modifier,
  ) {
    val power = requireNotNull(device.capability<ToggleUiModel>(POWER_ID))
    val mode = requireNotNull(device.capability<ChoiceUiModel>(MODE_ID))
    DeviceCapabilityList(
      device = device,
      controlState = controlState,
      onIntent = onIntent,
      modifier = modifier.padding(top = 16.dp),
      capabilityIds = setOf(power.id),
      sectionTitle = "Light controls",
    )
    val currentMode = mode.currentWireValue
    val canAdjust =
      power.currentValue &&
        controlState !is DeviceControlUiState.Unavailable &&
        controlState !is DeviceControlUiState.Sending
    val whiteChoice = mode.choices.firstOrNull { choice -> choice.wireValue == WHITE_MODE }
    val colorChoice = mode.choices.firstOrNull { choice -> choice.wireValue == COLOR_MODE }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag("light_controls")) {
      Text(text = "Mode", style = MaterialTheme.typography.titleSmall)
      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        whiteChoice?.let { choice ->
          LightModeButton(
            label = "White",
            selected = currentMode == choice.wireValue,
            enabled = canAdjust && mode.writable,
            modifier = Modifier.weight(1f),
            onClick = {
              onIntent(DeviceIntent.SetChoice(device.deviceId, mode.id, choice.wireValue))
            },
          )
        }
        colorChoice?.let { choice ->
          LightModeButton(
            label = "Color",
            selected = currentMode == choice.wireValue,
            enabled = canAdjust && mode.writable,
            modifier = Modifier.weight(1f),
            onClick = {
              onIntent(DeviceIntent.SetChoice(device.deviceId, mode.id, choice.wireValue))
            },
          )
        }
      }
      when {
        !power.currentValue ->
          Text(
            text = "Turn on the light to adjust its color and brightness.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
          )
        currentMode == WHITE_MODE -> {
          device.capability<RangeUiModel>(BRIGHTNESS_ID)?.let { capability ->
            LightRangeSlider(
              capability = capability,
              enabled = canAdjust && capability.writable,
              pending = controlState.isSending(device.deviceId, capability.id),
              onValueCommitted = { value ->
                onIntent(DeviceIntent.SetRange(device.deviceId, capability.id, value))
              },
            )
          }
          device.capability<RangeUiModel>(TEMPERATURE_ID)?.let { capability ->
            LightRangeSlider(
              capability = capability,
              enabled = canAdjust && capability.writable,
              pending = controlState.isSending(device.deviceId, capability.id),
              startLabel = "Warm",
              endLabel = "Cool",
              onValueCommitted = { value ->
                onIntent(DeviceIntent.SetRange(device.deviceId, capability.id, value))
              },
            )
          }
        }
        currentMode == COLOR_MODE ->
          device.capability<ColorUiModel>(COLOR_ID)?.let { capability ->
            LightColorPicker(
              capability = capability,
              enabled = canAdjust && capability.writable,
              pending = controlState.isSending(device.deviceId, capability.id),
              onColorCommitted = { color ->
                onIntent(DeviceIntent.SetColor(device.deviceId, capability.id, color))
              },
            )
          }
        else ->
          Text(
            text = "Scene and music modes are not controlled here. Choose White or Color.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
          )
      }
      LightControlFeedback(device.deviceId, controlState)
    }
  }

  /** Compact light layout intentionally exposes only the semantic power capability. */
  override fun prepareCompact(device: DeviceUiModel): Set<CapabilityId>? =
    device.capabilities
      .filterIsInstance<ToggleUiModel>()
      .singleOrNull { capability -> capability.id == POWER_ID }
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
        device.capabilities.filterIsInstance<ToggleUiModel>().singleOrNull { toggle ->
          toggle.id == POWER_ID
        }
      )
    CompactToggleButton(
      deviceId = device.deviceId,
      capability = capability,
      controlState = controlState,
      showLabel = false,
      onIntent = onIntent,
      modifier = modifier,
    )
  }
}

@Composable
private fun LightModeButton(
  label: String,
  selected: Boolean,
  enabled: Boolean,
  modifier: Modifier,
  onClick: () -> Unit,
) {
  val tag = "light_mode_${label.lowercase()}"
  if (selected) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.testTag(tag)) { Text(label) }
  } else {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.testTag(tag)) {
      Text(label)
    }
  }
}

@Composable
private fun LightRangeSlider(
  capability: RangeUiModel,
  enabled: Boolean,
  pending: Boolean,
  startLabel: String? = null,
  endLabel: String? = null,
  onValueCommitted: (Int) -> Unit,
) {
  var progress by
    remember(capability.id, capability.currentValue) {
      mutableFloatStateOf(capability.progressFor(capability.currentValue))
    }
  LaunchedEffect(capability.currentValue, pending) {
    if (!pending) progress = capability.progressFor(capability.currentValue)
  }
  val alignedValue = capability.valueFor(progress)
  val percentage = (progress * 100f).roundToInt().coerceIn(0, 100)
  Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(text = capability.label, style = MaterialTheme.typography.titleSmall)
      Text(
        text = "$percentage%",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
      )
    }
    Slider(
      value = progress,
      onValueChange = { progress = it },
      onValueChangeFinished = {
        val committedValue = capability.valueFor(progress)
        if (committedValue != capability.currentValue) {
          progress = capability.progressFor(committedValue)
          onValueCommitted(committedValue)
        }
      },
      valueRange = 0f..1f,
      enabled = enabled,
      modifier =
        Modifier.fillMaxWidth().testTag("light_slider_${capability.id.value.replace('.', '_')}"),
    )
    if (startLabel != null && endLabel != null) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = startLabel,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = endLabel,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun LightColorPicker(
  capability: ColorUiModel,
  enabled: Boolean,
  pending: Boolean,
  onColorCommitted: (TuyaHsvColor) -> Unit,
) {
  val currentColor = TuyaHsvColor(capability.hue, capability.saturation, capability.brightness)
  var selectedColor by remember(capability.id, currentColor) { mutableStateOf(currentColor) }
  LaunchedEffect(currentColor, pending) { if (!pending) selectedColor = currentColor }
  var wheelSize by remember { mutableStateOf(IntSize.Zero) }
  Column(
    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(text = capability.label, style = MaterialTheme.typography.titleSmall)
      Surface(
        color =
          Color.hsv(
            hue = selectedColor.hue.toFloat(),
            saturation = selectedColor.saturation / 1_000f,
            value = selectedColor.brightness.coerceAtLeast(100) / 1_000f,
          ),
        shape = CircleShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.size(24.dp),
        content = {},
      )
    }
    Canvas(
      modifier =
        Modifier.padding(top = 12.dp)
          .fillMaxWidth(0.72f)
          .sizeIn(maxWidth = 260.dp)
          .aspectRatio(1f)
          .onSizeChanged { wheelSize = it }
          .semantics { contentDescription = "Color wheel" }
          .testTag("light_color_wheel")
          .pointerInput(enabled, capability.id) {
            if (!enabled) return@pointerInput
            fun updateColor(position: Offset) {
              if (wheelSize == IntSize.Zero) return
              val centerX = wheelSize.width / 2f
              val centerY = wheelSize.height / 2f
              val deltaX = position.x - centerX
              val deltaY = position.y - centerY
              val radius = minOf(centerX, centerY)
              selectedColor =
                selectedColor.copy(
                  hue =
                    ((atan2(deltaY, deltaX) * 180f / PI.toFloat()) + 360f).rem(360f).roundToInt(),
                  saturation =
                    (hypot(deltaX, deltaY) / radius * 1_000f).roundToInt().coerceIn(0, 1_000),
                )
            }
            detectDragGestures(
              onDragStart = ::updateColor,
              onDragEnd = { if (selectedColor != currentColor) onColorCommitted(selectedColor) },
              onDrag = { change, _ ->
                change.consume()
                updateColor(change.position)
              },
            )
          }
    ) {
      drawCircle(
        brush =
          Brush.sweepGradient(
            listOf(
              Color.Red,
              Color.Yellow,
              Color.Green,
              Color.Cyan,
              Color.Blue,
              Color.Magenta,
              Color.Red,
            )
          )
      )
      drawCircle(
        brush =
          Brush.radialGradient(
            colors = listOf(Color.White, Color.Transparent),
            center = center,
            radius = size.minDimension / 2f,
          )
      )
      val radius = size.minDimension / 2f
      val pointerRadius = radius * selectedColor.saturation / 1_000f
      val angle = selectedColor.hue * PI.toFloat() / 180f
      val pointer =
        Offset(
          x = center.x + cos(angle) * pointerRadius,
          y = center.y + sin(angle) * pointerRadius,
        )
      drawCircle(
        color = Color.White,
        radius = 8.dp.toPx(),
        center = pointer,
        style = Stroke(width = 3.dp.toPx()),
      )
      drawCircle(
        color = Color.Black.copy(alpha = 0.7f),
        radius = 10.dp.toPx(),
        center = pointer,
        style = Stroke(width = 1.dp.toPx()),
      )
    }
    LightColorBrightnessSlider(
      color = selectedColor,
      enabled = enabled,
      pending = pending,
      onColorCommitted = onColorCommitted,
    )
  }
}

@Composable
private fun LightColorBrightnessSlider(
  color: TuyaHsvColor,
  enabled: Boolean,
  pending: Boolean,
  onColorCommitted: (TuyaHsvColor) -> Unit,
) {
  var brightness by remember(color) { mutableFloatStateOf(color.brightness.toFloat()) }
  LaunchedEffect(color.brightness, pending) {
    if (!pending) brightness = color.brightness.toFloat()
  }
  val alignedBrightness = brightness.roundToInt().coerceIn(10, 1_000)
  Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(text = "Brightness", style = MaterialTheme.typography.titleSmall)
      Text(
        text = "${(alignedBrightness / 10f).roundToInt()}%",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
      )
    }
    Slider(
      value = brightness.coerceIn(10f, 1_000f),
      onValueChange = { brightness = it },
      onValueChangeFinished = {
        val committedBrightness = brightness.roundToInt().coerceIn(10, 1_000)
        if (committedBrightness != color.brightness) {
          brightness = committedBrightness.toFloat()
          onColorCommitted(color.copy(brightness = committedBrightness))
        }
      },
      valueRange = 10f..1_000f,
      enabled = enabled,
      modifier = Modifier.fillMaxWidth().testTag("light_slider_color_brightness"),
    )
  }
}

@Composable
private fun LightControlFeedback(deviceId: String, controlState: DeviceControlUiState) {
  val intent =
    when (controlState) {
      is DeviceControlUiState.Sending -> controlState.intent
      is DeviceControlUiState.Confirmed -> controlState.intent
      is DeviceControlUiState.Error -> controlState.intent
      DeviceControlUiState.Ready,
      DeviceControlUiState.Unavailable -> null
    }?.takeIf { intent ->
      intent.deviceId == deviceId && intent.capabilityId.value.startsWith("light.")
    } ?: return
  val text =
    when (controlState) {
      is DeviceControlUiState.Sending ->
        when (intent) {
          is DeviceIntent.SetChoice -> "Changing mode and confirming…"
          is DeviceIntent.SetRange ->
            when (intent.capabilityId) {
              BRIGHTNESS_ID -> "Updating brightness and confirming…"
              TEMPERATURE_ID -> "Updating color temperature and confirming…"
              else -> "Updating value and confirming…"
            }
          is DeviceIntent.SetColor -> "Updating color and brightness…"
          else -> "Sending change and confirming…"
        }
      is DeviceControlUiState.Confirmed -> "Confirmed directly by the light."
      is DeviceControlUiState.Error -> controlState.message
      DeviceControlUiState.Ready,
      DeviceControlUiState.Unavailable -> return
    }
  Text(
    text = text,
    style = MaterialTheme.typography.bodySmall,
    color =
      if (controlState is DeviceControlUiState.Error) {
        MaterialTheme.colorScheme.error
      } else {
        MaterialTheme.colorScheme.onSurfaceVariant
      },
    modifier = Modifier.padding(top = 12.dp).testTag("light_control_feedback"),
  )
}

private inline fun <reified T : CapabilityUiModel> DeviceUiModel.capability(id: CapabilityId): T? =
  capabilities.filterIsInstance<T>().firstOrNull { capability -> capability.id == id }

private fun DeviceControlUiState.isSending(deviceId: String, capabilityId: CapabilityId): Boolean =
  this is DeviceControlUiState.Sending &&
    this.deviceId == deviceId &&
    this.capabilityId == capabilityId

private fun RangeUiModel.progressFor(value: Int): Float =
  ((value.toLong() - minimum.toLong()).toDouble() /
      (maximum.toLong() - minimum.toLong()).toDouble())
    .toFloat()

private fun RangeUiModel.valueFor(progress: Float): Int {
  val span = maximum.toLong() - minimum.toLong()
  val approximateValue = minimum.toDouble() + span.toDouble() * progress
  val stepIndex = ((approximateValue - minimum.toDouble()) / step.toDouble()).roundToInt()
  return (minimum.toLong() + stepIndex.toLong() * step.toLong())
    .coerceIn(minimum.toLong(), maximum.toLong())
    .toInt()
}

private const val WHITE_MODE = "white"
private const val COLOR_MODE = "colour"
private val POWER_ID = CapabilityId("power")
private val MODE_ID = CapabilityId("light.mode")
private val BRIGHTNESS_ID = CapabilityId("light.brightness")
private val TEMPERATURE_ID = CapabilityId("light.temperature")
private val COLOR_ID = CapabilityId("light.color")
private val BRIGHTNESS_READING_ID = CapabilityId("light.brightness.reading")
private val TEMPERATURE_READING_ID = CapabilityId("light.temperature.reading")
