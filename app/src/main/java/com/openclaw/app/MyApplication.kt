package com.openclaw.app

import android.app.Application
import com.ffalcon.mercury.android.sdk.MercurySDK
import java.util.Locale

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MercurySDK.init(this)
        // AppSettings must be initialised first so it can serve as the fallback tier
        // for AsrConfig / AgentConfig before RuntimeConfig loads openclaw.conf overrides.
        AppSettings.init(this)

        // ── UI language: auto-detect on first launch, then honour persisted choice ──
        val saved = AppSettings.uiLanguage
        val uiLang = if (saved.isNotEmpty()) {
            UiLanguage.fromCode(saved)
        } else {
            // First launch — detect from system locale
            val detected = UiLanguage.fromCode(Locale.getDefault().language)
            AppSettings.uiLanguage = detected.code
            detected
        }
        UiStrings.switchTo(uiLang)

        // RuntimeConfig must be initialised before any Config object (AgentConfig / AsrConfig)
        // is first accessed, so that runtime overrides take effect immediately.
        RuntimeConfig.init(this)
    }
}
