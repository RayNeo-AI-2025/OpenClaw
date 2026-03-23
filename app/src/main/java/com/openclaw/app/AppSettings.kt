package com.openclaw.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent in-glasses user settings backed by SharedPreferences.
 *
 * Priority chain for ASR parameters (high → low):
 *  1. openclaw.conf  — runtime developer override (adb push)
 *  2. AppSettings   — user-configured via SettingsActivity (this file)
 *  3. Code defaults
 *
 * Must be initialised via [init] before any access.
 * Call [init] in [MyApplication.onCreate] before RuntimeConfig.
 */
object AppSettings {

    private const val PREFS_NAME      = "openclaw_settings"
    private const val KEY_LANGUAGE    = "asr_language"
    private const val KEY_LISTEN_MODE = "asr_listen_mode"
    private const val KEY_UI_LANGUAGE = "ui_language"

    private lateinit var prefs: SharedPreferences

    /** Call once in [MyApplication.onCreate] before any Config object is accessed. */
    fun init(context: Context) {
        prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * BCP-47 language code (e.g. "zh", "en", "ja").
     * Default: "zh" (Mandarin Chinese).
     */
    var language: String
        get() = prefs.getString(KEY_LANGUAGE, "zh") ?: "zh"
        set(value) { prefs.edit().putString(KEY_LANGUAGE, value).apply() }

    /**
     * Listen mode key: "continuous" or "oneshot".
     * Default: "continuous".
     */
    var listenModeKey: String
        get() = prefs.getString(KEY_LISTEN_MODE, "continuous") ?: "continuous"
        set(value) { prefs.edit().putString(KEY_LISTEN_MODE, value).apply() }

    /**
     * UI display language code: "zh" or "en".
     * Empty string "" means not yet set (triggers auto-detection on first launch).
     */
    var uiLanguage: String
        get() = prefs.getString(KEY_UI_LANGUAGE, "") ?: ""
        set(value) { prefs.edit().putString(KEY_UI_LANGUAGE, value).apply() }

    /**
     * In-memory flag: SettingsActivity sets this to true when the user
     * requests a conversation reset. AgentChatActivity consumes it in onResume().
     * Not persisted — intentionally lost on process restart.
     */
    @Volatile
    var pendingReset: Boolean = false
}
