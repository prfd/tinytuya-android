package com.prfd.tinytuya.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSettingsStoreInstrumentedTest {
    @Test
    fun preferenceDefaultsOnPersistsAndResetsWithLocalData() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferencesFile = "app_settings_test_${UUID.randomUUID()}"
        try {
            val firstStore = AndroidAppSettingsStore(context, preferencesFile)
            assertTrue(firstStore.load().refreshWhenAppOpens)

            firstStore.setRefreshWhenAppOpens(false)

            val reopenedStore = AndroidAppSettingsStore(context, preferencesFile)
            assertFalse(reopenedStore.load().refreshWhenAppOpens)

            reopenedStore.deleteAll()
            assertTrue(reopenedStore.load().refreshWhenAppOpens)
        } finally {
            context.deleteSharedPreferences(preferencesFile)
        }
    }
}
