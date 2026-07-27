package com.example.lanvoicecaller.data.prefs

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

/**
 * Thin wrapper around SharedPreferences for persisting app settings.
 */
class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("lan_voice_caller_prefs", Context.MODE_PRIVATE)

    /** The user-chosen display name. Empty string if not yet set. */
    var displayName: String
        get() = prefs.getString(KEY_DISPLAY_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DISPLAY_NAME, value).apply()

    /** Stable UUID for this device — generated once and persisted. */
    val deviceId: String
        get() {
            val stored = prefs.getString(KEY_DEVICE_ID, null)
            if (stored != null) return stored
            val newId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
            return newId
        }

    /** Returns true if the user has completed the setup screen. */
    val isSetupDone: Boolean
        get() = displayName.isNotBlank()

    companion object {
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_DEVICE_ID = "device_id"
    }
}
