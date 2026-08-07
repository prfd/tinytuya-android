package com.prfd.tinytuya.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.prfd.tinytuya.ManualTestProbe
import com.prfd.tinytuya.ui.theme.Mint95
import com.prfd.tinytuya.ui.theme.Teal30
import com.prfd.tinytuya.ui.theme.Teal90
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exports BrandMark as 512x512 transparent PNG to the app cache.
 *
 * gradlew.bat installDebugAndroidTest adb shell am instrument -w -r -e \
 * class com.prfd.tinytuya.ui.components.BrandMarkPngInstrumentedTest#renderBrandMark
 * com.prfd.tinytuya.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Pipe the generated cache file to the local filesystem with: adb exec-out run-as com.prfd.tinytuya
 * cat cache/brand-mark-512.png > brand-mark-512.png
 */
@RunWith(AndroidJUnit4::class)
@ManualTestProbe
class BrandMarkPngInstrumentedTest {
  @Test
  fun renderBrandMark() {
    val bitmap = Bitmap.createBitmap(IMAGE_SIZE_PX, IMAGE_SIZE_PX, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(AndroidColor.TRANSPARENT)

    CanvasDrawScope().draw(
      density = Density(1f),
      layoutDirection = LayoutDirection.Ltr,
      canvas = Canvas(AndroidCanvas(bitmap)),
      size = Size(IMAGE_SIZE_PX.toFloat(), IMAGE_SIZE_PX.toFloat()),
    ) {
      drawBrandMark(EXPORT_COLORS)
    }

    assertEquals(0, AndroidColor.alpha(bitmap.getPixel(0, 0)))
    assertTrue(AndroidColor.alpha(bitmap.getPixel(IMAGE_SIZE_PX / 2, IMAGE_SIZE_PX / 2)) > 0)

    val output =
      File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, OUTPUT_FILE)
    output.outputStream().use { stream ->
      assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
    }

    assertTrue(output.isFile && output.length() > 0)
  }

  private companion object {
    const val IMAGE_SIZE_PX = 512
    const val OUTPUT_FILE = "brand-mark-512.png"
    val EXPORT_COLORS =
      BrandMarkColors(
        primary = Teal90,
        container = Teal30,
        onContainer = Mint95,
      )
  }
}
