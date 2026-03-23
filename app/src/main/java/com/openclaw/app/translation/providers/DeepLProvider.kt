package com.openclaw.app.translation.providers

import com.openclaw.app.AppConfig
import com.openclaw.app.translation.TranslationConfig
import com.openclaw.app.translation.TranslationProvider
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * DeepL Translation API（中国大陆需 VPN）
 * 文档：https://developers.deepl.com/docs/api-reference/translate
 * 申请：https://www.deepl.com/pro-api
 * 免费版：500K 字符/月；免费版 API Key 以 ":fx" 结尾
 *
 * 注意：免费版和付费版使用不同域名
 *   免费版：api-free.deepl.com
 *   付费版：api.deepl.com
 */
class DeepLProvider : TranslationProvider {

    override val name = "DeepL"
    override val isConfigured
        get() = TranslationConfig.DeepL.API_KEY.isNotBlank()

    // DeepL 目标语言：ZH 代表简体中文
    // 源语言代码（大写）
    private val langMap = mapOf(
        "en" to "EN",
        "ja" to "JA",
        "ko" to "KO",
        "fr" to "FR",
        "de" to "DE",
        "es" to "ES",
        "ru" to "RU"
    )

    override suspend fun translate(text: String, fromLang: String): String {
        val apiKey = TranslationConfig.DeepL.API_KEY
        val useFree = TranslationConfig.DeepL.USE_FREE_API
        val from = langMap[fromLang] ?: "EN"

        val host = if (useFree) "api-free.deepl.com" else "api.deepl.com"
        val params = "text=${URLEncoder.encode(text, "UTF-8")}" +
                "&source_lang=$from" +
                "&target_lang=ZH"

        val conn = URL("https://$host/v2/translate").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "DeepL-Auth-Key $apiKey")
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.doOutput = true
        conn.connectTimeout = AppConfig.TRANSLATION_TIMEOUT_OVERSEAS_MS
        conn.readTimeout = AppConfig.TRANSLATION_TIMEOUT_OVERSEAS_MS
        conn.outputStream.use { it.write(params.toByteArray()) }

        return try {
            val statusCode = conn.responseCode
            if (statusCode != 200) {
                val errBody = conn.errorStream?.bufferedReader()?.readText() ?: ""
                val msg = when (statusCode) {
                    403 -> "Key 无效或已失效"
                    456 -> "字符配额已用尽"
                    429 -> "请求过于频繁"
                    else -> "HTTP $statusCode $errBody"
                }
                "⚠ DeepL 错误：$msg"
            } else {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                json.getJSONArray("translations").getJSONObject(0).getString("text")
            }
        } finally {
            conn.disconnect()
        }
    }
}
