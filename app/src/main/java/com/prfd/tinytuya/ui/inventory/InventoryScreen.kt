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
import androidx.compose.material3.CircularProgressIndicator
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
import com.prfd.tinytuya.data.local.LanDeviceRecord
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.data.python.SensitiveString
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import com.prfd.tinytuya.ui.app.LanDiscoveryUiState
import com.prfd.tinytuya.ui.theme.TinytuyaTheme
import java.text.DateFormat
import java.util.Date

@Composable
fun InventoryScreen(
    catalog: DeviceCatalog,
    discovery: LanDiscoveryUiState,
    onDiscoverLan: () -> Unit,
    onImportFromCloud: () -> Unit,
    onDeleteAllLocalData: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val knownIds = remember(catalog.devices) { catalog.devices.mapTo(mutableSetOf()) { it.id } }
    val unmatchedLanDevices = remember(
        catalog.devices,
        catalog.lanDevices,
        catalog.lastDiscoveryAtEpochMillis,
    ) {
        catalog.lanDevices.filter { record ->
            record.id !in knownIds &&
                record.lastSeenAtEpochMillis == catalog.lastDiscoveryAtEpochMillis
        }
    }
    val isScanning = discovery is LanDiscoveryUiState.Scanning

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
                    LanDiscoveryCard(
                        catalog = catalog,
                        discovery = discovery,
                        onDiscoverLan = onDiscoverLan,
                    )
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
                    InventoryDeviceCard(
                        device = device,
                        lastDiscoveryAtEpochMillis = catalog.lastDiscoveryAtEpochMillis,
                        lanRecord = catalog.lanDevices.firstOrNull { it.id == device.id },
                    )
                }
                if (unmatchedLanDevices.isNotEmpty()) {
                    item {
                        Text(
                            text = "DISCOVERED WITHOUT CLOUD KEY",
                            style = MaterialTheme.typography.labelLarge,
                            fontSize = 11.sp,
                            letterSpacing = 1.5.sp,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                    }
                    items(
                        items = unmatchedLanDevices,
                        key = { "unmatched-${it.id}" },
                    ) { device ->
                        UnmatchedLanDeviceCard(device)
                    }
                }
                item {
                    DataControls(
                        enabled = !isScanning,
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
    val discoveredIds = remember(catalog.lanDevices, catalog.lastDiscoveryAtEpochMillis) {
        catalog.lanDevices
            .filter { it.lastSeenAtEpochMillis == catalog.lastDiscoveryAtEpochMillis }
            .mapTo(mutableSetOf()) { it.id }
    }
    val matchedCount = catalog.devices.count { it.id in discoveredIds }
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
            text = when {
                catalog.lastDiscoveryAtEpochMillis != null && catalog.devices.size == 1 ->
                    "$matchedCount of 1 secured device was found on this Wi-Fi."
                catalog.lastDiscoveryAtEpochMillis != null ->
                    "$matchedCount of ${catalog.devices.size} secured devices were found on this Wi-Fi."
                catalog.devices.size == 1 ->
                    "1 device is secured and ready for local discovery."
                else ->
                    "${catalog.devices.size} devices are secured and ready for local discovery."
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
private fun LanDiscoveryCard(
    catalog: DeviceCatalog,
    discovery: LanDiscoveryUiState,
    onDiscoverLan: () -> Unit,
) {
    val lastScan = remember(catalog.lastDiscoveryAtEpochMillis) {
        catalog.lastDiscoveryAtEpochMillis?.let { timestamp ->
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(timestamp))
        }
    }
    val currentDeviceCount = catalog.lanDevices.count {
        it.lastSeenAtEpochMillis == catalog.lastDiscoveryAtEpochMillis
    }
    val isScanning = discovery is LanDiscoveryUiState.Scanning
    val error = discovery as? LanDiscoveryUiState.Error

    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                        if (isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.5.dp,
                            )
                        } else {
                            Text("LAN", style = MaterialTheme.typography.labelLarge, fontSize = 10.sp)
                        }
                    }
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = when {
                            isScanning -> "Listening for Tuya devices"
                            error != null -> lanErrorTitle(error.code)
                            lastScan != null && currentDeviceCount == 1 ->
                                "1 device heard on the LAN"
                            lastScan != null -> "$currentDeviceCount devices heard on the LAN"
                            else -> "Find devices on this Wi-Fi"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = when {
                            isScanning ->
                                "Listening on UDP 6666, 6667, and 7000 for up to six seconds."
                            error != null -> error.message
                            lastScan != null ->
                                "Last scan $lastScan. Only local broadcasts were used."
                            else ->
                                "Match the encrypted cloud inventory to devices broadcasting on the phone's current Wi-Fi."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (error != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(top = 5.dp),
                    )
                    if (error != null) {
                        Text(
                            text = "Reference · ${error.code}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 7.dp),
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = onDiscoverLan,
                enabled = !isScanning,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .height(50.dp)
                    .testTag("lan_scan_button"),
            ) {
                Text(if (lastScan == null) "Scan this Wi-Fi" else "Scan again")
            }
        }
    }
}

@Composable
private fun InventoryDeviceCard(
    device: CloudImportedDevice,
    lastDiscoveryAtEpochMillis: Long?,
    lanRecord: LanDeviceRecord?,
) {
    val lanStatus = when {
        lastDiscoveryAtEpochMillis == null -> "LAN scan pending" to false
        lanRecord?.lastSeenAtEpochMillis == lastDiscoveryAtEpochMillis -> "On local network" to true
        else -> "Not found in last scan" to false
    }
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
                StatusPill(text = lanStatus.first, positive = lanStatus.second)
            }
        }
    }
}

@Composable
private fun UnmatchedLanDeviceCard(device: LanDeviceRecord) {
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                        Text("TU", style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Unlinked Tuya device",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = buildString {
                            append("Found at ${device.ip}")
                            if (device.protocolVersion.isNotBlank()) {
                                append(" · protocol ${device.protocolVersion}")
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusPill(text = "Cloud key unavailable", positive = false)
                StatusPill(text = "On local network", positive = true)
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
    enabled: Boolean,
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
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
        ) {
            Text("Import or sync from Tuya")
        }
        TextButton(
            onClick = onDeleteAllLocalData,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
        ) {
            Text(
                "Delete all local data",
                color = if (enabled) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
        }
    }
}

private fun lanErrorTitle(code: String): String = when (code) {
    "LAN_WIFI_UNAVAILABLE" -> "Connect to your device Wi-Fi"
    "LAN_PERMISSION_DENIED" -> "Local network access was blocked"
    "LAN_PORT_UNAVAILABLE" -> "Discovery ports are busy"
    "LAN_NETWORK_UNAVAILABLE" -> "Wi-Fi changed during the scan"
    "CATALOG_MISSING", "CATALOG_WRITE_FAILED", "CATALOG_ENCRYPT_FAILED" ->
        "Discovery could not be saved"
    else -> "Local discovery did not complete"
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
                schemaVersion = 2,
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
            discovery = LanDiscoveryUiState.Idle,
            onDiscoverLan = {},
            onImportFromCloud = {},
            onDeleteAllLocalData = {},
        )
    }
}
