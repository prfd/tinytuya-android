package com.prfd.tinytuya.data.local

import android.annotation.SuppressLint
import android.content.Context
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class InventoryDisplayMode(val storedValue: String) {
  COMPACT("compact"),
  FULL("full");

  companion object {
    fun fromStoredValue(value: String?): InventoryDisplayMode =
      entries.firstOrNull { mode -> mode.storedValue == value } ?: COMPACT
  }
}

data class AppSettings(
  val refreshWhenAppOpens: Boolean = true,
  val inventoryDisplayMode: InventoryDisplayMode = InventoryDisplayMode.COMPACT,
)

class AppSettingsStorageException(
  val code: String,
  message: String,
) : IllegalStateException(message)

interface AppSettingsStore {
  suspend fun load(): AppSettings

  suspend fun setRefreshWhenAppOpens(enabled: Boolean): AppSettings

  suspend fun setInventoryDisplayMode(mode: InventoryDisplayMode): AppSettings

  suspend fun deleteAll()
}

/** Stores non-sensitive user preferences. Application backup is disabled in the manifest. */
@SuppressLint("UseKtx") // KTX edit discards the Boolean commit result this store verifies.
class AndroidAppSettingsStore
internal constructor(
  context: Context,
  preferencesFile: String = PREFERENCES_FILE,
) : AppSettingsStore {
  private val preferences =
    context.applicationContext.getSharedPreferences(
      preferencesFile,
      Context.MODE_PRIVATE,
    )
  private val mutex = Mutex()

  override suspend fun load(): AppSettings =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        try {
          readSettings()
        } catch (error: CancellationException) {
          throw error
        } catch (_: Exception) {
          throw settingsError(
            code = "SETTINGS_READ_FAILED",
            message = "App settings could not be read safely.",
          )
        }
      }
    }

  override suspend fun setRefreshWhenAppOpens(enabled: Boolean): AppSettings =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        try {
          if (!preferences.edit().putBoolean(REFRESH_WHEN_APP_OPENS, enabled).commit()) {
            throw settingsError(
              code = "SETTINGS_WRITE_FAILED",
              message = "The refresh preference could not be saved.",
            )
          }
          readSettings()
        } catch (error: CancellationException) {
          throw error
        } catch (error: AppSettingsStorageException) {
          throw error
        } catch (_: Exception) {
          throw settingsError(
            code = "SETTINGS_WRITE_FAILED",
            message = "The refresh preference could not be saved.",
          )
        }
      }
    }

  override suspend fun setInventoryDisplayMode(mode: InventoryDisplayMode): AppSettings =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        try {
          if (!preferences.edit().putString(INVENTORY_DISPLAY_MODE, mode.storedValue).commit()) {
            throw settingsError(
              code = "SETTINGS_WRITE_FAILED",
              message = "The inventory view preference could not be saved.",
            )
          }
          readSettings()
        } catch (error: CancellationException) {
          throw error
        } catch (error: AppSettingsStorageException) {
          throw error
        } catch (_: Exception) {
          throw settingsError(
            code = "SETTINGS_WRITE_FAILED",
            message = "The inventory view preference could not be saved.",
          )
        }
      }
    }

  override suspend fun deleteAll(): Unit =
    withContext(Dispatchers.IO) {
      mutex.withLock {
        try {
          if (!preferences.edit().clear().commit()) {
            throw settingsError(
              code = "SETTINGS_DELETE_FAILED",
              message = "App settings could not be deleted.",
            )
          }
        } catch (error: CancellationException) {
          throw error
        } catch (error: AppSettingsStorageException) {
          throw error
        } catch (_: Exception) {
          throw settingsError(
            code = "SETTINGS_DELETE_FAILED",
            message = "App settings could not be deleted.",
          )
        }
      }
    }

  private fun settingsError(code: String, message: String) =
    AppSettingsStorageException(code = code, message = message)

  private fun readSettings(): AppSettings =
    AppSettings(
      refreshWhenAppOpens = preferences.getBoolean(REFRESH_WHEN_APP_OPENS, true),
      inventoryDisplayMode =
        InventoryDisplayMode.fromStoredValue(preferences.getString(INVENTORY_DISPLAY_MODE, null)),
    )

  private companion object {
    const val PREFERENCES_FILE = "app_settings"
    const val REFRESH_WHEN_APP_OPENS = "refresh_when_app_opens"
    const val INVENTORY_DISPLAY_MODE = "inventory_display_mode"
  }
}

/** Lightweight default for previews and ViewModel tests which do not exercise persistence. */
class InMemoryAppSettingsStore(initial: AppSettings = AppSettings()) : AppSettingsStore {
  private var settings = initial

  override suspend fun load(): AppSettings = settings

  override suspend fun setRefreshWhenAppOpens(enabled: Boolean): AppSettings =
    settings.copy(refreshWhenAppOpens = enabled).also { settings = it }

  override suspend fun setInventoryDisplayMode(mode: InventoryDisplayMode): AppSettings =
    settings.copy(inventoryDisplayMode = mode).also { settings = it }

  override suspend fun deleteAll() {
    settings = AppSettings()
  }
}
