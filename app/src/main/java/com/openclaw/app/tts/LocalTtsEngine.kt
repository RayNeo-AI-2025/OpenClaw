package com.openclaw.app.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig

/**
 * Local on-device TTS engine using sherpa-onnx with the Piper VITS model.
 *
 * Supports streaming playback: audio chunks are played via AudioTrack as
 * the model generates them (using the generateWithCallback API), so the
 * user hears audio before the full utterance is synthesised.
 *
 * Thread safety: [speak] runs generation + playback on a background thread.
 * Calling [stop] interrupts any in-progress speech immediately.
 */
class LocalTtsEngine(private val context: Context) {

    private var tts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private var speakThread: Thread? = null

    @Volatile private var isSpeaking = false

    companion object {
        private const val TAG = "LocalTtsEngine"
        private const val MODEL_DIR = "vits-piper-en_US-amy-low-int8"
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Speak the given text. If already speaking, the previous utterance is
     * stopped first. Generation and playback happen on a background thread.
     */
    fun speak(text: String) {
        if (text.isBlank()) return
        stop()  // cancel any previous utterance
        isSpeaking = true

        speakThread = Thread {
            try {
                ensureTts()
                val sampleRate = tts!!.sampleRate()

                // Set up AudioTrack for streaming playback
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT
                )
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuf, sampleRate * 4))  // ~1s buffer
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack = track
                track.play()

                // Generate with streaming callback — each chunk is played immediately
                tts!!.generateWithCallback(text, /* sid = */ 0, /* speed = */ 1.0f) { samples ->
                    if (!isSpeaking) return@generateWithCallback 0  // abort generation
                    track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                    if (!isSpeaking) 0 else 1  // 1 = continue, 0 = stop
                }

                // Wait for AudioTrack to drain remaining buffered audio
                if (isSpeaking) {
                    track.stop()
                }
                track.release()
                audioTrack = null
            } catch (e: Exception) {
                Log.e(TAG, "TTS speak failed", e)
            } finally {
                isSpeaking = false
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    /**
     * Stop any in-progress speech immediately.
     */
    fun stop() {
        isSpeaking = false
        try { audioTrack?.stop() } catch (_: Exception) { }
        try { audioTrack?.release() } catch (_: Exception) { }
        audioTrack = null
        speakThread?.interrupt()
        speakThread = null
    }

    /**
     * Release all resources. Call when the activity is destroyed.
     */
    fun release() {
        stop()
        tts?.release()
        tts = null
    }

    // ── TTS init ───────────────────────────────────────────────────────────

    private fun ensureTts() {
        if (tts != null) return

        val vitsConfig = OfflineTtsVitsModelConfig(
            model   = "$MODEL_DIR/en_US-amy-low.onnx",
            tokens  = "$MODEL_DIR/tokens.txt",
            dataDir = "$MODEL_DIR/espeak-ng-data"
        )

        val modelConfig = OfflineTtsModelConfig(
            vits = vitsConfig,
            numThreads = 2,
            debug = false
        )

        val ttsConfig = OfflineTtsConfig(
            model = modelConfig
        )

        tts = OfflineTts(context.assets, ttsConfig)
        Log.i(TAG, "sherpa-onnx OfflineTts initialised (sampleRate=${tts!!.sampleRate()}, speakers=${tts!!.numSpeakers()})")
    }
}
