package com.openclaw.app

/**
 * Lightweight UI language system for OpenClaw.
 *
 * Supports Chinese (zh) and English (en). On first launch the system locale
 * is auto-detected; the user can override via SettingsActivity at any time.
 *
 * Usage:  `S.tapToStart`  or  `S.voiceBadge("中文")`
 */
enum class UiLanguage(val code: String, val displayName: String) {
    ZH("zh", "中文"),
    EN("en", "English");

    companion object {
        fun fromCode(code: String): UiLanguage =
            if (code.startsWith("zh")) ZH else EN
    }
}

/**
 * All user-visible strings, keyed by the current [UiLanguage].
 * Accessed via the top-level alias [S] for brevity.
 */
object UiStrings {

    var lang: UiLanguage = UiLanguage.ZH
        private set

    fun switchTo(language: UiLanguage) { lang = language }

    private val isZh get() = lang == UiLanguage.ZH

    // ── AgentChatActivity ────────────────────────────────────────────────────

    val tapToStart        get() = if (isZh) "单击镜腿开始对话"      else "Tap to start"
    fun voiceBadge(name: String) = if (isZh) "语音: $name"         else "Voice: $name"
    val aiThinking        get() = if (isZh) "AI 思考中…"           else "AI thinking…"
    fun listening(name: String)  = if (isZh) "正在监听 $name…"     else "Listening $name…"
    val pausedTapContinue get() = if (isZh) "已暂停，单击继续"      else "Paused, tap to continue"
    val exitToast         get() = if (isZh) "退出"                  else "Exit"
    val speechNotReady    get() = if (isZh) "语音引擎未就绪"        else "Speech engine not ready"
    fun startListenToast(name: String, mode: String) =
        if (isZh) "开始监听: $name $mode".trim()
        else      "Listening: $name $mode".trim()
    val oneshotHint       get() = if (isZh) "（单次）"              else "(oneshot)"
    fun asrError(msg: String) =   if (isZh) "ASR 错误: $msg"       else "ASR error: $msg"
    val micPermRequired   get() = if (isZh) "需要麦克风权限才能使用" else "Microphone permission required"
    val resettingChat     get() = if (isZh) "正在重置对话…"         else "Resetting chat…"
    val chatReset         get() = if (isZh) "对话已重置"            else "Chat reset"

    // sidebar
    val sessionLabel      get() = if (isZh) "会话"                  else "Session"
    fun sessionInfo(count: Int) =
        if (isZh) "●  当前对话\n    已交流 $count 轮"
        else      "●  Current chat\n    $count turn(s)"

    // bottom hint bar
    val chatHintBar       get() = if (isZh)
        "单击: 对话  上/下划: 滚动  三击: 设置  双击: 退出"
    else
        "Tap: Talk  Swipe: Scroll  3-tap: Settings  2-tap: Exit"

    // ── SettingsActivity ─────────────────────────────────────────────────────

    val settingsTitle     get() = if (isZh) "⚙  设置"               else "⚙  Settings"
    val labelListenMode   get() = if (isZh) "监听模式"              else "Listen Mode"
    val labelAsrLanguage  get() = if (isZh) "识别语言"              else "ASR Language"
    val labelUiLanguage   get() = if (isZh) "界面语言"              else "UI Language"
    val labelReset        get() = if (isZh) "重置对话"              else "Reset Chat"
    val continuous        get() = if (isZh) "持续监听"              else "Continuous"
    val oneshot           get() = if (isZh) "单次识别"              else "One-shot"
    val savedAndReset     get() = if (isZh) "已保存，对话已重置"    else "Saved, chat reset"
    val settingsSaved     get() = if (isZh) "设置已保存"            else "Settings saved"
    val cancelled         get() = if (isZh) "已取消"                else "Cancelled"
    val settingsHintBar   get() = if (isZh)
        "上/下划: 切换项目  前/后划或单击: 切换值  双击: 保存  三击: 取消"
    else
        "Swipe ↑↓: Navigate  Swipe ←→ or Tap: Change  2-tap: Save  3-tap: Cancel"

    // ── AppLanguage display names ────────────────────────────────────────────

    fun langDisplayName(code: String): String = when (code) {
        "zh" -> if (isZh) "中文"       else "Chinese"
        "en" -> if (isZh) "英语"       else "English"
        "ja" -> if (isZh) "日语"       else "Japanese"
        "ko" -> if (isZh) "韩语"       else "Korean"
        "fr" -> if (isZh) "法语"       else "French"
        "de" -> if (isZh) "德语"       else "German"
        "es" -> if (isZh) "西班牙语"   else "Spanish"
        "ru" -> if (isZh) "俄语"       else "Russian"
        else -> code
    }
}

/** Short alias for [UiStrings] — keeps call-sites compact. */
val S = UiStrings
