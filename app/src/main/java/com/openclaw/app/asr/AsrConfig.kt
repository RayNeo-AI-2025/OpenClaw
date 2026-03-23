package com.openclaw.app.asr

import com.openclaw.app.AppSettings
import com.openclaw.app.BuildConfig
import com.openclaw.app.RuntimeConfig

/**
 * ════════════════════════════════════════════════
 *  语音识别（ASR）配置
 *
 *  优先级（高→低）：
 *   1. openclaw.conf（运行时，开发者通过 adb push 配置，无需重新编译）
 *   2. AppSettings（用户通过眼镜内 SettingsActivity 配置，持久化到 SharedPreferences）
 *   3. local.properties → BuildConfig（编译时注入，baked into APK）
 *   4. 代码默认值
 *
 *  端点自动回退机制：
 *   - 默认先尝试国内端点（dashscope.aliyuncs.com + paraformer-realtime-v2）
 *   - 如果连接失败（DNS/网络），自动回退到国际版端点（dashscope-intl + fun-asr-realtime）
 *   - 成功的端点缓存到 AppSettings，下次直接使用
 *   - 如果 openclaw.conf 显式指定了端点，则只使用该端点不做回退
 *
 *  可配置项一览（openclaw.conf 中可设置）：
 *
 *    DASHSCOPE_API_KEY=sk-xxxx
 *    DASHSCOPE_ASR_MODEL=fun-asr-realtime
 *    DASHSCOPE_WS_ENDPOINT=wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference
 *    ASR_LANGUAGE=zh
 *    ASR_LISTEN_MODE=continuous
 *
 *  申请 DashScope Key：
 *    中国大陆用户：https://dashscope.console.aliyun.com/ → API-KEY 管理
 *    海外用户：https://bailian.console.alibabacloud.com/ → API-KEY 管理
 * ════════════════════════════════════════════════
 */

/** 麦克风的持续行为。 */
enum class ListenMode {
    /** 保持录音，直到用户再次单击（适合连续对话）。 */
    CONTINUOUS,

    /** 识别到完整句子后自动停止，用户需再次单击才能开始下一句（适合精确单次输入）。 */
    ONESHOT
}

/**
 * ASR 端点配置：一个 WebSocket 端点 + 对应的模型名称。
 * 国内与国际版的模型不同，必须配对使用。
 */
data class EndpointProfile(
    val tag: String,
    val endpoint: String,
    val model: String
)

object AsrConfig {

    // ── 内置端点配置 ─────────────────────────────────────
    private val DOMESTIC = EndpointProfile(
        tag      = "domestic",
        endpoint = "wss://dashscope.aliyuncs.com/api-ws/v1/inference",
        model    = "paraformer-realtime-v2"
    )
    private val INTERNATIONAL = EndpointProfile(
        tag      = "intl",
        endpoint = "wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference",
        model    = "fun-asr-realtime"
    )

    // 运行时配置优先，fallback 到编译时 BuildConfig
    val DASHSCOPE_API_KEY: String
        get() = RuntimeConfig.get("DASHSCOPE_API_KEY", BuildConfig.DASHSCOPE_API_KEY)

    /**
     * 语音识别语言。
     * 优先级：openclaw.conf → AppSettings（SettingsActivity）→ 默认 zh
     */
    val LANGUAGE: String
        get() = RuntimeConfig.get("ASR_LANGUAGE", AppSettings.language)
            .trim().lowercase().ifBlank { AppSettings.language }

    /**
     * 监听模式。
     * 优先级：openclaw.conf → AppSettings（SettingsActivity）→ 默认 continuous
     */
    val LISTEN_MODE: ListenMode
        get() = when (
            RuntimeConfig.get("ASR_LISTEN_MODE", AppSettings.listenModeKey)
                .trim().lowercase().ifBlank { "continuous" }
        ) {
            "oneshot", "one_shot", "one-shot" -> ListenMode.ONESHOT
            else -> ListenMode.CONTINUOUS
        }

    /**
     * 返回有序的端点候选列表供 SpeechEngine 逐一尝试。
     *
     * - 如果 openclaw.conf 显式指定了端点/模型 → 只返回该单一配置（不做回退）
     * - 否则 → 返回 [缓存成功的端点, 国内, 国际]（去重后）
     */
    fun getEndpointCandidates(): List<EndpointProfile> {
        // openclaw.conf 显式指定 → 只用该配置，不回退
        if (RuntimeConfig.isLoaded) {
            val confEndpoint = RuntimeConfig.get("DASHSCOPE_WS_ENDPOINT", "").trim()
            val confModel    = RuntimeConfig.get("DASHSCOPE_ASR_MODEL", "").trim()
            if (confEndpoint.isNotEmpty() || confModel.isNotEmpty()) {
                return listOf(EndpointProfile(
                    tag      = "conf-override",
                    endpoint = confEndpoint.ifEmpty { DOMESTIC.endpoint },
                    model    = confModel.ifEmpty { DOMESTIC.model }
                ))
            }
        }

        // 正常模式：缓存优先 → 国内 → 国际
        val candidates = mutableListOf<EndpointProfile>()
        val cached = AppSettings.cachedAsrEndpoint
        if (cached == INTERNATIONAL.tag) {
            candidates += INTERNATIONAL
        }
        candidates += DOMESTIC
        candidates += INTERNATIONAL
        return candidates.distinctBy { it.tag }
    }

    /** 记录上次连接成功的端点 tag，加速下次连接。 */
    fun cacheSuccessfulEndpoint(profile: EndpointProfile) {
        AppSettings.cachedAsrEndpoint = profile.tag
    }

    // 音频参数（16kHz 单声道 16-bit PCM）
    const val SAMPLE_RATE = 16000

    // 每帧字节数 = 100ms @ 16kHz 16-bit mono = 3200 bytes
    const val FRAME_BYTES = 3200
}
