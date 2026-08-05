package com.prfd.tinytuya

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupThemeInstrumentedTest {
  @Test
  fun launchBackgroundMatchesLightAndDarkComposeBackgrounds() {
    assertThemeBackground(
      nightMode = Configuration.UI_MODE_NIGHT_NO,
      expectedColor = Color.rgb(0xF8, 0xFB, 0xF8),
    )
    assertThemeBackground(
      nightMode = Configuration.UI_MODE_NIGHT_YES,
      expectedColor = Color.rgb(0x0F, 0x15, 0x12),
    )
  }

  private fun assertThemeBackground(nightMode: Int, expectedColor: Int) {
    val appContext = InstrumentationRegistry.getInstrumentation().targetContext
    val configuration =
      Configuration(appContext.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
      }
    val configuredContext = appContext.createConfigurationContext(configuration)
    val themedContext = ContextThemeWrapper(configuredContext, R.style.Theme_Tinytuya)

    assertEquals(expectedColor, themedContext.resolveColor(android.R.attr.windowBackground))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      assertEquals(
        expectedColor,
        themedContext.resolveColor(android.R.attr.windowSplashScreenBackground),
      )
    }
  }

  private fun Context.resolveColor(attribute: Int): Int {
    val value = TypedValue()
    assertTrue(theme.resolveAttribute(attribute, value, true))
    return getColor(value.resourceId)
  }
}
