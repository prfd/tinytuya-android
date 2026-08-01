package com.prfd.tinytuya.ui.inventory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prfd.tinytuya.data.local.DeviceCatalog
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.data.python.SensitiveString
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import com.prfd.tinytuya.ui.theme.TinytuyaTheme
import java.text.DateFormat
import java.util.Date

@Composable
fun InventoryScreen(
    catalog: DeviceCatalog,
    onImportFromCloud: () -> Unit,
    onDeleteAllLocalData: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp)
                    .testTag("inventory_list"),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    InventoryHeader(catalog)
                }
                item {
                    LocalSecurityCard(catalog)
                }
                item {
                    DiscoveryNextCard()
                }
                item {
                    Text(
                        text = "DEVICE INVENTORY",
                        style = MaterialTheme.typography.labelLarge,
                        fontSize = 11.sp,
                        letterSpacing = 1.5.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
                items(
                    items = catalog.devices,
                    key = { it.id },
                ) { device ->
                    InventoryDeviceCard(device)
                }
                item {
                    DataControls(
                        onImportFromCloud = onImportFromCloud,
                        onDeleteAllLocalData = { confirmDelete = true },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all local data?") },
            text = {
                Text(
                    "This removes the encrypted device catalog and its Android Keystore key. " +
                        "You will need to import from Tuya again."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        onDeleteAllLocalData()
                    }
                ) {
                    Text("Delete data")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun InventoryHeader(catalog: DeviceCatalog) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Text("T", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("TinyTuya", style = MaterialTheme.typography.titleLarge)
                Text(
                    "LOCAL HOME",
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 10.sp,
                    letterSpacing = 1.7.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(
            text = "Your local home",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = when (catalog.devices.size) {
                1 -> "1 device is secured and ready for local discovery."
                else -> "${catalog.devices.size} devices are secured and ready for local discovery."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun LocalSecurityCard(catalog: DeviceCatalog) {
    val importedAt = remember(catalog.importedAtEpochMillis) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(catalog.importedAtEpochMillis))
    }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(Modifier.width(10.dp))
                Text("Encrypted on this device", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                text = "Local keys are protected by Android Keystore and excluded from backup. Cloud credentials were not saved.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                modifier = Modifier.padding(top = 7.dp),
            )
            Text(
                text = "Imported $importedAt · ${catalog.region.displayName}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun DiscoveryNextCard() {
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.Top) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ) {
                Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                    Text("LAN", style = MaterialTheme.typography.labelLarge, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.width(13.dp))
            Column {
                Text("Local discovery comes next", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "The next implementation slice will match these cloud records to devices broadcasting on your current Wi-Fi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun InventoryDeviceCard(device: CloudImportedDevice) {
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = device.category.take(2).uppercase().ifBlank { "TU" },
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = device.name.ifBlank { "Unnamed Tuya device" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = deviceDescription(device),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                modifier = Modifier.padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusPill(
                    text = if (device.localKey.isBlank) "Local key missing" else "Key secured",
                    positive = !device.localKey.isBlank,
                )
                StatusPill(text = "LAN scan pending", positive = false)
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, positive: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (positive) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (positive) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun DataControls(
    onImportFromCloud: () -> Unit,
    onDeleteAllLocalData: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 18.dp),
    ) {
        Text("Data controls", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "A cloud sync always asks for credentials again because TinyTuya does not retain them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
        )
        OutlinedButton(
            onClick = onImportFromCloud,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
        ) {
            Text("Import or sync from Tuya")
        }
        TextButton(
            onClick = onDeleteAllLocalData,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
        ) {
            Text("Delete all local data", color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun deviceDescription(device: CloudImportedDevice): String =
    listOf(device.productName, device.model, device.category)
        .firstOrNull { it.isNotBlank() }
        ?: "Unknown device type"

private val TuyaCloudRegion.displayName: String
    get() = when (this) {
        TuyaCloudRegion.CHINA -> "Mainland China"
        TuyaCloudRegion.WESTERN_AMERICA -> "Western America"
        TuyaCloudRegion.EASTERN_AMERICA -> "Eastern America"
        TuyaCloudRegion.CENTRAL_EUROPE -> "Central Europe"
        TuyaCloudRegion.WESTERN_EUROPE -> "Western Europe"
        TuyaCloudRegion.INDIA -> "India"
        TuyaCloudRegion.SINGAPORE -> "Singapore"
    }

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun InventoryPreview() {
    TinytuyaTheme(darkTheme = true) {
        InventoryScreen(
            catalog = DeviceCatalog(
                schemaVersion = 1,
                importedAtEpochMillis = 1_753_981_200_000L,
                region = TuyaCloudRegion.WESTERN_AMERICA,
                devices = listOf(
                    CloudImportedDevice(
                        id = "preview-device",
                        name = "Reading lamp",
                        localKey = SensitiveString.of("preview-secret"),
                        category = "dj",
                        productId = "",
                        productName = "Wi-Fi lamp",
                        model = "L1",
                        mac = "",
                        uuid = "",
                        isSubDevice = false,
                        gatewayId = "",
                        nodeId = "",
                        protocolVersion = "3.5",
                        lastIp = "",
                        mappingJson = "{}",
                    )
                ),
            ),
            onImportFromCloud = {},
            onDeleteAllLocalData = {},
        )
    }
}
