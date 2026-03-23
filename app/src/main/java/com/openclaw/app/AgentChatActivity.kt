package com.openclaw.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.annotation.ColorInt
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.openclaw.app.AppConfig
import com.openclaw.app.agent.AgentConfig
import com.openclaw.app.agent.OpenClawClient
import com.openclaw.app.asr.AppLanguage
import com.openclaw.app.asr.AsrConfig
import com.openclaw.app.asr.ListenMode
import com.openclaw.app.asr.SpeechEngine
import com.openclaw.app.databinding.ActivityAgentChatBinding
import com.openclaw.app.ui.MarkdownRenderer
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import com.ffalcon.mercury.android.sdk.ui.toast.FToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AgentChatActivity : BaseMirrorActivity<ActivityAgentChatBinding>() {

    private var speechEngine: SpeechEngine? = null
    private var openClawClient: OpenClawClient? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isListening = false
    private var isProcessing = false

    /**
     * Generation counter — bumped on every [resetConversation].
     * All streaming callbacks capture their generation at launch and silently
     * discard themselves if [generation] has moved on, preventing stale updates.
     */
    private var generation = 0

    private var sessionTurnCount = 0

    private var latestResponseMarkdown = ""
    private var responseScrollToBottom = true
    private val responseRenderRunnable = Runnable {
        renderResponseMarkdownNow(scrollToBottom = responseScrollToBottom)
    }

    // ── "Reply started" notification tone ────────────────────────────────
    private var soundPool: SoundPool? = null
    private var replyStartSoundId = 0
    private var replyTonePlayed = false

    // ─────────────────────── Lifecycle ───────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initSoundPool()
        ensureResponseWebViewsConfigured()
        initOpenClawClient()
        renderIdleUI()
        collectTempleActions()

        if (AppSettings.uiLanguage.isEmpty()) {
            // First launch — show language picker first; mic permission and
            // speech engine init are deferred to onResume after the user has
            // chosen their language, so all prompts appear in the right locale.
            startActivity(Intent(this, LanguagePickerActivity::class.java))
        } else {
            requestMicOrInitEngine()
        }
    }

    override fun onResume() {
        super.onResume()
        // Sync UiStrings with persisted choice (covers return from LanguagePickerActivity
        // or SettingsActivity where UI language may have changed).
        val savedLang = AppSettings.uiLanguage
        if (savedLang.isNotEmpty()) UiStrings.switchTo(UiLanguage.fromCode(savedLang))
        // Refresh all UI text (language badge, hint bar, status, etc.).
        refreshUiText()

        // Ensure mic permission / speech engine is initialised after language picker.
        if (speechEngine == null && AppSettings.uiLanguage.isNotEmpty()) {
            requestMicOrInitEngine()
        }

        // Execute a conversation reset requested from SettingsActivity.
        if (AppSettings.pendingReset) {
            AppSettings.pendingReset = false
            resetConversation()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(responseRenderRunnable)
        speechEngine?.release()
        openClawClient?.release()
        soundPool?.release()
        super.onDestroy()
    }

    // ─────────────────────── Init ───────────────────────

    private fun initOpenClawClient() {
        openClawClient = OpenClawClient(
            baseUrl        = AgentConfig.BASE_URL,
            agentId        = AgentConfig.AGENT_ID,
            token          = AgentConfig.GATEWAY_TOKEN,
            user           = AgentConfig.USER_ID,
            timeoutSeconds = AgentConfig.TIMEOUT_SECONDS
        )
    }

    private fun initSoundPool() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attrs).build()
        replyStartSoundId = soundPool!!.load(this, R.raw.reply_start, 1)
    }

    private fun initSpeechEngine() {
        speechEngine = SpeechEngine(
            context   = this,
            onPartial = { text -> setSpeechInput("$text…", isPartial = true) },
            onFinal   = { text ->
                setSpeechInput(text, isPartial = false)
                // Oneshot mode: stop listening as soon as a complete utterance arrives.
                // The user must tap again for the next query.
                if (AsrConfig.LISTEN_MODE == ListenMode.ONESHOT && isListening) {
                    isListening = false
                    stopListening()
                    setStatus(S.pausedTapContinue, COLOR_IDLE)
                }
                askAgent(text)
            },
            onError   = { msg -> setStatus(S.asrError(msg), COLOR_ERROR); isListening = false }
        )
    }

    // ─────────────────────── UI state ───────────────────────

    private fun renderIdleUI() {
        mBindingPair.updateView {
            tvStatus.text = S.tapToStart
            tvStatus.setTextColor(COLOR_IDLE)
            tvLanguage.text = S.voiceBadge(currentLanguageDisplayName())
            tvProvider.text = "OpenClaw"
            tvUserInput.text = ""
            tvUserInput.setTextColor(COLOR_INPUT_EMPTY)
            tvSidebarLabel.text = S.sessionLabel
            tvSessions.text = ""
            tvSessions.setTextColor(COLOR_SESSION_IDLE)
            tvHint.text = S.chatHintBar
        }
        setResponseMarkdown("", immediate = true, scrollToBottom = false)
    }

    /** Refresh all language-sensitive text (called from onResume after settings change). */
    private fun refreshUiText() {
        mBindingPair.updateView {
            tvLanguage.text = S.voiceBadge(currentLanguageDisplayName())
            tvSidebarLabel.text = S.sessionLabel
            tvHint.text = S.chatHintBar
            if (!isListening && !isProcessing) {
                tvStatus.text = S.tapToStart
            }
            if (sessionTurnCount > 0) {
                tvSessions.text = S.sessionInfo(sessionTurnCount)
            }
        }
    }

    private fun setStatus(text: String, @ColorInt color: Int = COLOR_IDLE) {
        mBindingPair.updateView {
            tvStatus.text = text
            tvStatus.setTextColor(color)
        }
    }

    private fun setSpeechInput(text: String, isPartial: Boolean = false) {
        mBindingPair.updateView {
            tvUserInput.text = text
            tvUserInput.setTextColor(
                when {
                    text.isBlank() -> COLOR_INPUT_EMPTY
                    isPartial      -> COLOR_INPUT_PARTIAL
                    else           -> COLOR_INPUT_FINAL
                }
            )
        }
    }

    private fun setResponseMarkdown(
        markdown: String,
        immediate: Boolean = false,
        scrollToBottom: Boolean = true
    ) {
        latestResponseMarkdown = markdown
        responseScrollToBottom = scrollToBottom
        mainHandler.removeCallbacks(responseRenderRunnable)
        if (immediate) mainHandler.post(responseRenderRunnable)
        else mainHandler.postDelayed(responseRenderRunnable, STREAM_RENDER_THROTTLE_MS)
    }

    // ─────────────────────── Session sidebar ───────────────────────

    private fun addSession() {
        sessionTurnCount++
        mBindingPair.updateView {
            tvSessions.text = S.sessionInfo(sessionTurnCount)
            tvSessions.setTextColor(COLOR_SESSION_ACTIVE)
        }
    }

    // ─────────────────────── WebView ───────────────────────

    private fun ensureResponseWebViewsConfigured() {
        mBindingPair.updateView { configureResponseWebView(wvResponse) }
    }

    private fun configureResponseWebView(webView: WebView) {
        if (webView.tag == WEBVIEW_READY_TAG) return
        webView.tag = WEBVIEW_READY_TAG
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
        webView.settings.apply {
            javaScriptEnabled = false
            domStorageEnabled = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            defaultTextEncodingName = "utf-8"
        }
        webView.loadDataWithBaseURL(
            ASSET_BASE_URL, MarkdownRenderer.buildHtmlDocument(""), "text/html", "utf-8", null
        )
    }

    private fun renderResponseMarkdownNow(scrollToBottom: Boolean) {
        val html = MarkdownRenderer.buildHtmlDocument(latestResponseMarkdown)
        mBindingPair.updateView {
            configureResponseWebView(wvResponse)
            wvResponse.loadDataWithBaseURL(ASSET_BASE_URL, html, "text/html", "utf-8", null)
            if (scrollToBottom) wvResponse.postDelayed({ wvResponse.scrollTo(0, Int.MAX_VALUE) }, AppConfig.WEBVIEW_SCROLL_DELAY_MS)
            else wvResponse.scrollTo(0, 0)
        }
    }

    /** Scroll AI response up (dy < 0) or down (dy > 0). */
    private fun scrollResponseBy(dy: Int) {
        mBindingPair.updateView { wvResponse.scrollBy(0, dy) }
    }

    // ─────────────────────── Agent interaction ───────────────────────

    private fun askAgent(text: String) {
        if (isProcessing) return
        isProcessing = true
        replyTonePlayed = false
        val myGen = generation
        setStatus(S.aiThinking, COLOR_THINKING)
        setResponseMarkdown("", immediate = true, scrollToBottom = false)

        lifecycleScope.launch(Dispatchers.IO) {
            openClawClient?.askStreaming(
                text = text,
                onDelta = { _, fullText ->
                    if (generation != myGen) return@askStreaming
                    if (!replyTonePlayed) {
                        replyTonePlayed = true
                        soundPool?.play(replyStartSoundId, 1f, 1f, 1, 0, 1f)
                    }
                    mainHandler.post { setResponseMarkdown(fullText, immediate = false, scrollToBottom = true) }
                },
                onComplete = { finalText ->
                    if (generation != myGen) return@askStreaming
                    mainHandler.post {
                        isProcessing = false
                        setResponseMarkdown(finalText, immediate = true, scrollToBottom = true)
                        addSession()
                        if (isListening) setStatus(S.listening(currentLanguageDisplayName()), COLOR_LISTENING)
                        else setStatus(S.pausedTapContinue, COLOR_IDLE)
                    }
                },
                onError = { error ->
                    if (generation != myGen) return@askStreaming
                    mainHandler.post {
                        isProcessing = false
                        setResponseMarkdown("⚠ $error", immediate = true, scrollToBottom = false)
                        if (isListening) setStatus(S.listening(currentLanguageDisplayName()), COLOR_LISTENING)
                        else setStatus(S.pausedTapContinue, COLOR_IDLE)
                    }
                }
            )
        }
    }

    /**
     * Full conversation reset (bound to TripleClick — avoids system long-press menu):
     *  1. Bumps generation → silently drops all in-flight streaming callbacks.
     *  2. Clears local UI immediately.
     *  3. Sends "/reset" to the agent server to wipe multi-turn context.
     */
    private fun resetConversation() {
        generation++
        isProcessing = false
        sessionTurnCount = 0

        setSpeechInput("", isPartial = false)
        setResponseMarkdown("", immediate = true, scrollToBottom = false)
        mBindingPair.updateView {
            tvSessions.text = ""
            tvSessions.setTextColor(COLOR_SESSION_IDLE)
        }

        val (statusText, statusColor) = if (isListening) {
            S.listening(currentLanguageDisplayName()) to COLOR_LISTENING
        } else {
            S.tapToStart to COLOR_IDLE
        }
        setStatus(statusText, statusColor)
        FToast.show(S.resettingChat)

        val myGen = generation
        lifecycleScope.launch(Dispatchers.IO) {
            openClawClient?.askStreaming(
                text       = "/reset",
                onDelta    = { _, _ -> },
                onComplete = { _ ->
                    if (generation == myGen) mainHandler.post { FToast.show(S.chatReset) }
                },
                onError    = { _ -> /* local already cleared */ }
            )
        }
    }

    // ─────────────────────── Speech control ───────────────────────

    private fun startListening() {
        speechEngine?.start(listOf(AsrConfig.LANGUAGE))
        setStatus(S.listening(currentLanguageDisplayName()), COLOR_LISTENING)
    }

    private fun stopListening() = speechEngine?.stop() ?: Unit

    private fun toggleListening() {
        if (speechEngine == null) { FToast.show(S.speechNotReady); return }
        isListening = !isListening
        if (isListening) {
            startListening()
            val modeHint = if (AsrConfig.LISTEN_MODE == ListenMode.ONESHOT) S.oneshotHint else ""
            FToast.show(S.startListenToast(currentLanguageDisplayName(), modeHint))
        } else {
            stopListening()
            setStatus(S.pausedTapContinue, COLOR_IDLE)
        }
    }

    // ─────────────────────── Helpers ───────────────────────

    /**
     * Returns the human-readable display name for the currently configured ASR language.
     * Falls back to the raw language code if the code is not in [AppLanguage].
     */
    private fun currentLanguageDisplayName(): String =
        try { AppLanguage.fromCode(AsrConfig.LANGUAGE).displayName }
        catch (_: Exception) { AsrConfig.LANGUAGE }

    // ─────────────────────── Gesture map ───────────────────────

    /**
     * Temple gesture bindings:
     *
     *  Click          — toggle listening on/off
     *  TripleClick    — open SettingsActivity (language / listen mode / reset)
     *  DoubleClick    — exit app
     *  SlideForward   — scroll AI response UP   (read previous)
     *  SlideBackward  — scroll AI response DOWN  (read more)
     *  SlideUpwards   — scroll AI response UP
     *  SlideDownwards — scroll AI response DOWN
     *
     * NOTE: LongClick deliberately NOT used — it triggers the system settings panel
     *       (WiFi, quick settings) before the app sees the event.
     *       DoubleFingerClick not used — physically impractical on the narrow X3 temple pad.
     *       "Reset conversation" lives inside SettingsActivity as the third row.
     */
    private fun collectTempleActions() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                templeActionViewModel.state.collect { action ->
                    when (action) {
                        is TempleAction.Click          -> toggleListening()
                        is TempleAction.TripleClick    -> openSettings()
                        is TempleAction.DoubleClick    -> { FToast.show(S.exitToast); finish() }
                        is TempleAction.SlideForward   -> scrollResponseBy(-SCROLL_STEP)
                        is TempleAction.SlideBackward  -> scrollResponseBy(+SCROLL_STEP)
                        is TempleAction.SlideUpwards   -> scrollResponseBy(-SCROLL_STEP)
                        is TempleAction.SlideDownwards -> scrollResponseBy(+SCROLL_STEP)
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // ─────────────────────── Permissions ───────────────────────

    private fun requestMicOrInitEngine() {
        if (hasMicPermission()) initSpeechEngine()
        else ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC
        )
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) initSpeechEngine()
        else setStatus(S.micPermRequired, COLOR_ERROR)
    }

    companion object {
        private const val REQ_MIC = 100
        private const val ASSET_BASE_URL = "file:///android_asset/"
        private const val WEBVIEW_READY_TAG = "agent_response_webview_ready"

        // ── 以下常量已迁移到 AppConfig，此处为别名方便本文件引用 ──
        private inline val STREAM_RENDER_THROTTLE_MS get() = AppConfig.STREAM_RENDER_THROTTLE_MS
        private inline val SCROLL_STEP               get() = AppConfig.SCROLL_STEP_PX

        private inline val COLOR_IDLE      get() = AppConfig.COLOR_STATUS_IDLE
        private inline val COLOR_LISTENING get() = AppConfig.COLOR_STATUS_LISTENING
        private inline val COLOR_THINKING  get() = AppConfig.COLOR_STATUS_THINKING
        private inline val COLOR_ERROR     get() = AppConfig.COLOR_STATUS_ERROR

        private inline val COLOR_INPUT_EMPTY   get() = AppConfig.COLOR_INPUT_EMPTY
        private inline val COLOR_INPUT_PARTIAL get() = AppConfig.COLOR_INPUT_PARTIAL
        private inline val COLOR_INPUT_FINAL   get() = AppConfig.COLOR_INPUT_FINAL

        private inline val COLOR_SESSION_IDLE   get() = AppConfig.COLOR_SESSION_IDLE
        private inline val COLOR_SESSION_ACTIVE get() = AppConfig.COLOR_SESSION_ACTIVE
    }
}
