package com.prfd.tinytuya.ui.inventory

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.prfd.tinytuya.data.local.DeviceCatalog
import com.prfd.tinytuya.data.local.LanDeviceRecord
import com.prfd.tinytuya.data.local.LocalStatusRecord
import com.prfd.tinytuya.data.lan.LocalBooleanControl
import com.prfd.tinytuya.data.lan.LocalDeviceAccessKind
import com.prfd.tinytuya.data.lan.LocalDeviceCapabilityRegistry
import com.prfd.tinytuya.data.lan.LocalDeviceProfile
import com.prfd.tinytuya.data.lan.LocalDeviceProfileKind
import com.prfd.tinytuya.data.lan.LocalLightColorControl
import com.prfd.tinytuya.data.lan.LocalLightControls
import com.prfd.tinytuya.data.lan.LocalLightHsv
import com.prfd.tinytuya.data.lan.LocalLightIntegerControl
import com.prfd.tinytuya.data.lan.LocalLightMode
import com.prfd.tinytuya.data.lan.LocalPollDeviceState
import com.prfd.tinytuya.data.lan.LocalSensorKind
import com.prfd.tinytuya.data.lan.hasCurrentKnownStatusTargets
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.data.python.SensitiveString
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import com.prfd.tinytuya.device.core.capability.CapabilityId
import com.prfd.tinytuya.device.core.capability.DeviceIntent
import com.prfd.tinytuya.device.core.capability.TuyaHsvColor
import com.prfd.tinytuya.device.ui.DeviceCapabilityList
import com.prfd.tinytuya.device.ui.DeviceControlUiState as LocalControlUiState
import com.prfd.tinytuya.device.ui.DeviceUiMapper
import com.prfd.tinytuya.device.ui.DeviceUiModel
import com.prfd.tinytuya.ui.app.LanDiscoveryUiState
import com.prfd.tinytuya.ui.app.LocalRefreshPhase
import com.prfd.tinytuya.ui.components.BrandMark
import com.prfd.tinytuya.ui.theme.TinytuyaTheme
import java.text.DateFormat
import java.util.Date
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun InventoryScreen(
    catalog: DeviceCatalog,
    discovery: LanDiscoveryUiState,
    control: LocalControlUiState,
    isLanSnapshotCurrent: Boolean = true,
    onRefreshKnownDevices: () -> Unit,
    onDiscoverLan: () -> Unit,
    onIntent: (DeviceIntent) -> Unit,
    onOpenSettings: () -> Unit,
    onImportFromCloud: () -> Unit,
    onDeleteAllLocalData: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val currentDiscoveryAtEpochMillis = catalog.lastDiscoveryAtEpochMillis
        .takeIf { isLanSnapshotCurrent }
    val knownIds = remember(catalog.devices) { catalog.devices.mapTo(mutableSetOf()) { it.id } }
    val unmatchedLanDevices = remember(
        catalog.devices,
        catalog.lanDevices,
        currentDiscoveryAtEpochMillis,
    ) {
        catalog.lanDevices.filter { record ->
            record.id !in knownIds &&
                record.lastSeenAtEpochMillis == currentDiscoveryAtEpochMillis
        }
    }
    val isBusy = discovery is LanDiscoveryUiState.Scanning ||
        discovery is LanDiscoveryUiState.ReadingStatus ||
        control is LocalControlUiState.Sending

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
                    InventoryHeader(
                        catalog = catalog,
                        onOpenSettings = onOpenSettings,
                    )
                }
                item {
                    FindDevicesCard(
                        catalog = catalog,
                        currentDiscoveryAtEpochMillis = currentDiscoveryAtEpochMillis,
                        discovery = discovery,
                        onDiscoverLan = onDiscoverLan,
                        isControlBusy = control is LocalControlUiState.Sending,
                    )
                }
                item {
                    DeviceInventoryHeader(
                        catalog = catalog,
                        currentDiscoveryAtEpochMillis = currentDiscoveryAtEpochMillis,
                        discovery = discovery,
                        isControlBusy = control is LocalControlUiState.Sending,
                        onRefreshKnownDevices = onRefreshKnownDevices,
                    )
                }
                items(
                    items = catalog.devices,
                    key = { it.id },
                ) { device ->
                    InventoryDeviceCard(
                        device = device,
                        lastDiscoveryAtEpochMillis = currentDiscoveryAtEpochMillis,
                        lanRecord = catalog.lanDevices.firstOrNull { it.id == device.id },
                        localStatus = catalog.localStatus.firstOrNull { it.id == device.id },
                        discovery = discovery,
                        control = control,
                        onIntent = onIntent,
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
                        enabled = !isBusy,
                        onImportFromCloud = onImportFromCloud,
                        onDeleteAllLocalData = { confirmDelete = true },
                    )
                }
                item {
                    LocalSecurityCard(catalog)
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
private fun InventoryHeader(
    catalog: DeviceCatalog,
    onOpenSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(Modifier.size(44.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("TinyTuya", style = MaterialTheme.typography.titleLarge)
                Text(
                    "LOCAL HOME",
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 10.sp,
                    letterSpacing = 1.7.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.testTag("open_settings_button"),
            ) {
                Text("Settings")
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(
            text = "Your local home",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = when {
                catalog.devices.size == 1 ->
                    "1 secured device is stored for private local control."
                else ->
                    "${catalog.devices.size} secured devices are stored for private local control."
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
        modifier = Modifier
            .fillMaxWidth()
            .testTag("local_security_card"),
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
private fun FindDevicesCard(
    catalog: DeviceCatalog,
    currentDiscoveryAtEpochMillis: Long?,
    discovery: LanDiscoveryUiState,
    onDiscoverLan: () -> Unit,
    isControlBusy: Boolean,
) {
    val lastScan = remember(catalog.lastDiscoveryAtEpochMillis) {
        catalog.lastDiscoveryAtEpochMillis?.let { timestamp ->
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(timestamp))
        }
    }
    val currentDeviceCount = catalog.lanDevices.count {
        it.lastSeenAtEpochMillis == currentDiscoveryAtEpochMillis
    }
    val isScanning = discovery is LanDiscoveryUiState.Scanning
    val isReadingStatus = discovery is LanDiscoveryUiState.ReadingStatus
    val isBusy = isScanning || isReadingStatus || isControlBusy
    val error = (discovery as? LanDiscoveryUiState.Error)
        ?.takeIf { it.phase == LocalRefreshPhase.DISCOVERY }

    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("find_devices_card"),
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
                            currentDiscoveryAtEpochMillis == null && lastScan != null ->
                                "Find devices on this Wi-Fi"
                            lastScan != null && currentDeviceCount == 0 ->
                                "No devices found in the last search"
                            lastScan != null && currentDeviceCount == 1 ->
                                "1 Tuya device found"
                            lastScan != null -> "$currentDeviceCount Tuya devices found"
                            else -> "Find devices on this Wi-Fi"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = when {
                            isScanning ->
                                "Listening on UDP 6666, 6667, and 7000 for up to twelve seconds."
                            error != null -> error.message
                            currentDiscoveryAtEpochMillis == null && lastScan != null ->
                                "The previous local snapshot is not verified on the active Wi-Fi."
                            lastScan != null ->
                                "Last searched $lastScan. Search again after a device or Wi-Fi address changes."
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
                enabled = !isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .height(50.dp)
                    .testTag("lan_scan_button"),
            ) {
                Text("Find devices")
            }
            Text(
                text = "Discovery · listens locally for new or changed addresses",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun DeviceInventoryHeader(
    catalog: DeviceCatalog,
    currentDiscoveryAtEpochMillis: Long?,
    discovery: LanDiscoveryUiState,
    isControlBusy: Boolean,
    onRefreshKnownDevices: () -> Unit,
) {
    val currentLanIds = remember(catalog.lanDevices, currentDiscoveryAtEpochMillis) {
        catalog.lanDevices
            .asSequence()
            .filter { record -> record.lastSeenAtEpochMillis == currentDiscoveryAtEpochMillis }
            .mapTo(mutableSetOf()) { record -> record.id }
    }
    val matchedCount = catalog.devices.count { device -> device.id in currentLanIds }
    val currentResponseCount = catalog.localStatus.count { status ->
        status.id in currentLanIds &&
            status.state == LocalPollDeviceState.RESPONDED &&
            status.polledAtEpochMillis >=
            (currentDiscoveryAtEpochMillis ?: Long.MAX_VALUE)
    }
    val currentStatusReadAt = catalog.lastLocalPollAtEpochMillis?.takeIf { timestamp ->
        currentDiscoveryAtEpochMillis != null && timestamp >= currentDiscoveryAtEpochMillis
    }?.let { timestamp ->
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(timestamp))
    }
    val canRefreshKnownDevices = currentDiscoveryAtEpochMillis != null &&
        catalog.hasCurrentKnownStatusTargets()
    val isScanning = discovery is LanDiscoveryUiState.Scanning
    val isReadingStatus = discovery is LanDiscoveryUiState.ReadingStatus
    val statusError = (discovery as? LanDiscoveryUiState.Error)
        ?.takeIf { it.phase == LocalRefreshPhase.STATUS }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp)
            .testTag("device_inventory_header"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "DEVICE INVENTORY",
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = when {
                        isReadingStatus ->
                            "Reading saved devices directly on this Wi-Fi…"
                        currentDiscoveryAtEpochMillis == null ->
                            "Find devices to match their current local addresses."
                        matchedCount == 0 ->
                            "No secured devices matched in the last search."
                        currentStatusReadAt != null && matchedCount == 1 ->
                            "$currentResponseCount of 1 device answered · $currentStatusReadAt"
                        currentStatusReadAt != null ->
                            "$currentResponseCount of $matchedCount devices answered · $currentStatusReadAt"
                        matchedCount == 1 ->
                            "1 device matched · Refresh to read its current status."
                        else ->
                            "$matchedCount devices matched · Refresh to read their current status."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (canRefreshKnownDevices) {
                Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    onClick = onRefreshKnownDevices,
                    enabled = !isScanning && !isReadingStatus && !isControlBusy,
                    contentPadding = PaddingValues(horizontal = 13.dp, vertical = 0.dp),
                    modifier = Modifier
                        .height(42.dp)
                        .testTag("inventory_refresh_button"),
                ) {
                    if (isReadingStatus) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (isReadingStatus) "Refreshing" else "Refresh status")
                }
            }
        }
        if (statusError != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .testTag("status_refresh_error"),
            ) {
                Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
                    Text(
                        text = lanErrorTitle(statusError.code),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = statusError.message,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Text(
                        text = "Reference · ${statusError.code}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun InventoryDeviceCard(
    device: CloudImportedDevice,
    lastDiscoveryAtEpochMillis: Long?,
    lanRecord: LanDeviceRecord?,
    localStatus: LocalStatusRecord?,
    discovery: LanDiscoveryUiState,
    control: LocalControlUiState,
    onIntent: (DeviceIntent) -> Unit,
) {
    val isOnCurrentLan = lastDiscoveryAtEpochMillis != null &&
        lanRecord?.lastSeenAtEpochMillis == lastDiscoveryAtEpochMillis
    val isCurrentStatus = isOnCurrentLan &&
        localStatus != null &&
        localStatus.polledAtEpochMillis >= (lastDiscoveryAtEpochMillis ?: Long.MAX_VALUE)
    val profile = remember(device, localStatus, lastDiscoveryAtEpochMillis) {
        LocalDeviceCapabilityRegistry.profile(
            device = device,
            status = localStatus,
            lastDiscoveryAtEpochMillis = lastDiscoveryAtEpochMillis,
        )
    }
    val sensorPresentation = remember(
        device.mappingJson,
        localStatus?.dataPoints,
        localStatus?.state,
        profile.sensorKind,
        isCurrentStatus,
    ) {
        if (isCurrentStatus && localStatus?.state == LocalPollDeviceState.RESPONDED) {
            profile.sensorKind?.let { sensorKind ->
                presentLocalSensor(sensorKind, profile.capabilities)
            }
        } else {
            null
        }
    }
    val hasActiveSensor = sensorPresentation?.tone == LocalSensorTone.ACTIVE ||
        sensorPresentation?.tone == LocalSensorTone.ALERT
    val isProfileActive = isCurrentStatus &&
        (profile.booleanControls.any { localControl -> localControl.currentValue } || hasActiveSensor)
    val localAvailability = when {
        lastDiscoveryAtEpochMillis == null -> LocalAvailability("Scan needed", false)
        isOnCurrentLan -> LocalAvailability("Local", true)
        else -> LocalAvailability("Not found", false)
    }
    OutlinedCard(
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 17.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                DeviceProfileMark(
                    profile = profile,
                    tone = sensorPresentation?.tone,
                    active = isProfileActive,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = device.name.ifBlank { "Unnamed Tuya device" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = deviceDescription(device, profile),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    LocalAvailabilityLabel(localAvailability)
                    when {
                        profile.access == LocalDeviceAccessKind.STATUS_ONLY -> Text(
                            text = "Read only",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 5.dp),
                        )
                        profile.access == LocalDeviceAccessKind.DIRECT_CONTROL &&
                            device.localKey.isBlank -> Text(
                            text = "Key missing",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 5.dp),
                        )
                        profile.access != LocalDeviceAccessKind.DIRECT_CONTROL -> Text(
                            text = "Unsupported",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 5.dp),
                        )
                    }
                }
            }
            if (
                profile.access != LocalDeviceAccessKind.DIRECT_CONTROL &&
                !(profile.access == LocalDeviceAccessKind.STATUS_ONLY &&
                    profile.kind == LocalDeviceProfileKind.SENSOR)
            ) {
                LocalAccessNotice(profile)
            }
            LocalStatusPanel(
                device = device,
                profile = profile,
                status = localStatus,
                sensorPresentation = sensorPresentation,
                isCurrentStatus = isCurrentStatus,
                isOnCurrentLan = isOnCurrentLan,
                isReadingStatus = discovery is LanDiscoveryUiState.ReadingStatus,
                control = control,
                onIntent = onIntent,
            )
        }
    }
}

@Composable
private fun LocalStatusPanel(
    device: CloudImportedDevice,
    profile: LocalDeviceProfile,
    status: LocalStatusRecord?,
    sensorPresentation: LocalSensorPresentation?,
    isCurrentStatus: Boolean,
    isOnCurrentLan: Boolean,
    isReadingStatus: Boolean,
    control: LocalControlUiState,
    onIntent: (DeviceIntent) -> Unit,
) {
    if (!isOnCurrentLan || !profile.access.canReadLocalStatus) return
    val updatedAt = remember(status?.polledAtEpochMillis) {
        status?.polledAtEpochMillis?.let { timestamp ->
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(timestamp))
        }
    }
    val booleanControls = profile.booleanControls
    val atomicCapabilityIds = remember(profile) { profile.atomicCapabilityIds() }
    val presentedDataPoints = remember(
        device.mappingJson,
        status?.dataPoints,
        booleanControls,
        sensorPresentation,
        profile.lightControls,
        atomicCapabilityIds,
    ) {
        val featuredIds = booleanControls.mapTo(mutableSetOf()) { it.dataPointId }
        profile.capabilities.capabilities
            .filter { capability -> capability.id in atomicCapabilityIds }
            .mapTo(featuredIds) { capability -> capability.dataPointId }
        sensorPresentation?.consumedDataPointIds?.let(featuredIds::addAll)
        profile.lightControls?.let { lightControls ->
            lightControls.mode?.dataPointId?.let(featuredIds::add)
            lightControls.whiteBrightness?.dataPointId?.let(featuredIds::add)
            lightControls.colorTemperature?.dataPointId?.let(featuredIds::add)
            lightControls.color?.dataPointId?.let(featuredIds::add)
        }
        if (status != null) {
            presentLocalDataPoints(
                capabilities = profile.capabilities,
                excludedDataPointIds = featuredIds,
            )
        } else {
            emptyList()
        }
    }
    val inspection = remember(device.mappingJson, status?.dataPoints, status?.state) {
        status
            ?.takeIf { currentStatus -> currentStatus.state == LocalPollDeviceState.RESPONDED }
            ?.let { currentStatus -> inspectLocalDataPoints(device, currentStatus.dataPoints) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.padding(top = 14.dp)) {
            val statusTitle = when {
                isReadingStatus -> "Reading status…"
                !isCurrentStatus -> "Local status not read yet"
                status?.errorCode == "LOCAL_CONTROL_UNCONFIRMED" ->
                    "Could not confirm the requested state"
                status?.state == LocalPollDeviceState.OFFLINE -> "Status request timed out"
                status?.state != LocalPollDeviceState.RESPONDED ->
                    "Status could not be decoded"
                else -> null
            }
            statusTitle?.let { title ->
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                isReadingStatus -> Text(
                    text = "Using the encrypted local key directly on this Wi-Fi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                !isCurrentStatus -> Text(
                    text = "Refresh status to read its current data points.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                status?.state == LocalPollDeviceState.RESPONDED -> {
                    sensorPresentation?.let { presentation ->
                        LocalSensorSummary(presentation)
                    }
                    if (profile.kind != LocalDeviceProfileKind.SENSOR) {
                        LocalDeviceControls(
                            profile = profile,
                            atomicCapabilityIds = atomicCapabilityIds,
                            controlState = control,
                            onIntent = onIntent,
                        )
                    }
                    if (presentedDataPoints.isNotEmpty()) {
                        DeviceHighlights(
                            title = when (profile.kind) {
                                LocalDeviceProfileKind.LIGHT -> "Light details"
                                LocalDeviceProfileKind.COVER -> "Cover position"
                                else -> "At a glance"
                            },
                            dataPoints = presentedDataPoints,
                        )
                    }
                    if (
                        presentedDataPoints.isEmpty() &&
                        sensorPresentation == null &&
                        atomicCapabilityIds.isEmpty() &&
                        profile.kind != LocalDeviceProfileKind.GENERIC
                    ) {
                        Text(
                            text = "No everyday controls or readings are available for this " +
                                "device profile.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    updatedAt?.let {
                        Text(
                            text = "Updated locally · $it",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 14.dp),
                        )
                    }
                    inspection?.takeIf { it.totalCount > 0 }?.let { localInspection ->
                        LocalDpsInspector(localInspection)
                    }
                }
                else -> Text(
                    text = localStatusMessage(status?.errorCode.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun DeviceHighlights(
    title: String,
    dataPoints: List<PresentedDataPoint>,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .testTag("device_highlights"),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
        )
        dataPoints.chunked(2).forEach { rowDataPoints ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowDataPoints.forEach { dataPoint ->
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(
                                text = dataPoint.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = dataPoint.value,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
                if (rowDataPoints.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LocalSensorSummary(presentation: LocalSensorPresentation) {
    val primaryContainerColor = when (presentation.tone) {
        LocalSensorTone.NEUTRAL -> MaterialTheme.colorScheme.surface
        LocalSensorTone.NORMAL -> MaterialTheme.colorScheme.primaryContainer
        LocalSensorTone.ACTIVE -> MaterialTheme.colorScheme.tertiaryContainer
        LocalSensorTone.ALERT -> MaterialTheme.colorScheme.errorContainer
    }
    val primaryContentColor = when (presentation.tone) {
        LocalSensorTone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
        LocalSensorTone.NORMAL -> MaterialTheme.colorScheme.onPrimaryContainer
        LocalSensorTone.ACTIVE -> MaterialTheme.colorScheme.onTertiaryContainer
        LocalSensorTone.ALERT -> MaterialTheme.colorScheme.onErrorContainer
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .testTag("local_sensor_summary"),
    ) {
        Surface(
            color = primaryContainerColor,
            contentColor = primaryContentColor,
            shape = MaterialTheme.shapes.small,
            border = if (presentation.tone == LocalSensorTone.NEUTRAL) {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    text = "Current reading",
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = presentation.primary.label,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 5.dp),
                )
                Text(
                    text = presentation.primary.value,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        if (presentation.secondary.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                presentation.secondary.forEach { reading ->
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
                                text = reading.value,
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

@Composable
private fun LocalAccessNotice(profile: LocalDeviceProfile) {
    val isStatusOnly = profile.access == LocalDeviceAccessKind.STATUS_ONLY
    val title = when (profile.access) {
        LocalDeviceAccessKind.STATUS_ONLY -> if (profile.kind == LocalDeviceProfileKind.SENSOR) {
            "Read-only sensor"
        } else {
            "Status-only profile"
        }
        LocalDeviceAccessKind.GATEWAY_CHILD -> "Gateway child"
        LocalDeviceAccessKind.GATEWAY -> "Gateway controls unavailable"
        LocalDeviceAccessKind.CAMERA -> "Camera controls disabled"
        LocalDeviceAccessKind.LOCK -> "Lock controls disabled"
        LocalDeviceAccessKind.DIRECT_CONTROL -> return
    }
    val message = when (profile.access) {
        LocalDeviceAccessKind.STATUS_ONLY -> when (profile.kind) {
            LocalDeviceProfileKind.COVER ->
                "Cover commands are not enabled yet. Current local DPS stays read-only."
            LocalDeviceProfileKind.SENSOR ->
                "Fresh readings come directly from the device. This profile never sends commands."
            else ->
                "No verified control profile matches this device. Local DPS stays read-only."
        }
        LocalDeviceAccessKind.GATEWAY_CHILD ->
            "This device communicates through a Tuya gateway, not directly over Wi-Fi."
        LocalDeviceAccessKind.GATEWAY ->
            "Gateway management and child-device routing are not supported yet."
        LocalDeviceAccessKind.CAMERA ->
            "Camera streams and camera commands are intentionally unavailable."
        LocalDeviceAccessKind.LOCK ->
            "Lock and access-control commands are intentionally unavailable for safety."
        LocalDeviceAccessKind.DIRECT_CONTROL -> return
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (isStatusOnly) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.tertiaryContainer
        },
        contentColor = if (isStatusOnly) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onTertiaryContainer
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .testTag("local_access_notice"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                shape = CircleShape,
                color = if (isStatusOnly) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
                modifier = Modifier
                    .padding(top = 6.dp)
                    .size(7.dp),
                content = {},
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(text = title, style = MaterialTheme.typography.labelLarge)
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun LocalDpsInspector(inspection: LocalDataPointInspection) {
    var expanded by remember(inspection) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
    ) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("dps_inspector_toggle"),
        ) {
            Text(
                if (expanded) {
                    "Hide device data"
                } else {
                    "Show all device data · ${inspection.totalCount}"
                }
            )
        }
        if (expanded) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dps_inspector_panel"),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = "ALL LOCAL DEVICE DATA · READ ONLY",
                        style = MaterialTheme.typography.labelLarge,
                        fontSize = 10.sp,
                        letterSpacing = 1.3.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Boolean, numeric, enum, and safe mapped text values are shown. " +
                            "Structured and potentially sensitive values stay hidden.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    inspection.dataPoints.forEach { dataPoint ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "DP ${dataPoint.id} · ${dataPoint.kindLabel}",
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                dataPoint.code?.let { code ->
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = code,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 140.dp),
                                    )
                                }
                            }
                            Text(
                                text = dataPoint.safeValue,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalDeviceControls(
    profile: LocalDeviceProfile,
    atomicCapabilityIds: Set<CapabilityId>,
    controlState: LocalControlUiState,
    onIntent: (DeviceIntent) -> Unit,
) {
    val device = remember(profile.resolvedDevice) {
        DeviceUiMapper.map(profile.resolvedDevice)
    }
    when (profile.kind) {
        LocalDeviceProfileKind.LIGHT -> LocalLightControls(
            device = device,
            powerControls = profile.booleanControls,
            powerCapabilityIds = atomicCapabilityIds,
            lightControls = profile.lightControls,
            controlState = controlState,
            onIntent = onIntent,
        )
        else -> DeviceCapabilityList(
            device = device,
            controlState = controlState,
            onIntent = onIntent,
            modifier = Modifier.padding(top = 16.dp),
            capabilityIds = atomicCapabilityIds,
            sectionTitle = when {
                atomicCapabilityIds.isEmpty() -> null
                profile.kind == LocalDeviceProfileKind.COVER -> "Cover"
                profile.booleanControls.size == 1 -> "Control"
                profile.kind == LocalDeviceProfileKind.SWITCH_OR_OUTLET -> "Controls"
                else -> "Controls and readings"
            },
            showEmptyFallback = true,
        )
    }
}

private fun LocalDeviceProfile.atomicCapabilityIds(): Set<CapabilityId> = when (kind) {
    LocalDeviceProfileKind.LIGHT -> booleanControls.mapTo(mutableSetOf()) { control ->
        control.capabilityId
    }
    LocalDeviceProfileKind.SENSOR -> emptySet()
    LocalDeviceProfileKind.SWITCH_OR_OUTLET,
    LocalDeviceProfileKind.COVER,
    LocalDeviceProfileKind.GENERIC -> capabilities.capabilities.mapTo(mutableSetOf()) { capability ->
        capability.id
    }
}

@Composable
private fun LocalLightControls(
    device: DeviceUiModel,
    powerControls: List<LocalBooleanControl>,
    powerCapabilityIds: Set<CapabilityId>,
    lightControls: LocalLightControls?,
    controlState: LocalControlUiState,
    onIntent: (DeviceIntent) -> Unit,
) {
    DeviceCapabilityList(
        device = device,
        controlState = controlState,
        onIntent = onIntent,
        modifier = Modifier.padding(top = 16.dp),
        capabilityIds = powerCapabilityIds,
        sectionTitle = "Light controls",
    )
    val modeControl = lightControls?.mode ?: return
    val currentMode = modeControl.currentMode
    val powerIsOn = powerControls.singleOrNull()?.currentValue == true
    val canAdjust = powerIsOn &&
        controlState !is LocalControlUiState.Unavailable &&
        controlState !is LocalControlUiState.Sending

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .testTag("light_controls"),
    ) {
        Text(text = "Mode", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LightModeButton(
                label = "White",
                selected = currentMode == LocalLightMode.WHITE,
                enabled = canAdjust,
                modifier = Modifier.weight(1f),
                onClick = {
                    onIntent(
                        DeviceIntent.SetChoice(
                            deviceId = device.deviceId,
                            capabilityId = modeControl.capabilityId,
                            wireValue = LocalLightMode.WHITE.wireValue,
                        )
                    )
                },
            )
            LightModeButton(
                label = "Color",
                selected = currentMode == LocalLightMode.COLOR,
                enabled = canAdjust,
                modifier = Modifier.weight(1f),
                onClick = {
                    onIntent(
                        DeviceIntent.SetChoice(
                            deviceId = device.deviceId,
                            capabilityId = modeControl.capabilityId,
                            wireValue = LocalLightMode.COLOR.wireValue,
                        )
                    )
                },
            )
        }
        when {
            !powerIsOn -> Text(
                text = "Turn on the light to adjust its color and brightness.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            currentMode == LocalLightMode.WHITE -> {
                lightControls.whiteBrightness?.let { control ->
                    LightRangeSlider(
                        label = "Brightness",
                        control = control,
                        enabled = canAdjust,
                        pending = controlState.isSending(device.deviceId, control.capabilityId),
                        onValueCommitted = { value ->
                            onIntent(
                                DeviceIntent.SetRange(
                                    deviceId = device.deviceId,
                                    capabilityId = control.capabilityId,
                                    value = value,
                                )
                            )
                        },
                    )
                }
                lightControls.colorTemperature?.let { control ->
                    LightRangeSlider(
                        label = "Color temperature",
                        control = control,
                        enabled = canAdjust,
                        pending = controlState.isSending(device.deviceId, control.capabilityId),
                        startLabel = "Warm",
                        endLabel = "Cool",
                        onValueCommitted = { value ->
                            onIntent(
                                DeviceIntent.SetRange(
                                    deviceId = device.deviceId,
                                    capabilityId = control.capabilityId,
                                    value = value,
                                )
                            )
                        },
                    )
                }
            }
            currentMode == LocalLightMode.COLOR -> lightControls.color?.let { control ->
                LightColorPicker(
                    control = control,
                    enabled = canAdjust,
                    pending = controlState.isSending(device.deviceId, control.capabilityId),
                    onColorCommitted = { color ->
                        onIntent(
                            DeviceIntent.SetColor(
                                deviceId = device.deviceId,
                                capabilityId = control.capabilityId,
                                color = TuyaHsvColor(
                                    color.hue,
                                    color.saturation,
                                    color.brightness,
                                ),
                            )
                        )
                    },
                )
            }
            else -> Text(
                text = "Scene and music modes are not controlled here. Choose White or Color.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        LightControlFeedback(device.deviceId, controlState)
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
    if (selected) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.testTag("light_mode_${label.lowercase()}"),
        ) {
            Text(label)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.testTag("light_mode_${label.lowercase()}"),
        ) {
            Text(label)
        }
    }
}

@Composable
private fun LightRangeSlider(
    label: String,
    control: LocalLightIntegerControl,
    enabled: Boolean,
    pending: Boolean,
    startLabel: String? = null,
    endLabel: String? = null,
    onValueCommitted: (Int) -> Unit,
) {
    var value by remember(control.dataPointId, control.currentValue) {
        mutableStateOf(control.currentValue.toFloat())
    }
    LaunchedEffect(control.currentValue, pending) {
        if (!pending) value = control.currentValue.toFloat()
    }
    val alignedValue = alignLightValue(value, control)
    val percentage = lightPercentage(alignedValue, control.minimum, control.maximum)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.titleSmall)
            Text(
                text = "$percentage%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = {
                if (alignedValue != control.currentValue) {
                    value = alignedValue.toFloat()
                    onValueCommitted(alignedValue)
                }
            },
            valueRange = control.minimum.toFloat()..control.maximum.toFloat(),
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("light_slider_${control.code}"),
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
    control: LocalLightColorControl,
    enabled: Boolean,
    pending: Boolean,
    onColorCommitted: (LocalLightHsv) -> Unit,
) {
    var selectedColor by remember(control.dataPointId, control.currentColor) {
        mutableStateOf(control.currentColor)
    }
    LaunchedEffect(control.currentColor, pending) {
        if (!pending) selectedColor = control.currentColor
    }
    var wheelSize by remember { mutableStateOf(IntSize.Zero) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = "Color", style = MaterialTheme.typography.titleSmall)
            Surface(
                color = Color.hsv(
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
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth(0.72f)
                .sizeIn(maxWidth = 260.dp)
                .aspectRatio(1f)
                .onSizeChanged { wheelSize = it }
                .semantics { contentDescription = "Color wheel" }
                .testTag("light_color_wheel")
                .pointerInput(enabled, control.dataPointId) {
                    if (!enabled) return@pointerInput
                    fun updateColor(position: Offset) {
                        if (wheelSize == IntSize.Zero) return
                        val centerX = wheelSize.width / 2f
                        val centerY = wheelSize.height / 2f
                        val deltaX = position.x - centerX
                        val deltaY = position.y - centerY
                        val radius = minOf(centerX, centerY)
                        val saturation = (hypot(deltaX, deltaY) / radius * 1_000f)
                            .roundToInt()
                            .coerceIn(0, 1_000)
                        val hue = ((atan2(deltaY, deltaX) * 180f / PI.toFloat()) + 360f)
                            .rem(360f)
                            .roundToInt()
                        selectedColor = selectedColor.copy(
                            hue = hue,
                            saturation = saturation,
                        )
                    }
                    detectDragGestures(
                        onDragStart = ::updateColor,
                        onDragEnd = {
                            if (selectedColor != control.currentColor) {
                                onColorCommitted(selectedColor)
                            }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            updateColor(change.position)
                        },
                    )
                },
        ) {
            drawCircle(
                brush = Brush.sweepGradient(
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
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, Color.Transparent),
                    center = center,
                    radius = size.minDimension / 2f,
                )
            )
            val radius = size.minDimension / 2f
            val pointerRadius = radius * selectedColor.saturation / 1_000f
            val angle = selectedColor.hue * PI.toFloat() / 180f
            val pointer = Offset(
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
    color: LocalLightHsv,
    enabled: Boolean,
    pending: Boolean,
    onColorCommitted: (LocalLightHsv) -> Unit,
) {
    var brightness by remember(color) { mutableStateOf(color.brightness.toFloat()) }
    LaunchedEffect(color.brightness, pending) {
        if (!pending) brightness = color.brightness.toFloat()
    }
    val alignedBrightness = brightness.roundToInt().coerceIn(10, 1_000)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    ) {
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
                if (alignedBrightness != color.brightness) {
                    brightness = alignedBrightness.toFloat()
                    onColorCommitted(color.copy(brightness = alignedBrightness))
                }
            },
            valueRange = 10f..1_000f,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("light_slider_color_brightness"),
        )
    }
}

@Composable
private fun LightControlFeedback(
    deviceId: String,
    controlState: LocalControlUiState,
) {
    val text = when {
        controlState is LocalControlUiState.Sending &&
            controlState.deviceId == deviceId &&
            controlState.intent.isLightIntent -> controlState.intent.sendingMessage
        controlState is LocalControlUiState.Confirmed &&
            controlState.deviceId == deviceId &&
            controlState.intent.isLightIntent -> "Confirmed directly by the light."
        controlState is LocalControlUiState.Error &&
            controlState.deviceId == deviceId &&
            controlState.intent.isLightIntent -> controlState.message
        else -> null
    } ?: return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (controlState is LocalControlUiState.Error) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .padding(top = 12.dp)
            .testTag("light_control_feedback"),
    )
}

private fun alignLightValue(
    value: Float,
    control: LocalLightIntegerControl,
): Int {
    val stepIndex = ((value - control.minimum) / control.step).roundToInt()
    return (control.minimum + stepIndex * control.step).coerceIn(control.minimum, control.maximum)
}

private fun lightPercentage(value: Int, minimum: Int, maximum: Int): Int =
    ((value - minimum).toFloat() / (maximum - minimum) * 100f)
        .roundToInt()
        .coerceIn(0, 100)

private fun LocalControlUiState.appliesTo(deviceId: String, capabilityId: CapabilityId): Boolean =
    when (this) {
        is LocalControlUiState.Sending ->
            this.deviceId == deviceId && this.capabilityId == capabilityId
        is LocalControlUiState.Confirmed ->
            this.deviceId == deviceId && this.capabilityId == capabilityId
        is LocalControlUiState.Error ->
            this.deviceId == deviceId && this.capabilityId == capabilityId
        LocalControlUiState.Ready, LocalControlUiState.Unavailable -> false
    }

private fun LocalControlUiState.isSending(deviceId: String, capabilityId: CapabilityId): Boolean =
    this is LocalControlUiState.Sending && appliesTo(deviceId, capabilityId)

private val DeviceIntent.isLightIntent: Boolean
    get() = capabilityId.value.startsWith("light.")

private val DeviceIntent.sendingMessage: String
    get() = when (this) {
        is DeviceIntent.SetToggle -> if (value) {
            "Turning on and confirming…"
        } else {
            "Turning off and confirming…"
        }
        is DeviceIntent.SetChoice -> "Changing mode and confirming…"
        is DeviceIntent.SetRange -> when (capabilityId.value) {
            "light.brightness" -> "Updating brightness and confirming…"
            "light.temperature" -> "Updating color temperature and confirming…"
            else -> "Updating value and confirming…"
        }
        is DeviceIntent.SetColor -> "Updating color and brightness…"
        is DeviceIntent.InvokeAction -> "Sending action and confirming…"
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
private fun DeviceProfileMark(
    profile: LocalDeviceProfile,
    tone: LocalSensorTone?,
    active: Boolean,
) {
    val containerColor = when {
        tone == LocalSensorTone.ALERT -> MaterialTheme.colorScheme.errorContainer
        tone == LocalSensorTone.ACTIVE -> MaterialTheme.colorScheme.tertiaryContainer
        active -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        tone == LocalSensorTone.ALERT -> MaterialTheme.colorScheme.onErrorContainer
        tone == LocalSensorTone.ACTIVE -> MaterialTheme.colorScheme.onTertiaryContainer
        active -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier.testTag("device_profile_badge"),
    ) {
        Box(Modifier.size(50.dp), contentAlignment = Alignment.Center) {
            if (
                (profile.access == LocalDeviceAccessKind.DIRECT_CONTROL ||
                    profile.access == LocalDeviceAccessKind.STATUS_ONLY) &&
                profile.kind == LocalDeviceProfileKind.SWITCH_OR_OUTLET
            ) {
                SwitchProfileIcon(
                    color = contentColor,
                    modifier = Modifier.size(27.dp),
                )
            } else {
                Text(
                    text = deviceProfileSymbol(profile),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun SwitchProfileIcon(
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
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

@Composable
private fun LocalAvailabilityLabel(availability: LocalAvailability) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = CircleShape,
            color = if (availability.isLocal) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            },
            modifier = Modifier.size(7.dp),
            content = {},
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = availability.label,
            style = MaterialTheme.typography.labelLarge,
            color = if (availability.isLocal) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

private data class LocalAvailability(
    val label: String,
    val isLocal: Boolean,
)

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
            text = "Cloud access happens only when you choose this action. Saved credentials are reused when available; automatic local refresh never contacts Tuya Cloud.",
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
    "LAN_NETWORK_CHANGED" -> "Wi-Fi changed since refresh"
    "LOCAL_REFRESH_DISCOVERY_REQUIRED" -> "Find devices again"
    "LAN_WIFI_UNAVAILABLE" -> "Connect to your device Wi-Fi"
    "LAN_PERMISSION_DENIED" -> "Local network access was blocked"
    "LAN_PORT_UNAVAILABLE" -> "Discovery ports are busy"
    "LAN_NETWORK_UNAVAILABLE" -> "Wi-Fi changed during the scan"
    "LOCAL_POLL_FAILED", "LOCAL_POLL_INPUT_INVALID", "LOCAL_POLL_NETWORK_INVALID",
    "LOCAL_POLL_DEVICES_INVALID" -> "Local status could not be read"
    "CATALOG_MISSING", "CATALOG_WRITE_FAILED", "CATALOG_ENCRYPT_FAILED" ->
        "Discovery could not be saved"
    else -> "Local discovery did not complete"
}

private fun localStatusMessage(code: String): String = when (code) {
    "LOCAL_DEVICE_OFFLINE" -> "The device was found but did not accept a local connection."
    "LOCAL_DEVICE_TIMEOUT", "LOCAL_DEVICE_NO_RESPONSE" ->
        "The device was found but did not answer before the local timeout."
    "LOCAL_CONTROL_UNCONFIRMED" ->
        "No readable status came back after the command. Refresh to verify the device's actual state."
    "LOCAL_KEY_OR_VERSION_INVALID" ->
        "The saved local key or protocol version was rejected. A cloud sync may refresh it."
    "LOCAL_PROTOCOL_ERROR" ->
        "The response could not be decoded safely with the detected Tuya protocol."
    else -> "The local status request did not complete safely."
}

private fun deviceDescription(
    device: CloudImportedDevice,
    profile: LocalDeviceProfile,
): String = buildList {
    add(deviceProfileLabel(device, profile))
    add(device.productName)
    add(device.model)
}.map { value -> value.trim() }
    .filter { value -> value.isNotBlank() }
    .distinctBy { value -> value.lowercase() }
    .take(2)
    .joinToString(" · ")

private fun deviceProfileLabel(
    device: CloudImportedDevice,
    profile: LocalDeviceProfile,
): String = when (profile.access) {
    LocalDeviceAccessKind.GATEWAY_CHILD -> "Gateway child"
    LocalDeviceAccessKind.GATEWAY -> "Tuya gateway"
    LocalDeviceAccessKind.CAMERA -> "Smart camera"
    LocalDeviceAccessKind.LOCK -> "Smart lock or access control"
    LocalDeviceAccessKind.DIRECT_CONTROL,
    LocalDeviceAccessKind.STATUS_ONLY -> when (profile.kind) {
        LocalDeviceProfileKind.SWITCH_OR_OUTLET -> when {
            profile.mappedSwitchCount > 1 && device.category.lowercase() == "pc" ->
                "${profile.mappedSwitchCount}-channel power strip"
            profile.mappedSwitchCount > 1 -> "${profile.mappedSwitchCount}-gang switch"
            device.category.lowercase() == "cz" -> "Smart outlet"
            device.category.lowercase() == "pc" -> "Power strip"
            else -> "Smart switch"
        }
        LocalDeviceProfileKind.LIGHT -> "Smart light"
        LocalDeviceProfileKind.COVER -> "Curtain or cover"
        LocalDeviceProfileKind.SENSOR -> sensorProfileLabel(profile.sensorKind)
        LocalDeviceProfileKind.GENERIC -> "Tuya device"
    }
}

private fun deviceProfileSymbol(profile: LocalDeviceProfile): String = when (profile.access) {
    LocalDeviceAccessKind.GATEWAY_CHILD -> "⌁"
    LocalDeviceAccessKind.GATEWAY -> "⌂"
    LocalDeviceAccessKind.CAMERA -> "◉"
    LocalDeviceAccessKind.LOCK -> "◇"
    LocalDeviceAccessKind.DIRECT_CONTROL,
    LocalDeviceAccessKind.STATUS_ONLY -> when (profile.kind) {
        LocalDeviceProfileKind.SWITCH_OR_OUTLET -> "⏻"
        LocalDeviceProfileKind.LIGHT -> "✦"
        LocalDeviceProfileKind.COVER -> "↕"
        LocalDeviceProfileKind.SENSOR -> sensorProfileSymbol(profile.sensorKind)
        LocalDeviceProfileKind.GENERIC -> "••"
    }
}

private fun sensorProfileLabel(sensorKind: LocalSensorKind?): String = when (sensorKind) {
    LocalSensorKind.CLIMATE -> "Temperature and humidity sensor"
    LocalSensorKind.CONTACT -> "Contact sensor"
    LocalSensorKind.MOTION -> "Motion sensor"
    LocalSensorKind.PRESENCE -> "Presence sensor"
    LocalSensorKind.WATER_LEAK -> "Water leak sensor"
    LocalSensorKind.SMOKE -> "Smoke alarm"
    LocalSensorKind.GAS -> "Gas alarm"
    null -> "Tuya sensor"
}

private fun sensorProfileSymbol(sensorKind: LocalSensorKind?): String = when (sensorKind) {
    LocalSensorKind.CLIMATE -> "°"
    LocalSensorKind.CONTACT -> "▯"
    LocalSensorKind.MOTION -> "⌁"
    LocalSensorKind.PRESENCE -> "◎"
    LocalSensorKind.WATER_LEAK -> "≈"
    LocalSensorKind.SMOKE -> "≋"
    LocalSensorKind.GAS -> "◇"
    null -> "·"
}

private val LocalDeviceAccessKind.canReadLocalStatus: Boolean
    get() = this == LocalDeviceAccessKind.DIRECT_CONTROL ||
        this == LocalDeviceAccessKind.STATUS_ONLY

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
            control = LocalControlUiState.Unavailable,
            onRefreshKnownDevices = {},
            onDiscoverLan = {},
            onIntent = {},
            onOpenSettings = {},
            onImportFromCloud = {},
            onDeleteAllLocalData = {},
        )
    }
}
