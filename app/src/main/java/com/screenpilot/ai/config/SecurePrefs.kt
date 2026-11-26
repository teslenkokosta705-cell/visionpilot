package com.screenpilot.ai.config

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecurePrefs(context: Context) {

    private val appContext = context.applicationContext

    private val secretPrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                "screenpilot_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            appContext.getSharedPreferences("screenpilot_secure_fallback", Context.MODE_PRIVATE)
        }
    }

    private val plainPrefs: SharedPreferences by lazy {
        appContext.getSharedPreferences("screenpilot_settings", Context.MODE_PRIVATE)
    }

    var apiKey: String
        get() = secretPrefs.getString(KEY_API_KEY, "") ?: ""
        set(value) {
            secretPrefs.edit().putString(KEY_API_KEY, value.trim()).apply()
        }

    var modelName: String
        get() = plainPrefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) {
            plainPrefs.edit().putString(KEY_MODEL, value.trim().ifEmpty { DEFAULT_MODEL }).apply()
        }

    var stepDelayMs: Long
        get() = plainPrefs.getLong(KEY_STEP_DELAY, 120L)
        set(value) {
            plainPrefs.edit().putLong(KEY_STEP_DELAY, value.coerceIn(0L, 3000L)).apply()
        }

    var maxSteps: Int
        get() = plainPrefs.getInt(KEY_MAX_STEPS, 25)
        set(value) {
            plainPrefs.edit().putInt(KEY_MAX_STEPS, value.coerceIn(5, 100)).apply()
        }

    var voiceEnabled: Boolean
        get() = plainPrefs.getBoolean(KEY_VOICE_ENABLED, true)
        set(value) {
            plainPrefs.edit().putBoolean(KEY_VOICE_ENABLED, value).apply()
        }

    var voiceName: String
        get() = plainPrefs.getString(KEY_VOICE_NAME, "") ?: ""
        set(value) {
            plainPrefs.edit().putString(KEY_VOICE_NAME, value).apply()
        }

    var freeModeEnabled: Boolean
        get() = plainPrefs.getBoolean(KEY_FREE_MODE, false)
        set(value) {
            plainPrefs.edit().putBoolean(KEY_FREE_MODE, value).apply()
        }

    var bubbleX: Int
        get() = plainPrefs.getInt(KEY_BUBBLE_X, 24)
        set(value) {
            plainPrefs.edit().putInt(KEY_BUBBLE_X, value).apply()
        }

    var bubbleY: Int
        get() = plainPrefs.getInt(KEY_BUBBLE_Y, 260)
        set(value) {
            plainPrefs.edit().putInt(KEY_BUBBLE_Y, value).apply()
        }

    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_MODEL = "gemini_model"
        private const val KEY_STEP_DELAY = "step_delay_ms"
        private const val KEY_MAX_STEPS = "max_steps"
        private const val KEY_VOICE_ENABLED = "voice_enabled"
        private const val KEY_VOICE_NAME = "voice_name"
        private const val KEY_FREE_MODE = "free_mode_enabled"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"
    }
}
