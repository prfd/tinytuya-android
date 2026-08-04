package com.prfd.tinytuya.device.profiles

import com.prfd.tinytuya.device.core.profile.DeviceSupportLevel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class BuiltinSupportDocumentationTest {
    @Test
    fun `public support table matches built in profile evidence`() {
        val document = repositoryFile("SUPPORTED_DEVICES.md").readText().replace("\r\n", "\n")
        val actual = document
            .substringAfter(BEGIN_MARKER)
            .substringBefore(END_MARKER)
            .trim()
        val expected = buildList {
            add("| Profile | Family ID | Evidence | Profile claim |")
            add("| --- | --- | --- | --- |")
            BuiltinDeviceFamilies.definitions.forEach { definition ->
                add(
                    "| ${definition.presentation.typeLabel} | `${definition.id.value}` | " +
                        "${definition.support.level.publicLabel} | ${definition.support.summary} |"
                )
            }
        }.joinToString("\n")

        assertEquals(expected, actual)
    }

    private fun repositoryFile(name: String): File {
        var directory = File(System.getProperty("user.dir")).absoluteFile
        repeat(MAX_PARENT_SEARCH_DEPTH) {
            val candidate = File(directory, name)
            if (candidate.isFile) return candidate
            directory = directory.parentFile ?: return@repeat
        }
        error("Could not find $name from the test working directory.")
    }

    private val DeviceSupportLevel.publicLabel: String
        get() = when (this) {
            DeviceSupportLevel.REAL_HARDWARE -> "Real hardware"
            DeviceSupportLevel.SYNTHETIC_ONLY -> "Synthetic only"
            DeviceSupportLevel.EXPERIMENTAL -> "Experimental"
        }

    private companion object {
        const val BEGIN_MARKER = "<!-- BEGIN GENERATED DEVICE SUPPORT -->"
        const val END_MARKER = "<!-- END GENERATED DEVICE SUPPORT -->"
        const val MAX_PARENT_SEARCH_DEPTH = 5
    }
}
