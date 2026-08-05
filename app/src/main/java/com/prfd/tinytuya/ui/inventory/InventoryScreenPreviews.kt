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
  group = "Supported device cards",
  widthDp = 420,
  heightDp = 430,
)
@Composable
private fun SwitchCardPreview() =
  PreviewDeviceCard(
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

@Preview(
  name = "Outlet",
  group = "Supported device cards",
  widthDp = 420,
  heightDp = 600,
)
@Composable
private fun OutletCardPreview() =
  PreviewDeviceCard(
    device =
      previewDevice(
        id = "outlet",
        name = "Coffee station",
        category = "cz",
        productName = "Metered outlet",
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

@Preview(
  name = "Light · white",
  group = "Supported device cards",
  widthDp = 420,
  heightDp = 760,
)
@Composable
private fun WhiteLightCardPreview() = PreviewLightCard(mode = "white")

@Preview(
  name = "Light · color",
  group = "Supported device cards",
  widthDp = 420,
  heightDp = 820,
)
@Composable
private fun ColorLightCardPreview() = PreviewLightCard(mode = "colour")

@Composable
private fun PreviewLightCard(mode: String) =
  PreviewDeviceCard(
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

@Preview(
  name = "Cover",
  group = "Supported device cards",
  widthDp = 420,
  heightDp = 570,
)
@Composable
private fun CoverCardPreview() =
  PreviewDeviceCard(
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

@Composable
private fun PreviewDeviceCard(
  device: CloudImportedDevice,
  dataPoints: List<LocalDataPoint>,
) {
  TinytuyaTheme(darkTheme = false) {
    Box(modifier = Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp)) {
      InventoryDeviceCard(
        device = device,
        lastDiscoveryAtEpochMillis = PREVIEW_TIMESTAMP,
        lanRecord =
          LanDeviceRecord(
            id = device.id,
            ip = "192.168.1.42",
            protocolVersion = device.protocolVersion,
            productKey = "preview-product-key",
            mac = "",
            origin = "preview",
            lastSeenAtEpochMillis = PREVIEW_TIMESTAMP,
          ),
        localStatus =
          LocalStatusRecord(
            id = device.id,
            state = LocalPollDeviceState.RESPONDED,
            errorCode = "",
            durationMillis = 38L,
            dataPoints = dataPoints,
            polledAtEpochMillis = PREVIEW_TIMESTAMP + 1L,
          ),
        discovery = LanDiscoveryUiState.Idle,
        control = LocalControlUiState.Ready,
        onIntent = {},
      )
    }
  }
}

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

private const val PREVIEW_TIMESTAMP = 1_775_400_000_000L
