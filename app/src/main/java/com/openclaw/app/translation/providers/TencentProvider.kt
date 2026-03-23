package com.openclaw.app.translation.providers

import com.openclaw.app.AppConfig
import com.openclaw.app.translation.TranslationConfig
import com.openclaw.app.translation.TranslationProvider
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 腾讯云翻译（TMT）— TC3-HMAC-SHA256 签名
 * 文档：https://cloud.tencent.com/document/product/551/15619
 * 申请：https://cloud.tencent.com/product/tmt
 * 免费额度：每月 500 万字符
 *
 * 签名流程（TC3）：
 *   1. CanonicalRequest = Method + URI + QueryStr + Headers + SignedHeaders + HashedBody
 *   2. StringToSign = Algorithm + Timestamp + CredentialScope + HashedCanonical
 *   3. SigningKey = HMAC256(HMAC256(HMAC256("TC3"+SecretKey, date), service), "tc3_request")
 *   4. Signature = HEX(HMAC256(SigningKey, StringToSign))
 */
class TencentProvider : TranslationProvider {

    override val name = "腾讯翻译"
    override val isConfigured
        get() = TranslationConfig.Tencent.SECRET_ID.isNotBlank() &&
                TranslationConfig.Tencent.SECRET_KEY.isNotBlank()

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
        val secretId  = TranslationConfig.Tencent.SECRET_ID
        val secretKey = TranslationConfig.Tencent.SECRET_KEY
        val region    = TranslationConfig.Tencent.REGION
        val from = langMap[fromLang] ?: "en"

        val timestamp = System.currentTimeMillis() / 1000
        val host = "tmt.tencentcloudapi.com"
        val service = "tmt"
        val action = "TextTranslate"
        val version = "2018-03-21"

        val payload = """{"SourceText":${JSONObject.quote(text)},"Source":"$from","Target":"zh","ProjectId":0}"""

        val date = utcDate(timestamp)
        val credentialScope = "$date/$service/tc3_request"
        val authorization = buildAuthorization(
            secretId, secretKey, host, service, payload,
            timestamp, date, credentialScope
        )

        val conn = URL("https://$host").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", authorization)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Host", host)
        conn.setRequestProperty("X-TC-Action", action)
        conn.setRequestProperty("X-TC-Version", version)
        conn.setRequestProperty("X-TC-Timestamp", timestamp.toString())
        conn.setRequestProperty("X-TC-Region", region)
        conn.doOutput = true
        conn.connectTimeout = AppConfig.TRANSLATION_TIMEOUT_DOMESTIC_MS
        conn.readTimeout = AppConfig.TRANSLATION_TIMEOUT_DOMESTIC_MS
        conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

        return try {
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body).getJSONObject("Response")
            if (json.has("Error")) {
                val err = json.getJSONObject("Error")
                "⚠ 腾讯翻译错误 ${err.getString("Code")}：${err.getString("Message")}"
            } else {
                json.getString("TargetText")
            }
        } catch (e: Exception) {
            // 尝试读取错误流
            val errBody = conn.errorStream?.bufferedReader()?.readText() ?: e.message
            "⚠ 腾讯翻译请求失败：${errBody?.take(80)}"
        } finally {
            conn.disconnect()
        }
    }

    private fun buildAuthorization(
        secretId: String, secretKey: String,
        host: String, service: String, payload: String,
        timestamp: Long, date: String, credentialScope: String
    ): String {
        val contentType = "application/json"
        val signedHeaders = "content-type;host"
        val hashedPayload = sha256Hex(payload)

        val canonicalRequest =
            "POST\n/\n\ncontent-type:$contentType\nhost:$host\n\n$signedHeaders\n$hashedPayload"

        val stringToSign =
            "TC3-HMAC-SHA256\n$timestamp\n$credentialScope\n${sha256Hex(canonicalRequest)}"

        val secretDate    = hmacSha256(("TC3$secretKey").toByteArray(Charsets.UTF_8), date)
        val secretService = hmacSha256(secretDate, service)
        val secretSigning = hmacSha256(secretService, "tc3_request")
        val signature     = hmacSha256Hex(secretSigning, stringToSign)

        return "TC3-HMAC-SHA256 Credential=$secretId/$credentialScope, " +
                "SignedHeaders=$signedHeaders, Signature=$signature"
    }

    private fun utcDate(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(timestamp * 1000))

    private fun sha256Hex(data: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256")
            .also { it.init(SecretKeySpec(key, "HmacSHA256")) }
            .doFinal(data.toByteArray(Charsets.UTF_8))

    private fun hmacSha256Hex(key: ByteArray, data: String): String =
        hmacSha256(key, data).joinToString("") { "%02x".format(it) }
}
