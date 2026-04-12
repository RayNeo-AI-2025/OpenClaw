package com.openclaw.app.asr

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.openclaw.app.AppConfig

/**
 * Local on-device speech recognition engine using sherpa-onnx.
 *
 * Uses the streaming zipformer transducer model for real-time STT.
 * Drop-in replacement for the DashScope-based [SpeechEngine] — same
 * callback contract (onPartial / onFinal / onError), same start/stop/release API.
 *
 * Audio: 16 kHz mono 16-bit PCM via AudioRecord (identical to DashScope engine).
 */
class LocalSpeechEngine(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    @Volatile private var isRunning = false

    // ── Sentence merge / debounce (mirrors DashScope SpeechEngine) ─────────
    private val sentenceBuffer = StringBuilder()
    private val debounceRunnable = Runnable { flushSentenceBuffer() }

    // ── Model paths (relative to assets/) ──────────────────────────────────
    companion object {
        private const val TAG = "LocalSpeechEngine"
        private const val MODEL_DIR = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17-mobile"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SAMPLES = 1600  // 100 ms @ 16 kHz
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Start recognition.
     * @param langHints ignored for local engine (model is English-only),
     *                  kept for API compatibility with DashScope SpeechEngine.
     */
    fun start(langHints: List<String>) {
        if (isRunning) return
        isRunning = true
        try {
            ensureRecognizer()
            stream = recognizer!!.createStream()
            startRecording()
            startDecodingLoop()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start local ASR", e)
            isRunning = false
            mainHandler.post { onError("Local ASR init failed: ${e.message}") }
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        flushSentenceBuffer()
        stopRecording()
        stream?.let { s ->
            s.inputFinished()
            // Final decode pass
            try {
                while (recognizer?.isReady(s) == true) {
                    recognizer?.decode(s)
                }
                val text = recognizer?.getResult(s)?.text?.trim() ?: ""
                if (text.isNotEmpty()) {
                    mainHandler.post { appendAndDebounce(text) }
                }
            } catch (_: Exception) { }
            try { s.release() } catch (_: Exception) { }
        }
        stream = null
    }

    fun release() {
        stop()
        mainHandler.removeCallbacks(debounceRunnable)
        recognizer?.release()
        recognizer = null
    }

    // ── Sentence merge logic (same as DashScope SpeechEngine) ──────────────

    private fun appendAndDebounce(text: String) {
        if (sentenceBuffer.isNotEmpty()) sentenceBuffer.append(" ")
        sentenceBuffer.append(text)
        onPartial(sentenceBuffer.toString())
        mainHandler.removeCallbacks(debounceRunnable)
        mainHandler.postDelayed(debounceRunnable, AppConfig.ASR_DEBOUNCE_MS)
    }

    private fun flushSentenceBuffer() {
        mainHandler.removeCallbacks(debounceRunnable)
        val merged = sentenceBuffer.toString().trim()
        sentenceBuffer.clear()
        if (merged.isNotEmpty()) onFinal(merged)
    }

    // ── Recognizer init ────────────────────────────────────────────────────

    private fun ensureRecognizer() {
        if (recognizer != null) return

        val featConfig = FeatureConfig(
            sampleRate = SAMPLE_RATE,
            featureDim = 80
        )

        val transducerConfig = OnlineTransducerModelConfig(
            encoder = "$MODEL_DIR/encoder-epoch-99-avg-1.int8.onnx",
            decoder = "$MODEL_DIR/decoder-epoch-99-avg-1.onnx",
            joiner  = "$MODEL_DIR/joiner-epoch-99-avg-1.int8.onnx"
        )

        val modelConfig = OnlineModelConfig(
            transducer = transducerConfig,
            tokens = "$MODEL_DIR/tokens.txt",
            numThreads = 2,
            debug = false,
            modelType = "zipformer"
        )

        val endpointConfig = EndpointConfig(
            rule1 = EndpointRule(false, 2.4f, 0f),
            rule2 = EndpointRule(true, 1.4f, 0f),
            rule3 = EndpointRule(false, 0f, 20f)
        )

        val config = OnlineRecognizerConfig(
            featConfig = featConfig,
            modelConfig = modelConfig,
            endpointConfig = endpointConfig,
            enableEndpoint = true,
            decodingMethod = "greedy_search"
        )

        recognizer = OnlineRecognizer(context.assets, config)
        Log.i(TAG, "sherpa-onnx OnlineRecognizer initialised")
    }

    // ── Audio recording ────────────────────────────────────────────────────

    private fun startRecording() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, FRAME_SAMPLES * 2 * AppConfig.ASR_AUDIO_BUFFER_MULTIPLIER)

        @Suppress("MissingPermission")
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            mainHandler.post { onError("Microphone init failed") }
            isRunning = false
            return
        }

        audioRecord!!.startRecording()

        recordingThread = Thread {
            val buf = ShortArray(FRAME_SAMPLES)
            while (isRunning && !Thread.currentThread().isInterrupted) {
                val read = audioRecord?.read(buf, 0, buf.size) ?: break
                if (read > 0) {
                    // Convert short[] to float[] normalised to [-1, 1]
                    val floats = FloatArray(read) { buf[it] / 32768.0f }
                    stream?.acceptWaveform(floats, SAMPLE_RATE)
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
        } catch (_: Exception) { }
        audioRecord = null
    }

    // ── Decoding loop ──────────────────────────────────────────────────────

    private var decodingThread: Thread? = null
    private var lastPartialText = ""

    private fun startDecodingLoop() {
        decodingThread = Thread {
            try {
                while (isRunning && !Thread.currentThread().isInterrupted) {
                    val s = stream ?: break
                    val rec = recognizer ?: break

                    while (rec.isReady(s)) {
                        rec.decode(s)
                    }

                    val result = rec.getResult(s)
                    val text = result.text.trim()

                    if (text.isNotEmpty() && text != lastPartialText) {
                        lastPartialText = text
                        val preview = if (sentenceBuffer.isNotEmpty()) {
                            "${sentenceBuffer} $text"
                        } else {
                            text
                        }
                        mainHandler.post { onPartial("$preview\u2026") }
                    }

                    if (rec.isEndpoint(s)) {
                        if (text.isNotEmpty()) {
                            lastPartialText = ""
                            mainHandler.post { appendAndDebounce(text) }
                        }
                        rec.reset(s)
                    }

                    Thread.sleep(30)  // ~30 ms poll interval
                }
            } catch (_: InterruptedException) {
                // Expected on stop
            } catch (e: Exception) {
                Log.e(TAG, "Decoding loop error", e)
                mainHandler.post { onError("ASR decode error: ${e.message}") }
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }
}
