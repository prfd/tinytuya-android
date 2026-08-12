package com.prfd.tinytuya

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prfd.tinytuya.data.lan.LocalDataPointKind
import com.prfd.tinytuya.data.local.JsonDeviceCatalogStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * gradlew.bat installDebugAndroidTest adb shell am instrument -w -e class
 * com.prfd.tinytuya.LightCapabilityProbeInstrumentedTest -e \
 * com.prfd.tinytuya.test/androidx.test.runner.AndroidJUnitRunner adb shell run-as com.prfd.tinytuya
 * cat cache/tinytuya-light-capabilities.json
 */
@RunWith(AndroidJUnit4::class)
@ManualTestProbe
class LightCapabilityProbeInstrumentedTest {
  @Test
  fun writeSanitizedLightCapabilityReportToTargetCache() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val catalog =
      JsonDeviceCatalogStore(context).load() ?: error("The saved device catalog is unavailable.")
    val lights = catalog.devices.filter { device -> device.category.trim().lowercase() == "dj" }
    assertTrue("Expected one category-dj light.", lights.size == 1)
    val light = lights.single()
    val status =
      catalog.localStatus.firstOrNull { record -> record.id == light.id }
        ?: error("The light has no saved local status.")
    val mapping = JSONObject(light.mappingJson)
    val observedById = status.dataPoints.associateBy { dataPoint -> dataPoint.id }
    val capabilities = JSONArray()

    mapping
      .keys()
      .asSequence()
      .sortedWith(compareBy<String> { id -> id.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it })
      .forEach { id ->
        val definition = mapping.optJSONObject(id) ?: return@forEach
        val code = safeCode(definition.optString("code")) ?: return@forEach
        if (code !in LIGHT_CAPABILITY_CODES) return@forEach
        val capability =
          JSONObject()
            .put("dp", id)
            .put("code", code)
            .put("mapping_type", safeShortText(definition.optString("type")))
            .put("mapping_values", sanitizedValues(definition.opt("values")))
        observedById[id]?.let { observed ->
          capability.put("observed_kind", observed.kind.wireValue)
          when (observed.kind) {
            LocalDataPointKind.BOOLEAN ->
              if (observed.value in BOOLEAN_VALUES) {
                capability.put("observed_value", observed.value.toBooleanStrict())
              }
            LocalDataPointKind.INTEGER,
            LocalDataPointKind.DECIMAL ->
              observed.value
                .takeIf { value -> value.matches(NUMERIC_VALUE) }
                ?.let { value -> capability.put("observed_value", value) }
            LocalDataPointKind.STRING ->
              capability.put(
                "observed_text",
                sanitizedObservedText(code, observed.value, definition),
              )
            LocalDataPointKind.JSON ->
              capability.put(
                "observed_shape",
                JSONObject().put("structured", true),
              )
            LocalDataPointKind.NULL -> capability.put("observed_value", JSONObject.NULL)
          }
        }
        capabilities.put(capability)
      }

    val report =
      JSONObject()
        .put("format", "tinytuya-sanitized-light-capabilities-v1")
        .put("category", "dj")
        .put("status_state", status.state.wireValue)
        .put(
          "status_is_current",
          catalog.lastDiscoveryAtEpochMillis?.let { discoveryAt ->
            status.polledAtEpochMillis >= discoveryAt
          } == true,
        )
        .put("mapped_capability_count", capabilities.length())
        .put("capabilities", capabilities)
    val output = File(context.cacheDir, REPORT_FILE)
    output.writeText(report.toString(2))
    assertTrue(output.isFile && output.length() in 1..MAX_REPORT_BYTES)
  }

  private fun sanitizedObservedText(
    code: String,
    value: String,
    definition: JSONObject,
  ): JSONObject {
    val shape = JSONObject().put("length", value.length).put("hex", value.matches(HEX_VALUE))
    if (code == "work_mode") {
      val range = parsedValues(definition.opt("values"))?.optJSONArray("range")
      val declared =
        range != null && (0 until range.length()).any { index -> range.optString(index) == value }
      if (declared && value.length <= MAX_SHORT_TEXT_LENGTH && value.none(Char::isISOControl)) {
        shape.put("declared_value", value)
      }
    }
    if (code == "colour_data_v2" && value.length == 12 && value.matches(HEX_VALUE)) {
      val hue = value.substring(0, 4).toInt(16)
      val saturation = value.substring(4, 8).toInt(16)
      val brightness = value.substring(8, 12).toInt(16)
      shape.put(
        "decoded_hsv_candidate",
        JSONObject()
          .put("format", "hhhhssssvvvv")
          .put("hue", hue)
          .put("saturation_0_1000", saturation)
          .put("brightness_0_1000", brightness)
          .put(
            "within_expected_bounds",
            hue in 0..360 && saturation in 0..1000 && brightness in 0..1000,
          ),
      )
    }
    return shape
  }

  private fun sanitizedValues(rawValues: Any?): JSONObject {
    val values = parsedValues(rawValues) ?: return JSONObject()
    val sanitized = JSONObject()
    listOf("min", "max", "step", "scale", "maxlen").forEach { name ->
      values
        .opt(name)
        ?.takeUnless { value -> value == JSONObject.NULL }
        ?.toString()
        ?.takeIf { value -> value.matches(NUMERIC_VALUE) }
        ?.let { value -> sanitized.put(name, value) }
    }
    safeShortText(values.optString("unit")).takeIf(String::isNotEmpty)?.let { unit ->
      sanitized.put("unit", unit)
    }
    values.optJSONArray("range")?.let { range ->
      val sanitizedRange = JSONArray()
      for (index in 0 until minOf(range.length(), MAX_ENUM_VALUES)) {
        safeShortText(range.optString(index)).takeIf(String::isNotEmpty)?.let(sanitizedRange::put)
      }
      sanitized.put("range", sanitizedRange)
    }
    return sanitized
  }

  private fun parsedValues(rawValues: Any?): JSONObject? =
    when (rawValues) {
      is JSONObject -> rawValues
      is String -> runCatching { JSONObject(rawValues) }.getOrNull()
      else -> null
    }

  private fun safeCode(value: String): String? =
    value.trim().lowercase().takeIf { code ->
      code.length in 1..MAX_CODE_LENGTH && code.matches(CODE_VALUE)
    }

  private fun safeShortText(value: String): String =
    value.take(MAX_SHORT_TEXT_LENGTH).filterNot(Char::isISOControl)

  private companion object {
    const val PROBE_ARGUMENT = "tinytuya.lightCapabilityProbe"
    const val REPORT_FILE = "tinytuya-light-capabilities.json"
    const val MAX_CODE_LENGTH = 64
    const val MAX_ENUM_VALUES = 32
    const val MAX_REPORT_BYTES = 64_000
    const val MAX_SHORT_TEXT_LENGTH = 40
    val BOOLEAN_VALUES = setOf("true", "false")
    val CODE_VALUE = Regex("[a-z0-9_]+")
    val NUMERIC_VALUE = Regex("-?[0-9]+(?:\\.[0-9]+)?")
    val HEX_VALUE = Regex("[0-9a-fA-F]+")
    val LIGHT_CAPABILITY_CODES =
      setOf(
        "switch_led",
        "work_mode",
        "bright_value",
        "bright_value_v2",
        "temp_value",
        "temp_value_v2",
        "colour_data",
        "colour_data_v2",
        "scene_data",
        "scene_data_v2",
        "countdown_1",
        "do_not_disturb",
      )
  }
}
