package com.prfd.tinytuya.ui.inventory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prfd.tinytuya.data.lan.LocalDataPointInspection
import com.prfd.tinytuya.data.lan.LocalDeviceCapabilityRegistry
import com.prfd.tinytuya.data.lan.LocalDeviceProfile
import com.prfd.tinytuya.data.lan.LocalPollDeviceState
import com.prfd.tinytuya.data.lan.hasCurrentKnownStatusTargets
import com.prfd.tinytuya.data.lan.inspectLocalDataPoints
import com.prfd.tinytuya.data.local.DeviceCatalog
import com.prfd.tinytuya.data.local.InventoryDisplayMode
import com.prfd.tinytuya.data.local.LanDeviceRecord
import com.prfd.tinytuya.data.local.LocalStatusRecord
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.data.python.SensitiveString
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import com.prfd.tinytuya.device.core.capability.CapabilityAccess
import com.prfd.tinytuya.device.core.capability.DeviceIntent
import com.prfd.tinytuya.device.core.profile.DeviceAccessRestriction
import com.prfd.tinytuya.device.core.profile.DeviceFamilyId
import com.prfd.tinytuya.device.profiles.BuiltinDeviceFamilies
import com.prfd.tinytuya.device.profiles.BuiltinDeviceFamilyIds
import com.prfd.tinytuya.device.ui.CompactDeviceLayoutHost
import com.prfd.tinytuya.device.ui.CompactDeviceLayoutRendererRegistry
import com.prfd.tinytuya.device.ui.CoverActionsCompactLayoutRenderer
import com.prfd.tinytuya.device.ui.CoverDeviceLayoutRenderer
import com.prfd.tinytuya.device.ui.DeviceControlUiState as LocalControlUiState
import com.prfd.tinytuya.device.ui.DeviceLayoutHost
import com.prfd.tinytuya.device.ui.DeviceLayoutRendererRegistry
import com.prfd.tinytuya.device.ui.DeviceUiMapper
import com.prfd.tinytuya.device.ui.DeviceUiModel
import com.prfd.tinytuya.device.ui.GenericToggleCompactLayoutRenderer
import com.prfd.tinytuya.device.ui.LightDeviceLayoutRenderer
import com.prfd.tinytuya.device.ui.LightPowerCompactLayoutRenderer
import com.prfd.tinytuya.device.ui.ToggleUiModel
import com.prfd.tinytuya.ui.app.LanDiscoveryUiState
import com.prfd.tinytuya.ui.app.LocalRefreshPhase
import com.prfd.tinytuya.ui.components.BrandMark
import com.prfd.tinytuya.ui.theme.TinytuyaTheme
import java.text.DateFormat
import java.util.Date

private val inventoryDeviceLayoutRegistry =
  DeviceLayoutRendererRegistry(
    listOf(
      CoverDeviceLayoutRenderer,
      LightDeviceLayoutRenderer,
    )
  )

private val inventoryCompactDeviceLayoutRegistry =
  CompactDeviceLayoutRendererRegistry(
    listOf(
      GenericToggleCompactLayoutRenderer,
      LightPowerCompactLayoutRenderer,
      CoverActionsCompactLayoutRenderer,
    )
  )

private data class InventoryDeviceItem(
  val device: CloudImportedDevice,
  val lanRecord: LanDeviceRecord?,
  val localStatus: LocalStatusRecord?,
  val profile: LocalDeviceProfile,
  val deviceUiModel: DeviceUiModel,
  val isOnCurrentLan: Boolean,
  val isCurrentStatus: Boolean,
  val isProfileActive: Boolean,
  val localAvailability: LocalAvailability,
)

private data class InventorySection(
  val key: String,
  val label: String,
  val symbol: String,
  val familyId: DeviceFamilyId?,
  val items: List<InventoryDeviceItem>,
)

private fun buildInventoryDeviceItems(
  catalog: DeviceCatalog,
  currentDiscoveryAtEpochMillis: Long?,
): List<InventoryDeviceItem> {
  val lanById = catalog.lanDevices.associateBy(LanDeviceRecord::id)
  val statusById = catalog.localStatus.associateBy(LocalStatusRecord::id)
  return catalog.devices.map { device ->
    inventoryDeviceItem(
      device = device,
      lastDiscoveryAtEpochMillis = currentDiscoveryAtEpochMillis,
      lanRecord = lanById[device.id],
      localStatus = statusById[device.id],
    )
  }
}

private fun inventoryDeviceItem(
  device: CloudImportedDevice,
  lastDiscoveryAtEpochMillis: Long?,
  lanRecord: LanDeviceRecord?,
  localStatus: LocalStatusRecord?,
): InventoryDeviceItem {
  val isOnCurrentLan =
    lastDiscoveryAtEpochMillis != null &&
      lanRecord?.lastSeenAtEpochMillis == lastDiscoveryAtEpochMillis
  val isCurrentStatus =
    lastDiscoveryAtEpochMillis != null &&
      isOnCurrentLan &&
      localStatus != null &&
      localStatus.polledAtEpochMillis >= lastDiscoveryAtEpochMillis
  val profile =
    LocalDeviceCapabilityRegistry.profile(
      device = device,
      status = localStatus,
      lastDiscoveryAtEpochMillis = lastDiscoveryAtEpochMillis,
    )
  val deviceUiModel = DeviceUiMapper.map(profile.resolvedDevice)
  val localAvailability =
    when {
      lastDiscoveryAtEpochMillis == null -> LocalAvailability("Scan needed", false)
      isOnCurrentLan -> LocalAvailability("Local", true)
      else -> LocalAvailability("Not found", false)
    }
  return InventoryDeviceItem(
    device = device,
    lanRecord = lanRecord,
    localStatus = localStatus,
    profile = profile,
    deviceUiModel = deviceUiModel,
    isOnCurrentLan = isOnCurrentLan,
    isCurrentStatus = isCurrentStatus,
    isProfileActive =
      isCurrentStatus &&
        deviceUiModel.capabilities
          .filterIsInstance<ToggleUiModel>()
          .any(ToggleUiModel::currentValue),
    localAvailability = localAvailability,
  )
}

private fun buildInventorySections(items: List<InventoryDeviceItem>): List<InventorySection> {
  val familyOrder =
    BuiltinDeviceFamilies.definitions
      .mapIndexed { index, definition -> definition.id to index }
      .toMap()
  return items
    .groupBy { item ->
      item.profile.familyId.takeIf { item.profile.restriction == DeviceAccessRestriction.NONE }
    }
    .map { (familyId, familyItems) ->
      val presentation = familyItems.first().profile.presentation
      InventorySection(
        key = familyId?.value ?: "other",
        label = if (familyId == null) "Other devices" else presentation.familyLabel,
        symbol = if (familyId == null) "••" else presentation.symbol,
        familyId = familyId,
        items = familyItems,
      )
    }
    .sortedWith(
      compareBy<InventorySection> { section ->
          section.familyId?.let { familyId -> familyOrder[familyId] } ?: Int.MAX_VALUE
        }
        .thenBy { section -> section.label.lowercase() }
    )
}

@Composable
fun InventoryScreen(
  catalog: DeviceCatalog,
  discovery: LanDiscoveryUiState,
  control: LocalControlUiState,
  isLanSnapshotCurrent: Boolean = true,
  displayMode: InventoryDisplayMode = InventoryDisplayMode.FULL,
  isDisplayModeSaving: Boolean = false,
  onDisplayModeChanged: (InventoryDisplayMode) -> Unit = {},
  onRefreshKnownDevices: () -> Unit,
  onDiscoverLan: () -> Unit,
  onIntent: (DeviceIntent) -> Unit,
  onOpenSettings: () -> Unit,
) {
  var focusedDeviceId by remember { mutableStateOf<String?>(null) }
  val currentDiscoveryAtEpochMillis =
    catalog.lastDiscoveryAtEpochMillis.takeIf { isLanSnapshotCurrent }
  val inventoryItems =
    remember(
      catalog.devices,
      catalog.lanDevices,
      catalog.localStatus,
      currentDiscoveryAtEpochMillis,
    ) {
      buildInventoryDeviceItems(catalog, currentDiscoveryAtEpochMillis)
    }
  val inventorySections = remember(inventoryItems) { buildInventorySections(inventoryItems) }
  val focusedDevice = inventoryItems.firstOrNull { item -> item.device.id == focusedDeviceId }
  LaunchedEffect(focusedDeviceId, focusedDevice) {
    if (focusedDeviceId != null && focusedDevice == null) focusedDeviceId = null
  }
  val knownIds = remember(catalog.devices) { catalog.devices.mapTo(mutableSetOf()) { it.id } }
  val unmatchedLanDevices =
    remember(
      catalog.devices,
      catalog.lanDevices,
      currentDiscoveryAtEpochMillis,
    ) {
      catalog.lanDevices.filter { record ->
        record.id !in knownIds && record.lastSeenAtEpochMillis == currentDiscoveryAtEpochMillis
      }
    }
  if (focusedDevice != null) {
    BackHandler { focusedDeviceId = null }
    FocusedDeviceControlsScreen(
      item = focusedDevice,
      discovery = discovery,
      control = control,
      onIntent = onIntent,
      onBack = { focusedDeviceId = null },
    )
    return
  }

  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
  ) {
    Box(
      modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
      contentAlignment = Alignment.TopCenter,
    ) {
      LazyColumn(
        modifier = Modifier.fillMaxSize().widthIn(max = 680.dp).testTag("inventory_list"),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item {
          InventoryHeader(
            catalog = catalog,
            displayMode = displayMode,
            isDisplayModeSaving = isDisplayModeSaving,
            onDisplayModeChanged = onDisplayModeChanged,
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
        inventorySections.forEach { section ->
          if (catalog.devices.size > 1) {
            item(key = "family-${section.key}") { InventoryFamilyHeader(section) }
          }
          items(
            items = section.items,
            key = { item -> item.device.id },
          ) { item ->
            when (displayMode) {
              InventoryDisplayMode.COMPACT ->
                CompactInventoryDeviceCard(
                  item = item,
                  control = control,
                  onIntent = onIntent,
                  onOpenFullControls = { focusedDeviceId = item.device.id },
                )
              InventoryDisplayMode.FULL ->
                InventoryDeviceCard(
                  item = item,
                  discovery = discovery,
                  control = control,
                  onIntent = onIntent,
                )
            }
          }
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
      }
    }
  }
}

@Composable
private fun InventoryHeader(
  catalog: DeviceCatalog,
  displayMode: InventoryDisplayMode,
  isDisplayModeSaving: Boolean,
  onDisplayModeChanged: (InventoryDisplayMode) -> Unit,
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
        onClick = {
          onDisplayModeChanged(
            if (displayMode == InventoryDisplayMode.COMPACT) {
              InventoryDisplayMode.FULL
            } else {
              InventoryDisplayMode.COMPACT
            }
          )
        },
        enabled = !isDisplayModeSaving,
        modifier =
          Modifier.semantics {
              contentDescription =
                if (displayMode == InventoryDisplayMode.COMPACT) {
                  "Switch to Advanced view"
                } else {
                  "Switch to Simple view"
                }
            }
            .testTag("inventory_mode_toggle"),
      ) {
        Text(if (displayMode == InventoryDisplayMode.COMPACT) "Simple" else "Advanced")
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
      text =
        when {
          catalog.devices.size == 1 -> "1 secured device is stored for private local control."
          else -> "${catalog.devices.size} secured devices are stored for private local control."
        },
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 8.dp),
    )
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
  val lastScan =
    remember(catalog.lastDiscoveryAtEpochMillis) {
      catalog.lastDiscoveryAtEpochMillis?.let { timestamp ->
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
      }
    }
  val currentDeviceCount =
    catalog.lanDevices.count { it.lastSeenAtEpochMillis == currentDiscoveryAtEpochMillis }
  val isScanning = discovery is LanDiscoveryUiState.Scanning
  val isReadingStatus = discovery is LanDiscoveryUiState.ReadingStatus
  val isBusy = isScanning || isReadingStatus || isControlBusy
  val error =
    (discovery as? LanDiscoveryUiState.Error)?.takeIf { it.phase == LocalRefreshPhase.DISCOVERY }

  OutlinedCard(
    colors =
      CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier.fillMaxWidth().testTag("find_devices_card"),
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
              Text(
                "LAN",
                style = MaterialTheme.typography.labelLarge,
                fontSize = 10.sp,
              )
            }
          }
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
          Text(
            text =
              when {
                isScanning -> "Listening for Tuya devices"
                error != null -> lanErrorTitle(error.code)
                currentDiscoveryAtEpochMillis == null && lastScan != null ->
                  "Find devices on this Wi-Fi"
                lastScan != null && currentDeviceCount == 0 -> "No devices found in the last search"
                lastScan != null && currentDeviceCount == 1 -> "1 Tuya device found"
                lastScan != null -> "$currentDeviceCount Tuya devices found"
                else -> "Find devices on this Wi-Fi"
              },
            style = MaterialTheme.typography.titleMedium,
          )
          Text(
            text =
              when {
                isScanning -> "Listening on UDP 6666, 6667, and 7000 for up to twelve seconds."
                error != null -> error.message
                currentDiscoveryAtEpochMillis == null && lastScan != null ->
                  "The previous local snapshot is not verified on the active Wi-Fi."
                lastScan != null ->
                  "Last searched $lastScan. Search again after a device or Wi-Fi address changes."
                else ->
                  "Match the encrypted cloud inventory to devices broadcasting on the phone's current Wi-Fi."
              },
            style = MaterialTheme.typography.bodyMedium,
            color =
              if (error != null) {
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
        modifier =
          Modifier.fillMaxWidth().padding(top = 14.dp).height(50.dp).testTag("lan_scan_button"),
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
  val currentLanIds =
    remember(catalog.lanDevices, currentDiscoveryAtEpochMillis) {
      catalog.lanDevices
        .asSequence()
        .filter { record -> record.lastSeenAtEpochMillis == currentDiscoveryAtEpochMillis }
        .mapTo(mutableSetOf()) { record -> record.id }
    }
  val matchedCount = catalog.devices.count { device -> device.id in currentLanIds }
  val currentResponseCount =
    catalog.localStatus.count { status ->
      status.id in currentLanIds &&
        status.state == LocalPollDeviceState.RESPONDED &&
        status.polledAtEpochMillis >= (currentDiscoveryAtEpochMillis ?: Long.MAX_VALUE)
    }
  val currentStatusReadAt =
    catalog.lastLocalPollAtEpochMillis
      ?.takeIf { timestamp ->
        currentDiscoveryAtEpochMillis != null && timestamp >= currentDiscoveryAtEpochMillis
      }
      ?.let { timestamp ->
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
      }
  val canRefreshKnownDevices =
    currentDiscoveryAtEpochMillis != null && catalog.hasCurrentKnownStatusTargets()
  val isScanning = discovery is LanDiscoveryUiState.Scanning
  val isReadingStatus = discovery is LanDiscoveryUiState.ReadingStatus
  val statusError =
    (discovery as? LanDiscoveryUiState.Error)?.takeIf { it.phase == LocalRefreshPhase.STATUS }

  Column(
    modifier =
      Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp).testTag("device_inventory_header")
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
          text =
            when {
              isReadingStatus -> "Reading saved devices directly on this Wi-Fi…"
              currentDiscoveryAtEpochMillis == null ->
                "Find devices to match their current local addresses."
              matchedCount == 0 -> "No secured devices matched in the last search."
              currentStatusReadAt != null && matchedCount == 1 ->
                "$currentResponseCount of 1 device answered · $currentStatusReadAt"
              currentStatusReadAt != null ->
                "$currentResponseCount of $matchedCount devices answered · $currentStatusReadAt"
              matchedCount == 1 -> "1 device matched · Refresh to read its current status."
              else -> "$matchedCount devices matched · Refresh to read their current status."
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
          modifier = Modifier.height(42.dp).testTag("inventory_refresh_button"),
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
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag("status_refresh_error"),
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
private fun InventoryFamilyHeader(section: InventorySection) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .padding(top = 10.dp, bottom = 1.dp)
        .testTag("inventory_family_${section.key}"),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Surface(
      shape = CircleShape,
      color = MaterialTheme.colorScheme.secondaryContainer,
      contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
      Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
        if (section.familyId == BuiltinDeviceFamilyIds.SWITCH_OR_OUTLET) {
          SwitchProfileIcon(
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(18.dp),
          )
        } else {
          Text(section.symbol, style = MaterialTheme.typography.labelLarge)
        }
      }
    }
    Spacer(Modifier.width(9.dp))
    Text(
      text = section.label,
      style = MaterialTheme.typography.titleSmall,
      modifier = Modifier.weight(1f),
    )
    Text(
      text = section.items.size.toString(),
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun CompactInventoryDeviceCard(
  item: InventoryDeviceItem,
  control: LocalControlUiState,
  onIntent: (DeviceIntent) -> Unit,
  onOpenFullControls: () -> Unit,
) {
  val device = item.device
  val targetedError =
    (control as? LocalControlUiState.Error)?.takeIf { error -> error.deviceId == device.id }
  OutlinedCard(
    colors =
      CardDefaults.outlinedCardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
      ),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier =
      Modifier.fillMaxWidth()
        .semantics {
          contentDescription = "Open full controls for ${device.name.ifBlank { "device" }}"
          role = Role.Button
        }
        .clickable(onClick = onOpenFullControls)
        .testTag("compact_device_card_${device.id}"),
  ) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        DeviceProfileMark(profile = item.profile, active = item.isProfileActive)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
          Text(
            text = device.name.ifBlank { "Unnamed Tuya device" },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = deviceProfileLabel(device, item.profile),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 1.dp),
          )
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 3.dp),
          ) {
            LocalAvailabilityLabel(item.localAvailability)
            Text(
              text = "  ·  Advanced controls ›",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        Spacer(Modifier.width(10.dp))
        CompactDeviceLayoutHost(
          device = item.deviceUiModel,
          registry = inventoryCompactDeviceLayoutRegistry,
          controlState = control,
          onIntent = onIntent,
          modifier = Modifier.widthIn(max = 168.dp),
        )
      }
      targetedError?.let { error ->
        Text(
          text = error.message,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(top = 8.dp).testTag("compact_control_error"),
        )
      }
    }
  }
}

@Composable
private fun FocusedDeviceControlsScreen(
  item: InventoryDeviceItem,
  discovery: LanDiscoveryUiState,
  control: LocalControlUiState,
  onIntent: (DeviceIntent) -> Unit,
  onBack: () -> Unit,
) {
  Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Box(
      modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
      contentAlignment = Alignment.TopCenter,
    ) {
      LazyColumn(
        modifier = Modifier.fillMaxSize().widthIn(max = 680.dp).testTag("focused_device_controls"),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        item {
          Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("focused_device_back")) {
              Text("←  Back")
            }
            Spacer(Modifier.width(8.dp))
            Column {
              Text("Advanced controls", style = MaterialTheme.typography.titleLarge)
              Text(
                text = "Only this device is expanded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
        item {
          InventoryDeviceCard(
            item = item,
            discovery = discovery,
            control = control,
            onIntent = onIntent,
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
  val item =
    remember(device, localStatus, lastDiscoveryAtEpochMillis) {
      inventoryDeviceItem(
        device = device,
        lanRecord = lanRecord,
        localStatus = localStatus,
        lastDiscoveryAtEpochMillis = lastDiscoveryAtEpochMillis,
      )
    }
  InventoryDeviceCard(item, discovery, control, onIntent)
}

@Composable
private fun InventoryDeviceCard(
  item: InventoryDeviceItem,
  discovery: LanDiscoveryUiState,
  control: LocalControlUiState,
  onIntent: (DeviceIntent) -> Unit,
) {
  val device = item.device
  val profile = item.profile
  OutlinedCard(
    colors =
      CardDefaults.outlinedCardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
      ),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 17.dp)) {
      Row(verticalAlignment = Alignment.Top) {
        DeviceProfileMark(
          profile = profile,
          active = item.isProfileActive,
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
          LocalAvailabilityLabel(item.localAvailability)
          when {
            profile.capabilityAccess == CapabilityAccess.READ_ONLY ->
              Text(
                text = "Read only",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 5.dp),
              )
            profile.capabilityAccess == CapabilityAccess.READ_WRITE && device.localKey.isBlank ->
              Text(
                text = "Key missing",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 5.dp),
              )
            profile.capabilityAccess == CapabilityAccess.DENIED ->
              Text(
                text = "Unsupported",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 5.dp),
              )
          }
        }
      }
      if (profile.capabilityAccess != CapabilityAccess.READ_WRITE) {
        LocalAccessNotice(profile)
      }
      LocalStatusPanel(
        device = device,
        profile = profile,
        deviceUiModel = item.deviceUiModel,
        status = item.localStatus,
        isCurrentStatus = item.isCurrentStatus,
        isOnCurrentLan = item.isOnCurrentLan,
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
  deviceUiModel: DeviceUiModel,
  status: LocalStatusRecord?,
  isCurrentStatus: Boolean,
  isOnCurrentLan: Boolean,
  isReadingStatus: Boolean,
  control: LocalControlUiState,
  onIntent: (DeviceIntent) -> Unit,
) {
  if (!isOnCurrentLan || !profile.canReadLocalStatus) return
  val updatedAt =
    remember(status?.polledAtEpochMillis) {
      status?.polledAtEpochMillis?.let { timestamp ->
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
      }
    }
  val inspection =
    remember(device.mappingJson, status?.dataPoints, status?.state) {
      status
        ?.takeIf { currentStatus -> currentStatus.state == LocalPollDeviceState.RESPONDED }
        ?.let { currentStatus -> inspectLocalDataPoints(device, currentStatus.dataPoints) }
    }

  Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(Modifier.padding(top = 14.dp)) {
      val statusTitle =
        when {
          isReadingStatus -> "Reading status…"
          !isCurrentStatus -> "Local status not read yet"
          status?.errorCode == "LOCAL_CONTROL_UNCONFIRMED" ->
            "Could not confirm the requested state"
          status?.state == LocalPollDeviceState.OFFLINE -> "Status request timed out"
          status?.state != LocalPollDeviceState.RESPONDED -> "Status could not be decoded"
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
        isReadingStatus ->
          Text(
            text = "Using the encrypted local key directly on this Wi-Fi.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
          )
        !isCurrentStatus ->
          Text(
            text = "Refresh status to read its current data points.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
          )
        status?.state == LocalPollDeviceState.RESPONDED -> {
          DeviceLayoutHost(
            device = deviceUiModel,
            registry = inventoryDeviceLayoutRegistry,
            controlState = control,
            onIntent = onIntent,
          )
          updatedAt?.let {
            Text(
              text = "Updated locally · $it",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(top = 14.dp),
            )
          }
          inspection
            ?.takeIf { it.totalCount > 0 }
            ?.let { localInspection -> LocalDpsInspector(localInspection) }
        }
        else ->
          Text(
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
private fun LocalAccessNotice(profile: LocalDeviceProfile) {
  val isStatusOnly = profile.capabilityAccess == CapabilityAccess.READ_ONLY
  val title =
    when (profile.restriction) {
      DeviceAccessRestriction.NONE ->
        if (isStatusOnly) {
          "Status-only profile"
        } else {
          "Unsupported device"
        }
      DeviceAccessRestriction.GATEWAY_CHILD -> "Gateway child"
      DeviceAccessRestriction.GATEWAY -> "Gateway controls unavailable"
      DeviceAccessRestriction.CAMERA -> "Camera controls disabled"
      DeviceAccessRestriction.LOCK -> "Lock controls disabled"
    }
  val message =
    when (profile.restriction) {
      DeviceAccessRestriction.NONE ->
        if (isStatusOnly) {
          "This supported device has no verified writable capability. Local DPS stays read-only."
        } else {
          "Local status and controls are available only for supported switches, outlets, lights, and covers."
        }
      DeviceAccessRestriction.GATEWAY_CHILD ->
        "This device communicates through a Tuya gateway, not directly over Wi-Fi."
      DeviceAccessRestriction.GATEWAY ->
        "Gateway management and child-device routing are not supported yet."
      DeviceAccessRestriction.CAMERA ->
        "Camera streams and camera commands are intentionally unavailable."
      DeviceAccessRestriction.LOCK ->
        "Lock and access-control commands are intentionally unavailable for safety."
    }
  Surface(
    shape = MaterialTheme.shapes.medium,
    color =
      if (isStatusOnly) {
        MaterialTheme.colorScheme.surfaceVariant
      } else {
        MaterialTheme.colorScheme.tertiaryContainer
      },
    contentColor =
      if (isStatusOnly) {
        MaterialTheme.colorScheme.onSurfaceVariant
      } else {
        MaterialTheme.colorScheme.onTertiaryContainer
      },
    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("local_access_notice"),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
      verticalAlignment = Alignment.Top,
    ) {
      Surface(
        shape = CircleShape,
        color =
          if (isStatusOnly) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.tertiary
          },
        modifier = Modifier.padding(top = 6.dp).size(7.dp),
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
  Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
    TextButton(
      onClick = { expanded = !expanded },
      modifier = Modifier.fillMaxWidth().testTag("dps_inspector_toggle"),
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
        modifier = Modifier.fillMaxWidth().testTag("dps_inspector_panel"),
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
            text =
              "Boolean, numeric, enum, and safe mapped text values are shown. " +
                "Structured and potentially sensitive values stay hidden.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
          )
          inspection.dataPoints.forEach { dataPoint ->
            Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
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
            text =
              buildString {
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
    color =
      if (positive) {
        MaterialTheme.colorScheme.primaryContainer
      } else {
        MaterialTheme.colorScheme.surfaceVariant
      },
    contentColor =
      if (positive) {
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
  active: Boolean,
) {
  val containerColor =
    when {
      active -> MaterialTheme.colorScheme.primaryContainer
      else -> MaterialTheme.colorScheme.surfaceVariant
    }
  val contentColor =
    when {
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
        profile.restriction == DeviceAccessRestriction.NONE &&
          profile.familyId == BuiltinDeviceFamilyIds.SWITCH_OR_OUTLET
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
      color =
        if (availability.isLocal) {
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
      color =
        if (availability.isLocal) {
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

private fun lanErrorTitle(code: String): String =
  when (code) {
    "LAN_NETWORK_CHANGED" -> "Wi-Fi changed since refresh"
    "LOCAL_REFRESH_DISCOVERY_REQUIRED" -> "Find devices again"
    "LAN_WIFI_UNAVAILABLE" -> "Connect to your device Wi-Fi"
    "LAN_PERMISSION_DENIED" -> "Local network access was blocked"
    "LAN_PORT_UNAVAILABLE" -> "Discovery ports are busy"
    "LAN_NETWORK_UNAVAILABLE" -> "Wi-Fi changed during the scan"
    "LOCAL_POLL_FAILED",
    "LOCAL_POLL_INPUT_INVALID",
    "LOCAL_POLL_NETWORK_INVALID",
    "LOCAL_POLL_DEVICES_INVALID" -> "Local status could not be read"
    "CATALOG_MISSING",
    "CATALOG_WRITE_FAILED",
    "CATALOG_ENCRYPT_FAILED" -> "Discovery could not be saved"
    else -> "Local discovery did not complete"
  }

private fun localStatusMessage(code: String): String =
  when (code) {
    "LOCAL_DEVICE_OFFLINE" -> "The device was found but did not accept a local connection."
    "LOCAL_DEVICE_TIMEOUT",
    "LOCAL_DEVICE_NO_RESPONSE" ->
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
}
  .map { value -> value.trim() }
  .filter { value -> value.isNotBlank() }
  .distinctBy { value -> value.lowercase() }
  .take(2)
  .joinToString(" · ")

private fun deviceProfileLabel(
  device: CloudImportedDevice,
  profile: LocalDeviceProfile,
): String =
  when (profile.restriction) {
    DeviceAccessRestriction.GATEWAY_CHILD -> "Gateway child"
    DeviceAccessRestriction.GATEWAY -> "Tuya gateway"
    DeviceAccessRestriction.CAMERA -> "Smart camera"
    DeviceAccessRestriction.LOCK -> "Smart lock or access control"
    DeviceAccessRestriction.NONE ->
      when (profile.familyId) {
        BuiltinDeviceFamilyIds.SWITCH_OR_OUTLET ->
          when {
            profile.mappedSwitchCount > 1 && device.category.lowercase() == "pc" ->
              "${profile.mappedSwitchCount}-channel power strip"
            profile.mappedSwitchCount > 1 -> "${profile.mappedSwitchCount}-gang switch"
            device.category.lowercase() == "cz" -> "Smart outlet"
            device.category.lowercase() == "pc" -> "Power strip"
            else -> "Smart switch"
          }
        else -> profile.presentation.typeLabel
      }
  }

private fun deviceProfileSymbol(profile: LocalDeviceProfile): String =
  when (profile.restriction) {
    DeviceAccessRestriction.GATEWAY_CHILD -> "⌁"
    DeviceAccessRestriction.GATEWAY -> "⌂"
    DeviceAccessRestriction.CAMERA -> "◉"
    DeviceAccessRestriction.LOCK -> "◇"
    DeviceAccessRestriction.NONE -> profile.presentation.symbol
  }

private val LocalDeviceProfile.canReadLocalStatus: Boolean
  get() = restriction == DeviceAccessRestriction.NONE && capabilityAccess != CapabilityAccess.DENIED

private val TuyaCloudRegion.displayName: String
  get() =
    when (this) {
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
      catalog =
        DeviceCatalog(
          schemaVersion = 2,
          importedAtEpochMillis = 1_753_981_200_000L,
          region = TuyaCloudRegion.WESTERN_AMERICA,
          devices =
            listOf(
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
    )
  }
}
