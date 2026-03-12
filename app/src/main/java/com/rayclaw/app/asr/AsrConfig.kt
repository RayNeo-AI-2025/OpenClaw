package com.rayclaw.app.asr

import com.rayclaw.app.BuildConfig
import com.rayclaw.app.RuntimeConfig

/**
 * ════════════════════════════════════════════════
 *  语音识别（ASR）配置
 *
 *  优先级（高→低）：
 *   1. rayclaw.conf（运行时，开发者通过 adb push 配置，无需重新编译）
 *   2. local.properties → BuildConfig（编译时注入，baked into APK）
 *   3. 代码默认值
 *
 *  可配置项一览（rayclaw.conf 中可设置）：
 *
 *    DASHSCOPE_API_KEY=sk-xxxx
 *
 *    # 识别语言（BCP-47 代码），默认中文
 *    ASR_LANGUAGE=zh
 *
 *    # 监听模式，默认持续监听
 *    # continuous — 点击开始，再次点击才停止
 *    # oneshot    — 点击开始，收到完整句子后自动停止
 *    ASR_LISTEN_MODE=continuous
 *
 *  申请 DashScope Key：https://dashscope.console.aliyun.com/ → API-KEY 管理
 * ════════════════════════════════════════════════
 */

/** 麦克风的持续行为。 */
enum class ListenMode {
    /** 保持录音，直到用户再次单击（适合连续对话）。 */
    CONTINUOUS,

    /** 识别到完整句子后自动停止，用户需再次单击才能开始下一句（适合精确单次输入）。 */
    ONESHOT
}

object AsrConfig {

    // 运行时配置优先，fallback 到编译时 BuildConfig
    val DASHSCOPE_API_KEY: String
        get() = RuntimeConfig.get("DASHSCOPE_API_KEY", BuildConfig.DASHSCOPE_API_KEY)

    /**
     * 语音识别语言。
     * rayclaw.conf 键名：ASR_LANGUAGE
     * 取值：zh（中文）/ en / ja / ko / fr / de / es / ru
     * 默认：zh（中文）
     */
    val LANGUAGE: String
        get() = RuntimeConfig.get("ASR_LANGUAGE", "zh").trim().lowercase()

    /**
     * 监听模式。
     * rayclaw.conf 键名：ASR_LISTEN_MODE
     * 取值：continuous（默认）/ oneshot
     */
    val LISTEN_MODE: ListenMode
        get() = when (RuntimeConfig.get("ASR_LISTEN_MODE", "continuous").trim().lowercase()) {
            "oneshot", "one_shot", "one-shot" -> ListenMode.ONESHOT
            else -> ListenMode.CONTINUOUS
        }

    // ASR 模型（支持中英日韩法德西俄等多语言实时识别）
    const val MODEL = "paraformer-realtime-v2"

    // WebSocket 端点
    const val WS_ENDPOINT = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"

    // 音频参数（paraformer-realtime-v2 要求 16kHz 单声道 16-bit PCM）
    const val SAMPLE_RATE = 16000

    // 每帧字节数 = 100ms @ 16kHz 16-bit mono = 16000 * 2 * 0.1 = 3200 bytes
    const val FRAME_BYTES = 3200
}
