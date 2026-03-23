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
 * 百度翻译 API — 通用翻译接口
 * 文档：https://fanyi-api.baidu.com/doc/21
 * 申请：https://fanyi-api.baidu.com/ → 注册 → 通用翻译 API
 * 免费额度：标准版 100万字/月，高级版 200万字/月
 *
 * 签名算法：MD5(appid + q + salt + secretKey)
 */
class BaiduProvider : TranslationProvider {

    override val name = "百度翻译"
    override val isConfigured
        get() = TranslationConfig.Baidu.APP_ID.isNotBlank() &&
                TranslationConfig.Baidu.SECRET_KEY.isNotBlank()

    // 百度语言代码映射
    private val langMap = mapOf(
        "en" to "en",
        "ja" to "jp",   // 百度用 jp 而不是 ja
        "ko" to "kor",
        "fr" to "fra",
        "de" to "de",
        "es" to "spa",
        "ru" to "ru"
    )

    override suspend fun translate(text: String, fromLang: String): String {
        val appId = TranslationConfig.Baidu.APP_ID
        val secretKey = TranslationConfig.Baidu.SECRET_KEY
        val from = langMap[fromLang] ?: "en"
        val salt = System.currentTimeMillis().toString()
        val sign = md5("$appId$text$salt$secretKey")

        val q = URLEncoder.encode(text, "UTF-8")
        val url = "https://fanyi-api.baidu.com/api/trans/vip/translate" +
                "?q=$q&from=$from&to=zh&appid=$appId&salt=$salt&sign=$sign"

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = AppConfig.TRANSLATION_TIMEOUT_DOMESTIC_MS
        conn.readTimeout = AppConfig.TRANSLATION_TIMEOUT_DOMESTIC_MS
        return try {
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            if (json.has("error_code")) {
                val code = json.getString("error_code")
                val msg = json.optString("error_msg", "")
                "⚠ 百度翻译错误 $code：$msg"
            } else {
                val arr = json.getJSONArray("trans_result")
                buildString {
                    for (i in 0 until arr.length()) {
                        if (i > 0) append("\n")
                        append(arr.getJSONObject(i).getString("dst"))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
