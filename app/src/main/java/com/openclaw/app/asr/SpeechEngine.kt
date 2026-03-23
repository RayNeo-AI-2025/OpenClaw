package com.openclaw.app.asr

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import com.openclaw.app.AppConfig
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 基于阿里云 DashScope paraformer-realtime-v2 的实时流式语音识别引擎。
 *
 * 协议流程：
 *  1. WebSocket 连接（Authorization: Bearer <API_KEY>）
 *  2. 发送 run-task JSON
 *  3. 收到 task-started → 开始 AudioRecord 录音并推流
 *  4. 持续收到 result-generated（sentence_end=false 为实时中间结果）
 *  5. 调用 stop() → 发送 finish-task → 断开连接
 *
 * 回调均在主线程触发。
 */
class SpeechEngine(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)   // WebSocket 长连接，不设读超时
        .pingInterval(AppConfig.ASR_WS_PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    @Volatile private var isRunning = false
    @Volatile private var taskStarted = false
    private var taskId = ""
    private var languageHints: List<String> = listOf("en")

    // ── 句子合并防抖 ────────────────────────────────────────────────────────
    // DashScope 在用户短暂停顿时就会发出 sentence_end，导致一句话被拆成多段。
    // 用缓冲区累积文本，在最后一个 sentence_end 后等待 AppConfig.ASR_DEBOUNCE_MS 再真正回调 onFinal。
    private val sentenceBuffer = StringBuilder()
    private val debounceRunnable = Runnable { flushSentenceBuffer() }

    // ── 公开 API ──────────────────────────────────────────────────────────────

    /**
     * 开始识别。
     * @param langHints DashScope language_hints，例如 ["en"]、["zh"]、["ja"]
     */
    fun start(langHints: List<String>) {
        if (isRunning) return
        isRunning = true
        languageHints = langHints
        taskId = UUID.randomUUID().toString()
        taskStarted = false
        connectWebSocket()
    }

    /**
     * 停止识别并释放 AudioRecord（WebSocket 也会关闭）。
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        taskStarted = false
        // 用户主动停止时立即发送缓冲区中已有的文本，不再等待防抖
        flushSentenceBuffer()
        stopRecording()
        sendFinishTask()
        webSocket?.close(1000, "stopped")
        webSocket = null
    }

    /**
     * 完全释放资源（stop + 关闭 OkHttpClient 线程池）。
     */
    fun release() {
        stop()
        mainHandler.removeCallbacks(debounceRunnable)
        client.dispatcher.executorService.shutdown()
    }

    // ── 句子合并逻辑 ────────────────────────────────────────────────────────────

    /**
     * 将一段完成的句子追加到缓冲区，并重置防抖计时器。
     * 在 [AppConfig.ASR_DEBOUNCE_MS] 内没有新句子到达时触发 [flushSentenceBuffer]。
     */
    private fun appendAndDebounce(text: String) {
        if (sentenceBuffer.isNotEmpty()) sentenceBuffer.append(" ")
        sentenceBuffer.append(text)
        // 展示已缓冲文本（无 "…" 后缀，表示该段已确认）
        onPartial(sentenceBuffer.toString())
        // 重置防抖计时器
        mainHandler.removeCallbacks(debounceRunnable)
        mainHandler.postDelayed(debounceRunnable, AppConfig.ASR_DEBOUNCE_MS)
    }

    /**
     * 防抖到期，将缓冲区内容作为最终结果回调。
     */
    private fun flushSentenceBuffer() {
        mainHandler.removeCallbacks(debounceRunnable)
        val merged = sentenceBuffer.toString().trim()
        sentenceBuffer.clear()
        if (merged.isNotEmpty()) onFinal(merged)
    }

    // ── 端点回退 ──────────────────────────────────────────────────────────────

    /** 当前尝试的候选端点列表和索引 */
    private var endpointCandidates: List<EndpointProfile> = emptyList()
    private var currentCandidateIndex = 0

    /** 当前正在使用的端点配置（连接成功后由 onOpen 缓存） */
    private var activeProfile: EndpointProfile? = null

    // ── WebSocket ─────────────────────────────────────────────────────────────

    private fun connectWebSocket() {
        val apiKey = AsrConfig.DASHSCOPE_API_KEY
        if (apiKey.isBlank()) {
            mainHandler.post { onError("DashScope API Key 未填写，请在 AsrConfig.kt 中配置") }
            isRunning = false
            return
        }

        endpointCandidates = AsrConfig.getEndpointCandidates()
        currentCandidateIndex = 0
        tryNextEndpoint(apiKey)
    }

    private fun tryNextEndpoint(apiKey: String) {
        if (currentCandidateIndex >= endpointCandidates.size) {
            // 所有候选端点都已失败
            isRunning = false
            mainHandler.post { onError("ASR 连接失败: 所有端点均不可用") }
            return
        }

        val profile = endpointCandidates[currentCandidateIndex]
        activeProfile = profile
        Log.i(TAG, "Trying endpoint [${profile.tag}]: ${profile.endpoint}, model: ${profile.model}")

        val request = Request.Builder()
            .url(profile.endpoint)
            .header("Authorization", "Bearer $apiKey")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected via [${profile.tag}]")
                AsrConfig.cacheSuccessfulEndpoint(profile)
                val runTask = buildRunTaskJson()
                Log.i(TAG, "Sending run-task: $runTask")
                ws.send(runTask)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Endpoint [${profile.tag}] failed: ${t.message}", t)
                if (!isRunning) return

                val nextIdx = currentCandidateIndex + 1
                if (nextIdx < endpointCandidates.size) {
                    // 还有候选端点，尝试下一个
                    Log.i(TAG, "Falling back to next endpoint candidate...")
                    currentCandidateIndex = nextIdx
                    tryNextEndpoint(apiKey)
                } else {
                    // 全部失败
                    isRunning = false
                    stopRecording()
                    mainHandler.post { onError("ASR 连接失败: ${t.message}") }
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(code, reason)
            }
        })
    }

    private fun handleServerMessage(text: String) {
        try {
            val json = JSONObject(text)
            val header = json.optJSONObject("header") ?: return
            when (header.optString("event")) {
                "task-started" -> {
                    taskStarted = true
                    startRecording()
                }
                "result-generated" -> {
                    val sentence = json
                        .optJSONObject("payload")
                        ?.optJSONObject("output")
                        ?.optJSONObject("sentence") ?: return
                    val transcript = sentence.optString("text").trim()
                    if (transcript.isBlank()) return
                    val isFinal = sentence.optBoolean("sentence_end", false)
                    if (isFinal) {
                        mainHandler.post { appendAndDebounce(transcript) }
                    } else {
                        // 中间结果：展示缓冲区已有文本 + 当前正在说的内容
                        mainHandler.post {
                            val preview = if (sentenceBuffer.isNotEmpty())
                                "${sentenceBuffer} $transcript" else transcript
                            onPartial(preview)
                        }
                    }
                }
                "task-failed" -> {
                    val errCode = header.optString("error_code", "")
                    val errMsg = header.optString("error_message", "识别任务失败")
                    Log.e(TAG, "task-failed: code=$errCode, message=$errMsg")
                    Log.e(TAG, "task-failed full response: $text")
                    isRunning = false
                    stopRecording()
                    mainHandler.post { onError(errMsg) }
                }
                "task-finished" -> { /* 正常结束，无需处理 */ }
            }
        } catch (_: Exception) {
            // 忽略解析错误
        }
    }

    // ── 音频录制 ──────────────────────────────────────────────────────────────

    private fun startRecording() {
        val minBuf = AudioRecord.getMinBufferSize(
            AsrConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, AsrConfig.FRAME_BYTES * AppConfig.ASR_AUDIO_BUFFER_MULTIPLIER)

        @Suppress("MissingPermission")
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            AsrConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            mainHandler.post { onError("麦克风初始化失败") }
            isRunning = false
            return
        }

        audioRecord!!.startRecording()

        recordingThread = Thread {
            val frame = ByteArray(AsrConfig.FRAME_BYTES)
            while (isRunning && !Thread.currentThread().isInterrupted) {
                val read = audioRecord?.read(frame, 0, frame.size) ?: break
                if (read > 0 && taskStarted) {
                    val bytes = if (read < frame.size) frame.copyOf(read) else frame
                    webSocket?.send(bytes.toByteString())
                }
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun stopRecording() {
        recordingThread?.interrupt()
        recordingThread = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }

    private fun sendFinishTask() {
        try {
            webSocket?.send(buildFinishTaskJson())
        } catch (_: Exception) {}
    }

    // ── JSON 构建 ─────────────────────────────────────────────────────────────

    private fun buildRunTaskJson(): String = JSONObject().apply {
        put("header", JSONObject().apply {
            put("action", "run-task")
            put("task_id", taskId)
            put("streaming", "duplex")
        })
        put("payload", JSONObject().apply {
            put("task_group", "audio")
            put("task", "asr")
            put("function", "recognition")
            put("model", activeProfile?.model ?: "paraformer-realtime-v2")
            put("parameters", JSONObject().apply {
                put("format", "pcm")
                put("sample_rate", AsrConfig.SAMPLE_RATE)
                put("language_hints", JSONArray(languageHints))
                put("punctuation_prediction_enabled", true)
                put("disfluency_removal_enabled", true)
                put("inverse_text_normalization_enabled", true)
            })
            put("input", JSONObject())
        })
    }.toString()

    companion object {
        private const val TAG = "SpeechEngine"
    }

    private fun buildFinishTaskJson(): String = JSONObject().apply {
        put("header", JSONObject().apply {
            put("action", "finish-task")
            put("task_id", taskId)
            put("streaming", "duplex")
        })
        put("payload", JSONObject().apply {
            put("input", JSONObject())
        })
    }.toString()
}
