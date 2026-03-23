package com.openclaw.app.translation.providers

import com.openclaw.app.AppConfig
import com.openclaw.app.translation.TranslationConfig
import com.openclaw.app.translation.TranslationProvider
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * MyMemory — 免费翻译接口，无需注册即可使用
 * 文档：https://mymemory.translated.net/doc/spec.php
 * 限额：匿名 5K字/天，注册邮箱后 50K字/天
 */
class MyMemoryProvider : TranslationProvider {

    override val name = "MyMemory（免费）"
    override val isConfigured = true  // 无需配置

    private val langMap = mapOf(
        "en" to "en-US",
        "ja" to "ja-JP",
        "ko" to "ko-KR",
        "fr" to "fr-FR",
        "de" to "de-DE",
        "es" to "es-ES",
        "ru" to "ru-RU"
    )

    override suspend fun translate(text: String, fromLang: String): String {
        val from = langMap[fromLang] ?: "en-US"
        val q = URLEncoder.encode(text, "UTF-8")
        var url = "https://api.mymemory.translated.net/get?q=$q&langpair=$from|zh-CN"

        val email = TranslationConfig.MyMemory.EMAIL
        if (email.isNotBlank()) url += "&de=${URLEncoder.encode(email, "UTF-8")}"

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = AppConfig.TRANSLATION_TIMEOUT_DOMESTIC_MS
        conn.readTimeout = AppConfig.TRANSLATION_TIMEOUT_DOMESTIC_MS
        return try {
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val status = json.optInt("responseStatus", 0)
            if (status != 200) {
                val msg = json.optString("responseDetails", "未知错误")
                "⚠ MyMemory 错误：$msg"
            } else {
                json.getJSONObject("responseData").getString("translatedText")
            }
        } finally {
            conn.disconnect()
        }
    }
}
