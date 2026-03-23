package com.openclaw.app.asr

import com.openclaw.app.UiStrings

/** Speech recognition language options for the AR glasses ASR engine. */
enum class AppLanguage(val code: String, val locale: String) {
    CHINESE("zh",  "zh-CN"),
    ENGLISH("en",  "en-US"),
    JAPANESE("ja", "ja-JP"),
    KOREAN("ko",   "ko-KR"),
    FRENCH("fr",   "fr-FR"),
    GERMAN("de",   "de-DE"),
    SPANISH("es",  "es-ES"),
    RUSSIAN("ru",  "ru-RU");

    /** Human-readable name that respects the current UI language setting. */
    val displayName: String get() = UiStrings.langDisplayName(code)

    companion object {
        fun fromCode(code: String) = entries.first { it.code == code }
    }
}
