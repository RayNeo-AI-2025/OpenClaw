package com.openclaw.app.agent

import com.openclaw.app.AppConfig
import com.openclaw.app.BuildConfig
import com.openclaw.app.RuntimeConfig

/**
 * ════════════════════════════════════════════════
 *  OpenClaw 智能体网关配置
 *
 *  优先级（高→低）：
 *   1. openclaw.conf（运行时，开发者通过 adb push 配置，无需重新编译）
 *   2. local.properties → BuildConfig（编译时注入，baked into APK）
 *
 *  openclaw.conf 示例（位于设备 Android/data/com.openclaw.app/files/）：
 *      OPENCLAW_GATEWAY_TOKEN=your-token-here
 *      OPENCLAW_BASE_URL=http://your-server:18789
 *      OPENCLAW_AGENT_ID=main
 *
 *  local.properties 编译时配置（仍作为兜底 fallback）：
 *      OPENCLAW_GATEWAY_TOKEN=your-token-here
 *      OPENCLAW_BASE_URL=http://your-server:18789
 *      OPENCLAW_AGENT_ID=main
 * ════════════════════════════════════════════════
 */
object AgentConfig {

    val GATEWAY_TOKEN: String
        get() = RuntimeConfig.get("OPENCLAW_GATEWAY_TOKEN", BuildConfig.OPENCLAW_GATEWAY_TOKEN)

    val BASE_URL: String
        get() = RuntimeConfig.get("OPENCLAW_BASE_URL", BuildConfig.OPENCLAW_BASE_URL)

    val AGENT_ID: String
        get() = RuntimeConfig.get("OPENCLAW_AGENT_ID", BuildConfig.OPENCLAW_AGENT_ID)

    /** HTTP 请求超时（秒） */
    inline val TIMEOUT_SECONDS: Long get() = AppConfig.AGENT_READ_TIMEOUT_SECONDS

    /** 用于会话关联的用户标识（固定值可保持多轮上下文） */
    inline val USER_ID: String get() = AppConfig.AGENT_USER_ID
}
