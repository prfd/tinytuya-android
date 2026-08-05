package com.prfd.tinytuya

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.prfd.tinytuya.data.lan.LocalDataPoint
import com.prfd.tinytuya.data.lan.LocalDataPointKind
import com.prfd.tinytuya.data.lan.LocalPollDeviceState
import com.prfd.tinytuya.data.local.DeviceCatalog
import com.prfd.tinytuya.data.local.LanDeviceRecord
import com.prfd.tinytuya.data.local.LocalStatusRecord
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.data.python.SensitiveString
import com.prfd.tinytuya.data.python.TuyaCloudRegion
import com.prfd.tinytuya.device.core.capability.CapabilityId
import com.prfd.tinytuya.device.core.capability.DeviceIntent
import com.prfd.tinytuya.device.ui.DeviceControlUiState as LocalControlUiState
import com.prfd.tinytuya.ui.app.LanDiscoveryUiState
import com.prfd.tinytuya.ui.inventory.InventoryScreen
import com.prfd.tinytuya.ui.theme.TinytuyaTheme
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class InventoryScreenInstrumentedTest {
  @get:Rule val composeRule = createComposeRule()

  @Test
  fun deletingDataRequiresExplicitConfirmation() {
    var deleteCalled = false
    setInventoryContent(onDelete = { deleteCalled = true })
    composeRule.onNodeWithTag("inventory_list").performScrollToIndex(4)

    composeRule.onNodeWithText("Saved credentials are reused", substring = true).assertExists()
    composeRule.onNodeWithText("Delete all local data").performClick()
    composeRule.onNodeWithText("Delete all local data?").assertExists()
    assertFalse(deleteCalled)

    composeRule.onNodeWithText("Delete data").performClick()
    composeRule.runOnIdle { assertTrue(deleteCalled) }
  }

  @Test
  fun quickRefreshContactsKnownDevicesWithoutStartingDiscovery() {
    var refreshCalled = false
    var scanCalled = false
    setInventoryContent(
      catalog = controlledCatalog(),
      onRefreshKnownDevices = { refreshCalled = true },
      onDiscoverLan = { scanCalled = true },
    )

    composeRule.onNodeWithTag("inventory_refresh_button").performClick()

    composeRule.runOnIdle {
      assertTrue(refreshCalled)
      assertFalse(scanCalled)
    }
    composeRule.onNodeWithTag("find_devices_card").assertExists()
    composeRule.onNodeWithTag("device_inventory_header").assertExists()
    composeRule
      .onNodeWithText("Discovery · listens locally for new or changed addresses")
      .assertExists()
  }

  @Test
  fun localSwitchRequiresAnInProcessRefreshBeforeControl() {
    setInventoryContent(catalog = controlledCatalog())
    composeRule.onNodeWithTag("inventory_list").performScrollToIndex(3)

    composeRule.onNodeWithTag("capability_toggle_switch_1").assertIsNotEnabled().assertIsOn()
    composeRule.onNodeWithText("Refresh status to enable control.").assertExists()
  }

  @Test
  fun changedWifiTreatsSavedAddressesAndStatusAsStale() {
    setInventoryContent(
      catalog = controlledCatalog(),
      discovery =
        LanDiscoveryUiState.Error(
          code = "LAN_NETWORK_CHANGED",
          message = "The active Wi-Fi no longer matches the last local refresh.",
        ),
      control = LocalControlUiState.Unavailable,
      isLanSnapshotCurrent = false,
    )

    composeRule.onNodeWithText("Wi-Fi changed since refresh").assertExists()
    composeRule.onNodeWithTag("inventory_list").performScrollToIndex(3)
    composeRule.onNodeWithText("Scan needed").assertExists()
    composeRule.onNodeWithText("Local").assertDoesNotExist()
    composeRule.onNodeWithText("Power is on").assertDoesNotExist()
    composeRule.onNodeWithTag("capability_toggle_switch_1").assertDoesNotExist()
  }

  @Test
  fun verifiedLocalSwitchInvokesTheTypedControlCallback() {
    var request: DeviceIntent? = null
    setInventoryContent(
      catalog = controlledCatalog(),
      control = LocalControlUiState.Ready,
      onIntent = { request = it },
    )
    composeRule.onNodeWithTag("inventory_list").performScrollToIndex(3)

    composeRule.onNodeWithTag("capability_toggle_switch_1").assertIsOn().performClick()

    composeRule.runOnIdle {
      assertEquals(
        DeviceIntent.SetToggle(
          "office-lamp",
          CapabilityId("switch.1"),
          false,
        ),
        request,
      )
    }
    composeRule.onNodeWithText(LOCAL_KEY, substring = true).assertDoesNotExist()
  }

  @Test
  fun pendingControlKeepsTheLastConfirmedStateVisible() {
    setInventoryContent(
      catalog = controlledCatalog(),
      control =
        LocalControlUiState.Sending(
          DeviceIntent.SetToggle(
            "office-lamp",
            CapabilityId("switch.1"),
            false,
          )
        ),
    )
    composeRule.onNodeWithTag("inventory_list").performScrollToIndex(3)

    composeRule.onNodeWithTag("capability_toggle_switch_1").assertIsOn().assertIsNotEnabled()
    composeRule.onNodeWithText("Turning off and confirming…").assertExists()
    composeRule.onNodeWithTag("capability_control_progress").assertExists()
  }

  @Test
  fun multiGangCardUsesTheSwitchesAsStateAndControlsEachVerifiedChannel() {
    var request: DeviceIntent? = null
    setInventoryContent(
      catalog = multiGangCatalog(),
      control = LocalControlUiState.Ready,
      onIntent = { request = it },
    )
    composeRule.onNodeWithTag("inventory_list").performScrollToIndex(3)

    composeRule.onNodeWithText("3-gang switch", substring = true).assertExists()
    composeRule.onNodeWithText("2 of 3 switches on").assertDoesNotExist()
    composeRule.onNodeWithText("Controls").assertExists()
    composeRule.onNodeWithTag("capability_toggle_switch_1").assertIsOn()
    composeRule.onNodeWithTag("capability_toggle_switch_2").assertIsOff().performClick()
    composeRule.onNodeWithTag("capability_toggle_switch_3").assertIsOn()

    composeRule.runOnIdle {
      assertEquals(
        DeviceIntent.SetToggle(
          "office-lamp",
          CapabilityId("switch.2"),
          true,
        ),
        request,
      )
    }
  }

  @Test
  fun lightCardShowsWhiteModeControlsWithoutRepeatingRawLightDetails() {
    setInventoryContent(catalog = lightCatalog(), control = LocalControlUiState.Ready)
    composeRule.onNodeWithTag("inventory_list").performScrollToIndex(3)

    composeRule.onNodeWithText("Smart light", substring = true).assertExists()
    composeRule.onNodeWithText("Light controls").assertExists()
    composeRule.onNodeWithTag("capability_toggle_power").assertIsOn()
    composeRule.onNodeWithText("Power is on").assertDoesNotExist()
    composeRule.onNodeWithTag("light_controls").assertExists()
    composeRule.onNodeWithTag("light_mode_white").assertExists()
    composeRule.onNodeWithTag("light_mode_color").assertExists()
    composeRule.onNodeWithTag("light_slider_light_brightness").assertExists()
    composeRule.onNodeWithTag("light_slider_light_temperature").assertExists()
    composeRule.onNodeWithTag("light_color_wheel").assertDoesNotExist()
    composeRule.onNodeWithText("Light details").assertDoesNotExist()
    composeRule.onNodeWithText("Brightness").assertExists()
    composeRule.onNodeWithText("73%").assertExists()
    composeRule.onNodeWithText("Color temperature").assertExists()
    composeRule.onNodeWithText("42%").assertExists()
    composeRule.onNodeWithText("Mode").assertExists()
    composeRule.onNodeWithText("White").assertExists()
    composeRule.onNodeWithText("Colour data").assertDoesNotExist()
  }

  @Test
  fun lightModeCallbackIsTyped() {
    var request: DeviceIntent? = null
    setInventoryContent(
      catalog = lightCatalog(),
      control = LocalControlUiState.Ready,
      onIntent = { request = it },
    )
    composeRule.onNodeWithTag("inventory_list").performScrollToIndex(3)

    composeRule.onNodeWithTag("light_mode_color").performClick()

    composeRule.runOnIdle {
      assertEquals(
        DeviceIntent.SetChoice(
          "office-lamp",
          CapabilityId("light.mode"),
          "colour",
        ),
        request,
      )
    }
  }

  @Test
  fun colorModeShowsTheHsvWheelAndItsOwnBrightnessControl() {
    setInventoryContent(
      catalog = lightCatalog(mode = "colour"),
      control = LocalControlUiState.Ready,
    )
    composeRule.onNodeWithTag("inventory_list").performScrollToIndex(3)
    composeRule.onNodeWithTag("light_color_wheel").assertExists()
    composeRule.onNodeWithTag("light_slider_color_brightness").assertExists()
    composeRule.onNodeWithTag("light_slider_light_temperature").assertDoesNotExist()
  }

  @Test
  fun lightSliderKeepsTheRequestedValueUntilConfirmationOrFailure() {
    var catalog by mutableStateOf(lightCatalog())
    var controlState by mutableStateOf<LocalControlUiState>(LocalControlUiState.Ready)
    var requestedValue: Int? = null
    composeRule.setContent {
      TinytuyaTheme(darkTheme = false) {
        InventoryScreen(
          catalog = catalog,
          discovery = LanDiscoveryUiState.Idle,
          control = controlState,
          onRefreshKnownDevices = {},
          onDiscoverLan = {},
          onIntent = { intent ->
            val brightness = intent as DeviceIntent.SetRange
            requestedValue = brightness.value
            controlState = LocalControlUiState.Sending(intent)
          },
          onOpenSettings = {},
          onImportFromCloud = {},
          onDeleteAllLocalData = {},
        )
      }
    }
    composeRule.onNodeWithTag("inventory_list").performScrollToIndex(3)
    val slider = composeRule.onNodeWithTag("light_slider_light_brightness")
    slider.performScrollTo().performTouchInput {
      swipe(
        start = Offset(width * 0.73f, height / 2f),
        end = Offset(width * 0.30f, height / 2f),
      )
    }

    val firstRequest = composeRule.runOnIdle { requireNotNull(requestedValue) }
    val firstPercentage = lightBrightnessPercentage(firstRequest)
    slider.assertIsNotEnabled()
    composeRule.onNodeWithText("$firstPercentage%").assertExists()

    composeRule.runOnIdle {
      catalog = lightCatalog(brightness = firstRequest)
      controlState =
        LocalControlUiState.Confirmed(
          DeviceIntent.SetRange(
            "office-lamp",
            CapabilityId("light.brightness"),
            firstRequest,
          )
        )
    }
    slider.assertIsEnabled()
    composeRule.onNodeWithText("$firstPercentage%").assertExists()

    requestedValue = null
    slider.performTouchInput {
      swipe(
        start = Offset(width * 0.30f, height / 2f),
        end = Offset(width * 0.80f, height / 2f),
      )
    }
    val secondRequest = composeRule.runOnIdle { requireNotNull(requestedValue) }
    assertTrue(secondRequest != firstRequest)
    slider.assertIsNotEnabled()
    composeRule.onNodeWithText("${lightBrightnessPercentage(secondRequest)}%").assertExists()

    composeRule.runOnIdle {
      controlState =
        LocalControlUiState.Error(
          intent =
            DeviceIntent.SetRange(
              "office-lamp",
              CapabilityId("light.brightness"),
              secondRequest,
            ),
          code = "LOCAL_CONTROL_NOT_APPLIED",
          message = "The light kept its previous brightness.",
        )
    }
    slider.assertIsEnabled()
    composeRule.onNodeWithText("$firstPercentage%").assertExists()
  }

  @Test
  fun verifiedCoverUsesRegisteredPrimitiveLayoutAndEmitsSemanticAction() {
    var request: DeviceIntent? = null
    setInventoryContent(
      catalog = coverCatalog(),
      control = LocalControlUiState.Ready,
      onIntent = { request = it },
    )
    composeRule.onNodeWithTag("inventory_list").performScrollToIndex(3)

    composeRule.onNodeWithText("Cover controls").assertExists()
    composeRule.onNodeWithTag("capability_actions_cover_actions").assertExists()
    composeRule.onNodeWithTag("capability_range_cover_position").assertExists()
    composeRule.onNodeWithTag("capability_measurement_cover_position_reading").assertExists()
    composeRule.onNodeWithText("Open").performClick()

    composeRule.runOnIdle {
      assertEquals(
        DeviceIntent.InvokeAction(
          "office-lamp",
          CapabilityId("cover.actions"),
          "open",
        ),
        request,
      )
    }
    composeRule.onNodeWithText(LOCAL_KEY, substring = true).assertDoesNotExist()
  }

  @Test
  fun protectedCameraHidesCachedDpsAndControlEvenWithASwitchMapping() {
    val catalog =
      controlledCatalog()
        .copy(
          devices =
            listOf(
              controlledCatalog()
                .devices
                .single()
                .copy(
                  category = "sp",
                  productName = "Indoor camera",
                )
            )
        )
    setInventoryContent(catalog = catalog, control = LocalControlUiState.Ready)
    composeRule.onNodeWithTag("inventory_list").performScrollToIndex(3)

    composeRule.onNodeWithText("Smart camera", substring = true).assertExists()
    composeRule.onNodeWithText("Camera controls disabled").assertExists()
    composeRule
      .onNodeWithText("Camera streams and camera commands", substring = true)
      .assertExists()
    composeRule.onNodeWithTag("capability_toggle_switch_1").assertDoesNotExist()
    composeRule.onNodeWithTag("dps_inspector_toggle").assertDoesNotExist()
    composeRule.onNodeWithText("Power is on").assertDoesNotExist()
  }

  @Test
  fun statusOnlyDeviceOffersBoundedDpsDetailsWithoutRenderingPrivatePayloads() {
    setInventoryContent(catalog = statusOnlyCatalog())
    composeRule.onNodeWithTag("inventory_list").performScrollToIndex(3)

    composeRule.onNodeWithText("Read only").assertExists()
    composeRule.onNodeWithText("Status-only profile").assertExists()
    composeRule.onNodeWithText("Local DPS stays read-only", substring = true).assertExists()
    composeRule.onNodeWithText(PRIVATE_DP_TEXT, substring = true).assertDoesNotExist()
    composeRule.onNodeWithText(PRIVATE_DP_JSON, substring = true).assertDoesNotExist()

    composeRule.onNodeWithTag("dps_inspector_toggle").performClick()

    composeRule.onNodeWithTag("dps_inspector_panel").assertExists()
    composeRule.onNodeWithText("ALL LOCAL DEVICE DATA · READ ONLY").assertExists()
    composeRule.onNodeWithText("DP 1 · Boolean").assertExists()
    composeRule.onNodeWithText("DP 3 · Enum").assertExists()
    composeRule.onNodeWithText("DP 4 · Text").assertExists()
    composeRule.onNodeWithText("DP 5 · Structured").assertExists()
    composeRule.onNodeWithText("Ready for use").assertExists()
    composeRule
      .onNodeWithText("potentially sensitive values stay hidden", substring = true)
      .assertExists()
    composeRule.onNodeWithText(PRIVATE_DP_TEXT, substring = true).assertDoesNotExist()
    composeRule.onNodeWithText(PRIVATE_DP_JSON, substring = true).assertDoesNotExist()
    composeRule.onNodeWithTag("capability_toggle_switch_1").assertDoesNotExist()
  }

  private fun setInventoryContent(
    catalog: DeviceCatalog = sampleCatalog(),
    discovery: LanDiscoveryUiState = LanDiscoveryUiState.Idle,
    control: LocalControlUiState = LocalControlUiState.Unavailable,
    isLanSnapshotCurrent: Boolean = true,
    onRefreshKnownDevices: () -> Unit = {},
    onDiscoverLan: () -> Unit = {},
    onIntent: (DeviceIntent) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onDelete: () -> Unit = {},
  ) {
    composeRule.setContent {
      TinytuyaTheme(darkTheme = false) {
        InventoryScreen(
          catalog = catalog,
          discovery = discovery,
          control = control,
          isLanSnapshotCurrent = isLanSnapshotCurrent,
          onRefreshKnownDevices = onRefreshKnownDevices,
          onDiscoverLan = onDiscoverLan,
          onIntent = onIntent,
          onOpenSettings = onOpenSettings,
          onImportFromCloud = {},
          onDeleteAllLocalData = onDelete,
        )
      }
    }
  }

  private fun sampleCatalog() =
    DeviceCatalog(
      schemaVersion = 1,
      importedAtEpochMillis = 1_753_981_200_000L,
      region = TuyaCloudRegion.WESTERN_AMERICA,
      devices =
        listOf(
          CloudImportedDevice(
            id = "office-lamp",
            name = "Office lamp",
            localKey = SensitiveString.of(LOCAL_KEY),
            category = "dj",
            productId = "",
            productName = "Lamp",
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
    )

  private fun controlledCatalog() =
    sampleCatalog()
      .copy(
        schemaVersion = 3,
        devices =
          listOf(
            sampleCatalog()
              .devices
              .single()
              .copy(
                category = "kg",
                mappingJson = "{\"1\":{\"code\":\"switch_1\",\"type\":\"Boolean\"}}",
              )
          ),
        lastDiscoveryAtEpochMillis = 9L,
        lanDevices = listOf(lanRecord(id = "office-lamp", ip = "192.168.10.20")),
        lastLocalPollAtEpochMillis = 10L,
        localStatus =
          listOf(
            LocalStatusRecord(
              id = "office-lamp",
              state = LocalPollDeviceState.RESPONDED,
              errorCode = "",
              durationMillis = 42L,
              dataPoints =
                listOf(
                  LocalDataPoint(
                    id = "1",
                    kind = LocalDataPointKind.BOOLEAN,
                    value = "true",
                  )
                ),
              polledAtEpochMillis = 10L,
            )
          ),
      )

  private fun multiGangCatalog() =
    controlledCatalog()
      .copy(
        devices =
          listOf(
            controlledCatalog()
              .devices
              .single()
              .copy(
                productName = "Wall switch",
                mappingJson =
                  """
                  {
                    "1":{"code":"switch_1","type":"Boolean"},
                    "2":{"code":"switch_2","type":"Boolean"},
                    "3":{"code":"switch_3","type":"Boolean"}
                  }
                  """
                    .trimIndent(),
              )
          ),
        localStatus =
          listOf(
            controlledCatalog()
              .localStatus
              .single()
              .copy(
                dataPoints =
                  listOf(
                    LocalDataPoint("3", LocalDataPointKind.BOOLEAN, "true"),
                    LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true"),
                    LocalDataPoint("2", LocalDataPointKind.BOOLEAN, "false"),
                  )
              )
          ),
      )

  private fun lightCatalog(
    mode: String = "white",
    brightness: Int = 730,
  ) =
    controlledCatalog()
      .copy(
        devices =
          listOf(
            controlledCatalog()
              .devices
              .single()
              .copy(
                category = "dj",
                productName = "Color bulb",
                mappingJson =
                  """
                  {
                    "20":{"code":"switch_led","type":"Boolean"},
                    "21":{"code":"work_mode","type":"Enum","values":{"range":["white","colour","scene","music"]}},
                    "22":{"code":"bright_value_v2","type":"Integer","values":{"min":10,"max":1000,"step":1,"scale":0}},
                    "23":{"code":"temp_value_v2","type":"Integer","values":{"min":0,"max":1000,"step":1,"scale":0}},
                    "24":{"code":"colour_data_v2","type":"Json"}
                  }
                  """
                    .trimIndent(),
              )
          ),
        localStatus =
          listOf(
            controlledCatalog()
              .localStatus
              .single()
              .copy(
                dataPoints =
                  listOf(
                    LocalDataPoint("20", LocalDataPointKind.BOOLEAN, "true"),
                    LocalDataPoint("21", LocalDataPointKind.STRING, mode),
                    LocalDataPoint("22", LocalDataPointKind.INTEGER, brightness.toString()),
                    LocalDataPoint("23", LocalDataPointKind.INTEGER, "420"),
                    LocalDataPoint("24", LocalDataPointKind.STRING, "00d003e803e8"),
                  )
              )
          ),
      )

  private fun coverCatalog() =
    controlledCatalog()
      .copy(
        devices =
          listOf(
            controlledCatalog()
              .devices
              .single()
              .copy(
                category = "cl",
                productName = "Curtain motor",
                mappingJson =
                  """
                  {
                    "7":{"code":"control_2","type":"Enum","values":{"range":["open","stop","close","continue"]}},
                    "8":{"code":"percent_control_2","type":"Integer","values":{"min":0,"max":100,"step":1,"scale":0}},
                    "9":{"code":"percent_state_2","type":"Integer","values":{"min":0,"max":100,"step":1,"scale":0}}
                  }
                  """
                    .trimIndent(),
              )
          ),
        localStatus =
          listOf(
            controlledCatalog()
              .localStatus
              .single()
              .copy(
                dataPoints =
                  listOf(
                    LocalDataPoint("7", LocalDataPointKind.STRING, "stop"),
                    LocalDataPoint("8", LocalDataPointKind.INTEGER, "62"),
                    LocalDataPoint("9", LocalDataPointKind.INTEGER, "60"),
                  )
              )
          ),
      )

  private fun statusOnlyCatalog() =
    controlledCatalog()
      .copy(
        devices =
          listOf(
            controlledCatalog()
              .devices
              .single()
              .copy(
                category = "custom_sensor",
                productName = "Room sensor",
                mappingJson =
                  """
                  {
                    "1":{"code":"enabled","type":"Boolean"},
                    "2":{"code":"sample_count","type":"Integer"},
                    "3":{"code":"mode","type":"Enum","values":"{\"range\":[\"auto\",\"manual\"]}"},
                    "4":{"code":"api_token","type":"String"},
                    "5":{"code":"raw_blob","type":"Raw"},
                    "6":{"code":"display_message","type":"String"}
                  }
                  """
                    .trimIndent(),
              )
          ),
        localStatus =
          listOf(
            controlledCatalog()
              .localStatus
              .single()
              .copy(
                dataPoints =
                  listOf(
                    LocalDataPoint("5", LocalDataPointKind.JSON, PRIVATE_DP_JSON),
                    LocalDataPoint("3", LocalDataPointKind.STRING, "auto"),
                    LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true"),
                    LocalDataPoint("4", LocalDataPointKind.STRING, PRIVATE_DP_TEXT),
                    LocalDataPoint("2", LocalDataPointKind.INTEGER, "42"),
                    LocalDataPoint("6", LocalDataPointKind.STRING, "Ready for use"),
                  )
              )
          ),
      )

  private fun lanRecord(id: String, ip: String) =
    LanDeviceRecord(
      id = id,
      ip = ip,
      protocolVersion = "3.5",
      productKey = "product-key",
      mac = "",
      origin = "broadcast",
      lastSeenAtEpochMillis = 9L,
    )

  private fun lightBrightnessPercentage(value: Int): Int =
    ((value - 10) / 990f * 100f).roundToInt().coerceIn(0, 100)

  private companion object {
    const val LOCAL_KEY = "inventory-local-key-must-stay-hidden"
    const val PRIVATE_DP_TEXT = "private-device-token"
    const val PRIVATE_DP_JSON = "private-json-token"
  }
}
