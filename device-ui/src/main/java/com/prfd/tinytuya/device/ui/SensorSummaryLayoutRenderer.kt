package com.prfd.tinytuya.device.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prfd.tinytuya.device.core.capability.CapabilityId
import com.prfd.tinytuya.device.core.capability.CapabilityTone
import com.prfd.tinytuya.device.core.capability.DeviceIntent
import com.prfd.tinytuya.device.core.profile.StandardDeviceLayoutIds

/** Reusable compact summary for sensors represented by measurements and bounded binary states. */
object SensorSummaryLayoutRenderer : DeviceLayoutRenderer {
    override val layoutId = StandardDeviceLayoutIds.SENSOR_SUMMARY

    override fun prepare(device: DeviceUiModel): Set<CapabilityId>? = device.sensorReadings()
        .take(MAX_SENSOR_READINGS)
        .mapTo(linkedSetOf()) { reading -> reading.id }
        .takeIf { capabilityIds -> capabilityIds.isNotEmpty() }

    @Composable
    override fun Content(
        device: DeviceUiModel,
        controlState: DeviceControlUiState,
        onIntent: (DeviceIntent) -> Unit,
        modifier: Modifier,
    ) {
        val readings = device.sensorReadings().take(MAX_SENSOR_READINGS)
        val primary = readings.first()
        val tone = (primary as? BinaryStateUiModel)?.tone ?: CapabilityTone.NEUTRAL
        val primaryContainerColor = when (tone) {
            CapabilityTone.NEUTRAL -> MaterialTheme.colorScheme.surface
            CapabilityTone.NORMAL -> MaterialTheme.colorScheme.primaryContainer
            CapabilityTone.ACTIVE -> MaterialTheme.colorScheme.tertiaryContainer
            CapabilityTone.ALERT -> MaterialTheme.colorScheme.errorContainer
        }
        val primaryContentColor = when (tone) {
            CapabilityTone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
            CapabilityTone.NORMAL -> MaterialTheme.colorScheme.onPrimaryContainer
            CapabilityTone.ACTIVE -> MaterialTheme.colorScheme.onTertiaryContainer
            CapabilityTone.ALERT -> MaterialTheme.colorScheme.onErrorContainer
        }
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .testTag("local_sensor_summary"),
        ) {
            Surface(
                color = primaryContainerColor,
                contentColor = primaryContentColor,
                shape = MaterialTheme.shapes.small,
                border = if (tone == CapabilityTone.NEUTRAL) {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(text = "Current reading", style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = primary.label,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                    Text(
                        text = primary.displayValue,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
            if (readings.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    readings.drop(1).forEach { reading ->
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.small,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.weight(1f),
                        ) {
                            Column(Modifier.padding(horizontal = 11.dp, vertical = 10.dp)) {
                                Text(
                                    text = reading.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = reading.displayValue,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val CapabilityUiModel.displayValue: String
    get() = when (this) {
        is MeasurementUiModel -> displayValue
        is BinaryStateUiModel -> value
        else -> error("Sensor summary preparation accepted an unsupported capability.")
    }

private fun DeviceUiModel.sensorReadings(): List<CapabilityUiModel> = capabilities.filter { capability ->
    capability is MeasurementUiModel || capability is BinaryStateUiModel
}

private const val MAX_SENSOR_READINGS = 3
