package com.openclaw.app

import android.graphics.Color
import androidx.annotation.ColorInt

/**
 * ════════════════════════════════════════════════
 *  全局可调参数集中配置
 *
 *  将散落在各文件 companion object / 内联硬编码中的
 *  数值型、颜色型参数统一收敛到此处，方便调优和维护。
 *
 *  各子模块仍通过自身 Config（AgentConfig / AsrConfig /
 *  TranslationConfig）管理 API Key、端点等"业务配置"；
 *  本文件只负责"技术调参"。
 * ════════════════════════════════════════════════
 */
object AppConfig {

    // ═══════════════════════════════════════════════
    //  语音识别（SpeechEngine）
    // ═══════════════════════════════════════════════

    /** 句尾防抖等待时间（毫秒）。用户停顿超过此时间才视为说完，合并发送。 */
    const val ASR_DEBOUNCE_MS = 2000L

    /** WebSocket 保活 ping 间隔（秒）。 */
    const val ASR_WS_PING_INTERVAL_SECONDS = 20L

    /** AudioRecord 缓冲区为最小值的倍数。 */
    const val ASR_AUDIO_BUFFER_MULTIPLIER = 4

    // ═══════════════════════════════════════════════
    //  智能体网关（OpenClawClient）
    // ═══════════════════════════════════════════════

    /** HTTP 读超时（秒），覆盖 SSE 长连接场景。 */
    const val AGENT_READ_TIMEOUT_SECONDS = 120L

    /** HTTP 连接超时（秒）。 */
    const val AGENT_CONNECT_TIMEOUT_SECONDS = 30L

    /** 用于会话关联的用户标识（固定值可保持多轮上下文）。 */
    const val AGENT_USER_ID = "ar-glasses-user"

    /** 错误响应截取长度（字符数），防止日志过长。 */
    const val AGENT_ERROR_BODY_MAX_CHARS = 200

    // ═══════════════════════════════════════════════
    //  聊天界面（AgentChatActivity）
    // ═══════════════════════════════════════════════

    /** 流式渲染节流间隔（毫秒），控制 WebView 刷新频率。 */
    const val STREAM_RENDER_THROTTLE_MS = 120L

    /** WebView 渲染后滚动到底部的延迟（毫秒）。 */
    const val WEBVIEW_SCROLL_DELAY_MS = 60L

    /** 每次滑动手势滚动的像素数。 */
    const val SCROLL_STEP_PX = 320

    // ═══════════════════════════════════════════════
    //  翻译引擎 HTTP 超时（毫秒）
    // ═══════════════════════════════════════════════

    /** 国内翻译服务（百度、有道、腾讯、MyMemory）连接+读取超时。 */
    const val TRANSLATION_TIMEOUT_DOMESTIC_MS = 6000

    /** 海外翻译服务（Azure、DeepL）连接+读取超时，适当放宽。 */
    const val TRANSLATION_TIMEOUT_OVERSEAS_MS = 8000

    // ═══════════════════════════════════════════════
    //  主题色板 — 聊天界面（AgentChatActivity）
    // ═══════════════════════════════════════════════

    // ── 状态栏 ──
    @ColorInt @JvmField val COLOR_STATUS_IDLE      = Color.parseColor("#484838")
    @ColorInt @JvmField val COLOR_STATUS_LISTENING  = Color.parseColor("#00C896")
    @ColorInt @JvmField val COLOR_STATUS_THINKING   = Color.parseColor("#F5A30A")
    @ColorInt @JvmField val COLOR_STATUS_ERROR      = Color.parseColor("#FF5555")

    // ── 用户输入文本 ──
    @ColorInt @JvmField val COLOR_INPUT_EMPTY       = Color.parseColor("#181816")
    @ColorInt @JvmField val COLOR_INPUT_PARTIAL     = Color.parseColor("#808060")
    @ColorInt @JvmField val COLOR_INPUT_FINAL       = Color.parseColor("#EEE8DC")

    // ── 侧栏会话计数 ──
    @ColorInt @JvmField val COLOR_SESSION_IDLE      = Color.parseColor("#303028")
    @ColorInt @JvmField val COLOR_SESSION_ACTIVE    = Color.parseColor("#00C896")

    // ═══════════════════════════════════════════════
    //  主题色板 — 设置界面（SettingsActivity）
    // ═══════════════════════════════════════════════

    @ColorInt @JvmField val COLOR_ROW_SELECTED       = Color.parseColor("#1500C896")
    @ColorInt @JvmField val COLOR_LABEL_ACTIVE        = Color.parseColor("#00C896")
    @ColorInt @JvmField val COLOR_LABEL_IDLE          = Color.parseColor("#484838")
    @ColorInt @JvmField val COLOR_VALUE_ACTIVE        = Color.parseColor("#EEE8DC")
    @ColorInt @JvmField val COLOR_VALUE_IDLE          = Color.parseColor("#484838")

    // Reset 行使用橙色/警告色板
    @ColorInt @JvmField val COLOR_RESET_ROW_SELECTED = Color.parseColor("#15FF6B35")
    @ColorInt @JvmField val COLOR_RESET_ACTIVE       = Color.parseColor("#FF6B35")
    @ColorInt @JvmField val COLOR_RESET_IDLE         = Color.parseColor("#4A3020")
}
