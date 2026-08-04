package com.prfd.tinytuya.data.lan

import com.prfd.tinytuya.device.core.schema.DpDefinitionInput
import com.prfd.tinytuya.device.core.schema.DpSchema
import org.json.JSONArray
import org.json.JSONObject

/**
 * Normalizes the persisted cloud mapping at the Android boundary.
 *
 * Raw JSON remains an app/persistence concern. Device profiles and authorization consume only the
 * bounded pure-Kotlin [DpSchema]. Invalid or oversized input returns an empty/rejected schema and
 * therefore cannot create writable controls.
 */
internal fun parseTuyaDpSchema(mappingJson: String): DpSchema {
    if (mappingJson.length > MAX_MAPPING_JSON_LENGTH) {
        return DpSchema.normalize(oversizedDefinitionInputs())
    }
    val mapping = runCatching { JSONObject(mappingJson) }.getOrNull() ?: return DpSchema.empty()
    val inputs = buildList {
        val keys = mapping.keys()
        while (keys.hasNext() && size <= DpSchema.MAX_DEFINITION_COUNT) {
            val id = keys.next()
            val definition = mapping.optJSONObject(id) ?: continue
            val values = definition.mappingValuesObject()
            add(
                DpDefinitionInput(
                    id = id,
                    code = definition.safePrimitiveText("code"),
                    declaredType = definition.safePrimitiveText("type"),
                    minimum = values.safePrimitiveText("min"),
                    maximum = values.safePrimitiveText("max"),
                    step = values.safePrimitiveText("step"),
                    scale = values.safePrimitiveText("scale"),
                    unit = values.safePrimitiveText("unit"),
                    enumValues = values.enumRange(),
                )
            )
        }
    }
    return DpSchema.normalize(inputs)
}

private fun JSONObject?.safePrimitiveText(name: String): String? {
    if (this == null) return null
    val value = opt(name) ?: return null
    if (value == JSONObject.NULL || value is JSONObject || value is JSONArray) return null
    return value.toString().takeIf { text -> text.length <= MAX_MAPPING_PRIMITIVE_LENGTH }
}

private fun JSONObject.mappingValuesObject(): JSONObject? = when (val values = opt("values")) {
    is JSONObject -> values
    is String -> values
        .takeIf { encoded -> encoded.length <= MAX_MAPPING_VALUES_JSON_LENGTH }
        ?.let { encoded -> runCatching { JSONObject(encoded) }.getOrNull() }
    else -> null
}

private fun JSONObject?.enumRange(): List<String> {
    val range = this?.optJSONArray("range") ?: return emptyList()
    return buildList {
        val boundedLength = minOf(range.length(), DpSchema.MAX_ENUM_VALUE_COUNT + 1)
        for (index in 0 until boundedLength) {
            val value = range.opt(index)
            if (value == null || value == JSONObject.NULL || value is JSONObject || value is JSONArray) {
                continue
            }
            add(
                value.toString().take(
                    DpSchema.MAX_ENUM_VALUE_LENGTH + 1,
                )
            )
        }
    }
}

private fun oversizedDefinitionInputs(): List<DpDefinitionInput> =
    (1..(DpSchema.MAX_DEFINITION_COUNT + 1)).map { id ->
        DpDefinitionInput(
            id = id.toString(),
            code = null,
            declaredType = null,
        )
    }

private const val MAX_MAPPING_JSON_LENGTH = 512 * 1024
private const val MAX_MAPPING_VALUES_JSON_LENGTH = 64 * 1024
private const val MAX_MAPPING_PRIMITIVE_LENGTH = 256
