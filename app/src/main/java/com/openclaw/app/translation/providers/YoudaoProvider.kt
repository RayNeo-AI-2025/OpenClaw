package com.openclaw.app.translation.providers

import com.openclaw.app.AppConfig
import com.openclaw.app.translation.TranslationConfig
import com.openclaw.app.translation.TranslationProvider
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 有道智云翻译 API（v3，SHA256 签名）
 * 文档：https://ai.youdao.com/DOCSIRMA/html/trans/api/wbfy/index.html
 * 申请：https://ai.youdao.com/ → 创建应用 → 文本翻译
 * 免费额度：注册送 50元体验额度
 *
 * 签名算法（signType=v3）：
 *   input = q.length <= 20 ? q : q[0..10] + q.length + q[-10..]
 *   sign  = SHA256(appKey + input + salt + curtime + appSecret)
 */
class YoudaoProvider : TranslationProvider {

    override val name = "有道智云"
    override val isConfigured
        get() = TranslationConfig.Youdao.APP_KEY.isNotBlank() &&
                TranslationConfig.Youdao.APP_SECRET.isNotBlank()

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
        val appKey = TranslationConfig.Youdao.APP_KEY
        val appSecret = TranslationConfig.Youdao.APP_SECRET
        val from = langMap[fromLang] ?: "en"
        val salt = System.currentTimeMillis().toString()
        val curtime = (System.currentTimeMillis() / 1000).toString()
        val input = truncateForSign(text)
        val sign = sha256("$appKey$input$salt$curtime$appSecret")

        val params = buildString {
            append("q=${URLEncoder.encode(text, "UTF-8")}")
            append("&from=$from&to=zh-CHS")
            append("&appKey=$appKey")
            append("&salt=$salt")
            append("&sign=$sign")
            append("&signType=v3")
            append("&curtime=$curtime")
        }

        val conn = URL("https://openapi.youdao.com/api").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.doOutput = true
        conn.connectTimeout = AppConfig.TRANSLATION_TIMEOUT_DOMESTIC_MS
        conn.readTimeout = AppConfig.TRANSLATION_TIMEOUT_DOMESTIC_MS
        conn.outputStream.use { it.write(params.toByteArray()) }

        return try {
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val code = json.optString("errorCode", "0")
            if (code != "0") {
                "⚠ 有道翻译错误 $code（见错误码文档）"
            } else {
                val arr = json.getJSONArray("translation")
                buildString {
                    for (i in 0 until arr.length()) {
                        if (i > 0) append("\n")
                        append(arr.getString(i))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** 有道签名规则：超过 20 字符则截取首尾各 10 字符 + 总长度 */
    private fun truncateForSign(q: String): String =
        if (q.length <= 20) q
        else q.take(10) + q.length + q.takeLast(10)

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
