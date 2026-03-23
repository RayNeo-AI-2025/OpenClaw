package com.openclaw.app.translation.providers

import com.openclaw.app.AppConfig
import com.openclaw.app.translation.TranslationConfig
import com.openclaw.app.translation.TranslationProvider
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 微软 Azure Translator（中国大陆需 VPN）
 * 文档：https://learn.microsoft.com/azure/ai-services/translator/reference/v3-0-translate
 * 申请：https://azure.microsoft.com → 认知服务 → 翻译
 * 免费套餐（F0）：200 万字符/月
 *
 * 请求：POST /translate?api-version=3.0&from={from}&to=zh-Hans
 * Body：[{"Text":"..."}]
 * Header：Ocp-Apim-Subscription-Key, Ocp-Apim-Subscription-Region
 */
class AzureProvider : TranslationProvider {

    override val name = "Azure Translator"
    override val isConfigured
        get() = TranslationConfig.Azure.SUBSCRIPTION_KEY.isNotBlank()

    private val langMap = mapOf(
        "en" to "en",
        "ja" to "ja",
        "ko" to "ko",
        "fr" to "fr",
        "de" to "de",
        "es" to "es",
        "ru" to "ru"
    )

    override suspend fun translate(text: String, fromLang: String): String {
        val key    = TranslationConfig.Azure.SUBSCRIPTION_KEY
        val region = TranslationConfig.Azure.REGION
        val from   = langMap[fromLang] ?: "en"

        val endpoint = "https://api.cognitive.microsofttranslator.com/translate" +
                "?api-version=3.0&from=$from&to=zh-Hans"

        val body = JSONArray().put(JSONObject().put("Text", text)).toString()

        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Ocp-Apim-Subscription-Key", key)
        conn.setRequestProperty("Ocp-Apim-Subscription-Region", region)
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.doOutput = true
        conn.connectTimeout = AppConfig.TRANSLATION_TIMEOUT_OVERSEAS_MS
        conn.readTimeout = AppConfig.TRANSLATION_TIMEOUT_OVERSEAS_MS
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        return try {
            val statusCode = conn.responseCode
            if (statusCode != 200) {
                val errBody = conn.errorStream?.bufferedReader()?.readText() ?: ""
                val msg = when (statusCode) {
                    401 -> "订阅 Key 无效"
                    403 -> "配额或权限不足"
                    429 -> "请求频率过高"
                    else -> "HTTP $statusCode $errBody"
                }
                "⚠ Azure 错误：$msg"
            } else {
                val arr = JSONArray(conn.inputStream.bufferedReader().readText())
                arr.getJSONObject(0)
                    .getJSONArray("translations")
                    .getJSONObject(0)
                    .getString("text")
            }
        } finally {
            conn.disconnect()
        }
    }
}
