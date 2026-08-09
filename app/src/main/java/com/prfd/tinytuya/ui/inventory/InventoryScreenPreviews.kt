package com.prfd.tinytuya.ui.inventory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.prfd.tinytuya.data.lan.LocalDataPoint
import com.prfd.tinytuya.data.lan.LocalDataPointKind
import com.prfd.tinytuya.data.lan.LocalPollDeviceState
import com.prfd.tinytuya.data.local.LanDeviceRecord
import com.prfd.tinytuya.data.local.LocalStatusRecord
import com.prfd.tinytuya.data.python.CloudImportedDevice
import com.prfd.tinytuya.data.python.SensitiveString
import com.prfd.tinytuya.device.ui.DeviceControlUiState as LocalControlUiState
import com.prfd.tinytuya.ui.app.LanDiscoveryUiState
import com.prfd.tinytuya.ui.theme.TinytuyaTheme

@Preview(
  name = "Switch",
  group = FULL_DEVICE_CARD_PREVIEWS,
  widthDp = 420,
  heightDp = 430,
)
@Composable
private fun SwitchCardPreview() = PreviewFullDeviceCard(previewSwitch())

@Preview(
  name = "Socket",
  group = FULL_DEVICE_CARD_PREVIEWS,
  widthDp = 420,
  heightDp = 600,
)
@Composable
private fun SocketCardPreview() = PreviewFullDeviceCard(previewSocket())

@Preview(
  name = "Power strip",
  group = FULL_DEVICE_CARD_PREVIEWS,
  widthDp = 420,
  heightDp = 580,
)
@Composable
private fun PowerStripCardPreview() = PreviewFullDeviceCard(previewPowerStrip())

@Preview(
  name = "Light · white",
  group = FULL_DEVICE_CARD_PREVIEWS,
  widthDp = 420,
  heightDp = 760,
)
@Composable
private fun WhiteLightCardPreview() = PreviewLightCard(mode = "white")

@Preview(
  name = "Light · color",
  group = FULL_DEVICE_CARD_PREVIEWS,
  widthDp = 420,
  heightDp = 820,
)
@Composable
private fun ColorLightCardPreview() = PreviewLightCard(mode = "colour")

@Composable private fun PreviewLightCard(mode: String) = PreviewFullDeviceCard(previewLight(mode))

@Preview(
  name = "Cover",
  group = FULL_DEVICE_CARD_PREVIEWS,
  widthDp = 420,
  heightDp = 570,
)
@Composable
private fun CoverCardPreview() = PreviewFullDeviceCard(previewCover())

@Preview(
  name = "Switch",
  group = COMPACT_DEVICE_CARD_PREVIEWS,
  widthDp = 420,
  heightDp = 140,
)
@Composable
private fun CompactSwitchCardPreview() = PreviewCompactDeviceCard(previewSwitch())

@Preview(
  name = "Socket",
  group = COMPACT_DEVICE_CARD_PREVIEWS,
  widthDp = 420,
  heightDp = 140,
)
@Composable
private fun CompactSocketCardPreview() = PreviewCompactDeviceCard(previewSocket())

@Preview(
  name = "Power strip",
  group = COMPACT_DEVICE_CARD_PREVIEWS,
  widthDp = 420,
  heightDp = 140,
)
@Composable
private fun CompactPowerStripCardPreview() = PreviewCompactDeviceCard(previewPowerStrip())

@Preview(
  name = "Light",
  group = COMPACT_DEVICE_CARD_PREVIEWS,
  widthDp = 420,
  heightDp = 140,
)
@Composable
private fun CompactLightCardPreview() = PreviewCompactDeviceCard(previewLight(mode = "white"))

@Preview(
  name = "Cover",
  group = COMPACT_DEVICE_CARD_PREVIEWS,
  widthDp = 420,
  heightDp = 140,
)
@Composable
private fun CompactCoverCardPreview() = PreviewCompactDeviceCard(previewCover())

@Composable
private fun PreviewFullDeviceCard(state: PreviewDeviceState) {
  PreviewCardFrame {
    InventoryDeviceCard(
      device = state.device,
      lastDiscoveryAtEpochMillis = PREVIEW_TIMESTAMP,
      lanRecord = previewLanRecord(state.device),
      localStatus = previewLocalStatus(state),
      discovery = LanDiscoveryUiState.Idle,
      control = LocalControlUiState.Ready,
      onIntent = {},
    )
  }
}

@Composable
private fun PreviewCompactDeviceCard(state: PreviewDeviceState) {
  PreviewCardFrame {
    CompactInventoryDeviceCard(
      device = state.device,
      lastDiscoveryAtEpochMillis = PREVIEW_TIMESTAMP,
      lanRecord = previewLanRecord(state.device),
      localStatus = previewLocalStatus(state),
      control = LocalControlUiState.Ready,
      onIntent = {},
      onOpenFullControls = {},
    )
  }
}

@Composable
private fun PreviewCardFrame(content: @Composable () -> Unit) {
  TinytuyaTheme(darkTheme = false) {
    Box(modifier = Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp)) {
      content()
    }
  }
}

private fun previewSwitch() =
  PreviewDeviceState(
    device =
      previewDevice(
        id = "switch",
        name = "Hallway switch",
        category = "kg",
        productName = "Wall switch",
        mappingJson =
          """
          {"1":{"code":"switch_1","type":"Boolean"}}
          """
            .trimIndent(),
      ),
    dataPoints = listOf(LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true")),
  )

private fun previewSocket() =
  PreviewDeviceState(
    device =
      previewDevice(
        id = "socket",
        name = "Coffee station",
        category = "cz",
        productName = "Metered socket",
        mappingJson =
          """
          {
            "1":{"code":"switch_1","type":"Boolean"},
            "9":{"code":"countdown_1","type":"Integer"},
            "18":{"code":"cur_current","type":"Integer","values":{"unit":"mA","scale":0}},
            "19":{"code":"cur_power","type":"Integer","values":{"unit":"W","scale":1}},
            "20":{"code":"cur_voltage","type":"Integer","values":{"unit":"V","scale":1}},
            "21":{"code":"add_ele","type":"Integer","values":{"unit":"kWh","scale":3}}
          }
          """
            .trimIndent(),
      ),
    dataPoints =
      listOf(
        LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true"),
        LocalDataPoint("9", LocalDataPointKind.INTEGER, "0"),
        LocalDataPoint("18", LocalDataPointKind.INTEGER, "421"),
        LocalDataPoint("19", LocalDataPointKind.INTEGER, "123"),
        LocalDataPoint("20", LocalDataPointKind.INTEGER, "2304"),
        LocalDataPoint("21", LocalDataPointKind.INTEGER, "1234"),
      ),
  )

private fun previewPowerStrip() =
  PreviewDeviceState(
    device =
      previewDevice(
        id = "power-strip",
        name = "Media center",
        category = "pc",
        productName = "Three-socket power strip",
        mappingJson =
          """
          {
            "1":{"code":"switch_1","type":"Boolean"},
            "2":{"code":"switch_2","type":"Boolean"},
            "3":{"code":"switch_3","type":"Boolean"}
          }
          """
            .trimIndent(),
      ),
    dataPoints =
      listOf(
        LocalDataPoint("1", LocalDataPointKind.BOOLEAN, "true"),
        LocalDataPoint("2", LocalDataPointKind.BOOLEAN, "false"),
        LocalDataPoint("3", LocalDataPointKind.BOOLEAN, "true"),
      ),
  )

private fun previewLight(mode: String) =
  PreviewDeviceState(
    device =
      previewDevice(
        id = "light-$mode",
        name = "Reading lamp",
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
      ),
    dataPoints =
      listOf(
        LocalDataPoint("20", LocalDataPointKind.BOOLEAN, "true"),
        LocalDataPoint("21", LocalDataPointKind.STRING, mode),
        LocalDataPoint("22", LocalDataPointKind.INTEGER, "730"),
        LocalDataPoint("23", LocalDataPointKind.INTEGER, "420"),
        LocalDataPoint("24", LocalDataPointKind.STRING, "00d003e803e8"),
      ),
  )

private fun previewCover() =
  PreviewDeviceState(
    device =
      previewDevice(
        id = "cover",
        name = "Living room curtain",
        category = "cl",
        productName = "Wi-Fi curtain motor",
        mappingJson =
          """
          {
            "1":{"code":"control","type":"Enum","values":{"range":["open","stop","close"]}},
            "2":{"code":"percent_control","type":"Integer","values":{"min":0,"max":100,"step":1,"scale":0}},
            "3":{"code":"percent_state","type":"Integer","values":{"min":0,"max":100,"step":1,"scale":0}}
          }
          """
            .trimIndent(),
      ),
    dataPoints =
      listOf(
        LocalDataPoint("1", LocalDataPointKind.STRING, "stop"),
        LocalDataPoint("2", LocalDataPointKind.INTEGER, "62"),
        LocalDataPoint("3", LocalDataPointKind.INTEGER, "60"),
      ),
  )

private fun previewLanRecord(device: CloudImportedDevice) =
  LanDeviceRecord(
    id = device.id,
    ip = "192.168.1.42",
    protocolVersion = device.protocolVersion,
    productKey = "preview-product-key",
    mac = "",
    origin = "preview",
    lastSeenAtEpochMillis = PREVIEW_TIMESTAMP,
  )

private fun previewLocalStatus(state: PreviewDeviceState) =
  LocalStatusRecord(
    id = state.device.id,
    state = LocalPollDeviceState.RESPONDED,
    errorCode = "",
    durationMillis = 38L,
    dataPoints = state.dataPoints,
    polledAtEpochMillis = PREVIEW_TIMESTAMP + 1L,
  )

private fun previewDevice(
  id: String,
  name: String,
  category: String,
  productName: String,
  mappingJson: String,
) =
  CloudImportedDevice(
    id = "preview-$id",
    name = name,
    localKey = SensitiveString.of("preview-local-key"),
    category = category,
    productId = "preview-product",
    productName = productName,
    model = "Preview",
    mac = "",
    uuid = "",
    isSubDevice = false,
    gatewayId = "",
    nodeId = "",
    protocolVersion = "3.5",
    lastIp = "",
    mappingJson = mappingJson,
  )

private data class PreviewDeviceState(
  val device: CloudImportedDevice,
  val dataPoints: List<LocalDataPoint>,
)

private const val FULL_DEVICE_CARD_PREVIEWS = "Full device cards"
private const val COMPACT_DEVICE_CARD_PREVIEWS = "Compact device cards"
private const val PREVIEW_TIMESTAMP = 1_775_400_000_000L
