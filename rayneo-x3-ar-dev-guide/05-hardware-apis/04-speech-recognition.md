# 语音转文本（STT）：在 AR 眼镜上实现实时语音识别

> **核心结论**：RayNeo X3 没有可用的本地语音识别引擎。必须用 `AudioRecord` 直接采集 PCM 音频，再通过云端 ASR API 完成识别。本文记录了这一结论的完整推导过程及生产可用的实现方案。

---

## 1. 为什么 Android SpeechRecognizer 在 X3 上无效

### 1.1 直觉上的方案（行不通）

Android 标准语音识别 API 是 `SpeechRecognizer`，用法如下：

```kotlin
// ❌ 在 RayNeo X3 上无效
val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
recognizer.startListening(intent)
// → onReadyForSpeech 永远不会触发，或立即 onError
```

这段代码在普通 Android 手机上运行正常，但在 X3 上点击触发后**界面毫无反应**，不会有任何错误弹出。

### 1.2 根本原因：设备上没有通用 ASR 引擎

排查步骤：

```bash
# 查看设备上有哪些语音识别服务
adb shell pm query-services --permission android.permission.BIND_VOICE_INTERACTION

# 或者直接查询 aispeech 包
adb shell pm dump com.rayneo.aispeech | grep -i "recogni"
```

结论：X3 上存在的语音服务是 `com.rayneo.aispeech/.wakeup.RayNeoRecognitionService`，这是一个**唤醒词检测服务**，仅用于识别 "Hi RayNeo" 等固定词汇来唤醒设备，不支持任意内容的语音识别。

`SpeechRecognizer` 在调用时会查找系统里的 ASR 引擎（需要实现 `RecognitionService`），而 X3 上没有安装 Google 语音服务，也没有第三方通用 ASR 引擎。所以调用后系统找不到对应组件，直接失败。

### 1.3 验证方法

```kotlin
// 这行代码返回 false 即可确认设备没有 ASR 引擎
val available = SpeechRecognizer.isRecognitionAvailable(context)
// X3 上返回：false（或返回 true 但后续报错 ERROR_SERVER）
```

---

## 2. 正确方案：AudioRecord + 云端 ASR

既然设备没有本地引擎，解决思路是：

```
麦克风 ──AudioRecord──► 原始 PCM 音频帧 ──WebSocket──► 云端 ASR API ──► 识别文本
```

这套方案完全基于 Android 标准 API，与设备无关：

| 组件 | 选型 | 原因 |
|------|------|------|
| 音频采集 | `AudioRecord` | Android 标准 API，X3 麦克风支持 |
| 网络传输 | OkHttp WebSocket | 已是项目依赖，支持二进制帧 |
| 云端 ASR | 阿里云 DashScope `paraformer-realtime-v2` | 真正流式、无会话时长限制、支持中英日韩等多语言、认证最简单 |

---

## 3. 云端 ASR 选型对比

在选型时评估了三个主流方案：

| 方案 | 认证复杂度 | 实时流式 | 语言支持 | 会话限制 | 推荐 |
|------|-----------|---------|---------|---------|------|
| **DashScope paraformer-realtime-v2** | 低（Bearer Token） | ✅ WebSocket 双工 | 中英日韩法德西俄 | 无限制 | ⭐ **首选** |
| 科大讯飞实时 ASR | 高（HMAC-SHA256 签名） | ✅ WebSocket | 中英 | 60秒/次 | 不推荐 |
| Azure Speech Services | 中（SDK 封装） | ✅ | 多语言 | 无限制 | 备选 |

选用 DashScope 的理由：
- 认证只需在 WebSocket 握手时加一个 Header：`Authorization: Bearer <key>`，无需计算签名
- 真正的全双工流式，说话时实时返回中间结果（`sentence_end: false`），句子结束后返回最终结果（`sentence_end: true`）
- `paraformer-realtime-v2` 支持多语言混合识别，无需切换模型
- 无 60 秒会话上限，适合 AR 场景下的持续监听

---

## 4. DashScope ASR WebSocket 协议

### 4.1 获取 API Key

1. 访问 DashScope 控制台：
   - 中国大陆用户：[dashscope.console.aliyun.com](https://dashscope.console.aliyun.com/)
   - 海外用户：[bailian.console.alibabacloud.com](https://bailian.console.alibabacloud.com/)（无需中国手机号）
2. 左侧菜单 → **API-KEY 管理** → 创建 API Key
3. 将 Key 填入 `asr/AsrConfig.kt`

> **海外用户注意**：国际版 API Key 须搭配国际版 WebSocket 端点 `wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference` 使用，两者不可混用。

### 4.2 连接参数

| 参数 | 值 |
|------|-----|
| 端点（国内） | `wss://dashscope.aliyuncs.com/api-ws/v1/inference` |
| 端点（国际） | `wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference` |
| 认证 Header | `Authorization: Bearer sk-xxxx` |
| 模型 | `paraformer-realtime-v2` |
| 音频格式 | PCM，16kHz，单声道，16-bit |

### 4.3 协议流程

```
客户端                                    DashScope 服务端
  │                                              │
  │──── WebSocket 握手（Authorization header） ──►│
  │                                              │
  │──── run-task（JSON）─────────────────────────►│
  │                                              │
  │◄─── task-started（JSON）────────────────────│
  │          ↕（此时开始推送音频）                │
  │──── 二进制 PCM 帧（3200 bytes / 100ms）────►│
  │──── 二进制 PCM 帧 ───────────────────────────►│
  │──── ...                                      │
  │◄─── result-generated（sentence_end=false）──│  ← 实时中间结果
  │◄─── result-generated（sentence_end=true）───│  ← 句子最终结果
  │──── ...（继续推送音频）                       │
  │                                              │
  │──── finish-task（JSON）─────────────────────►│
  │◄─── task-finished（JSON）───────────────────│
  │                                              │
  │──── WebSocket close(1000) ──────────────────►│
```

### 4.4 关键 JSON 报文

**`run-task`（客户端发送，连接建立后立即发送）**：

```json
{
  "header": {
    "action": "run-task",
    "task_id": "550e8400-e29b-41d4-a716-446655440000",
    "streaming": "duplex"
  },
  "payload": {
    "task_group": "audio",
    "task": "asr",
    "function": "recognition",
    "model": "paraformer-realtime-v2",
    "parameters": {
      "format": "pcm",
      "sample_rate": 16000,
      "language_hints": ["en"],
      "punctuation_prediction_enabled": true,
      "disfluency_removal_enabled": true,
      "inverse_text_normalization_enabled": true
    },
    "input": {}
  }
}
```

**`result-generated`（服务端推送，实时识别结果）**：

```json
{
  "header": {
    "event": "result-generated",
    "task_id": "550e8400-e29b-41d4-a716-446655440000"
  },
  "payload": {
    "output": {
      "sentence": {
        "text": "Hello world",
        "sentence_end": false
      }
    }
  }
}
```

- `sentence_end: false`：说话进行中，这是**实时中间结果**（可展示给用户但不做业务处理）
- `sentence_end: true`：句子结束，这是**最终确认结果**（用于翻译等下游处理）

**`finish-task`（客户端发送，停止时发送）**：

```json
{
  "header": {
    "action": "finish-task",
    "task_id": "550e8400-e29b-41d4-a716-446655440000",
    "streaming": "duplex"
  },
  "payload": {
    "input": {}
  }
}
```

---

## 5. 完整实现

### 5.1 项目结构

```
app/src/main/java/your/package/
├── asr/
│   ├── AsrConfig.kt       ← API Key 配置
│   └── SpeechEngine.kt    ← 核心引擎（AudioRecord + WebSocket）
└── YourActivity.kt        ← 使用 SpeechEngine
```

### 5.2 依赖配置（`app/build.gradle.kts`）

```kotlin
dependencies {
    // ...已有依赖...

    // OkHttp：WebSocket 传输
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
```

### 5.3 权限声明（`AndroidManifest.xml`）

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
```

两个权限缺一不可：
- `RECORD_AUDIO`：普通危险权限，需要在运行时动态申请
- `INTERNET`：普通权限，声明即可，无需动态申请

### 5.4 `asr/AsrConfig.kt`

```kotlin
package your.package.asr

object AsrConfig {

    // ★ 填入阿里云 DashScope API Key
    // 中国大陆用户：https://dashscope.console.aliyun.com/ → API-KEY 管理
    // 海外用户：https://bailian.console.alibabacloud.com/ → API-KEY 管理
    const val DASHSCOPE_API_KEY = ""

    // 模型：支持中英日韩法德西俄等多语言实时识别
    const val MODEL = "paraformer-realtime-v2"

    // WebSocket 端点
    // 国内：wss://dashscope.aliyuncs.com/api-ws/v1/inference（默认）
    // 国际：wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference（海外用户须改为此端点）
    const val WS_ENDPOINT = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"

    // 音频参数（必须与此一致，不可随意更改）
    const val SAMPLE_RATE = 16000   // Hz

    // 每帧字节数：100ms × 16000Hz × 2 bytes/sample = 3200 bytes
    const val FRAME_BYTES = 3200
}
```

### 5.5 `asr/SpeechEngine.kt`（核心引擎）

```kotlin
package your.package.asr

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 实时流式语音识别引擎。
 *
 * 使用方式：
 *   val engine = SpeechEngine(this, onPartial, onFinal, onError)
 *   engine.start(listOf("en"))   // 开始
 *   engine.stop()                // 暂停
 *   engine.release()             // Activity.onDestroy() 中调用
 *
 * 所有回调均在主线程触发。
 */
class SpeechEngine(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val onPartial: (String) -> Unit,   // 实时中间结果
    private val onFinal: (String) -> Unit,     // 句子最终结果
    private val onError: (String) -> Unit      // 错误
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)  // WebSocket 长连接，不设读超时
        .pingInterval(20, TimeUnit.SECONDS)      // 保活 ping，防止 NAT 超时断开
        .build()

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    @Volatile private var isRunning = false
    @Volatile private var taskStarted = false
    private var taskId = ""
    private var languageHints: List<String> = listOf("en")

    // ── 公开 API ──────────────────────────────────────────────────────────────

    /**
     * 开始识别。
     * @param langHints language_hints，例如 ["en"]、["zh"]、["ja"]、["ko"]
     *                  支持多个值以识别混合语言，例如 ["zh", "en"]
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
     * 停止识别，释放麦克风和 WebSocket。
     * 可多次调用（幂等）。
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        taskStarted = false
        stopRecording()
        sendFinishTask()
        webSocket?.close(1000, "stopped")
        webSocket = null
    }

    /**
     * 完全释放资源，在 Activity.onDestroy() 中调用。
     */
    fun release() {
        stop()
        client.dispatcher.executorService.shutdown()
    }

    // ── WebSocket 连接 ────────────────────────────────────────────────────────

    private fun connectWebSocket() {
        val apiKey = AsrConfig.DASHSCOPE_API_KEY
        if (apiKey.isBlank()) {
            mainHandler.post { onError("DashScope API Key 未配置，请填写 AsrConfig.kt") }
            isRunning = false
            return
        }

        val request = Request.Builder()
            .url(AsrConfig.WS_ENDPOINT)
            .header("Authorization", "Bearer $apiKey")  // 唯一的认证方式
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            // 连接成功 → 立即发送 run-task 启动识别任务
            override fun onOpen(ws: WebSocket, response: Response) {
                ws.send(buildRunTaskJson())
            }

            // 收到服务端消息（始终是 JSON 文本）
            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(text)
            }

            // 连接失败（网络问题、Key 错误等）
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (isRunning) {
                    isRunning = false
                    stopRecording()
                    mainHandler.post { onError("连接失败: ${t.message}") }
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(code, reason)
            }
        })
    }

    // ── 服务端消息处理 ─────────────────────────────────────────────────────────

    private fun handleServerMessage(text: String) {
        try {
            val json = JSONObject(text)
            val header = json.optJSONObject("header") ?: return
            when (header.optString("event")) {

                // 服务端就绪 → 此时才能开始推送音频
                "task-started" -> {
                    taskStarted = true
                    startRecording()   // AudioRecord 在这里才启动
                }

                // 识别结果（实时推送）
                "result-generated" -> {
                    val sentence = json
                        .optJSONObject("payload")
                        ?.optJSONObject("output")
                        ?.optJSONObject("sentence") ?: return
                    val transcript = sentence.optString("text").trim()
                    if (transcript.isBlank()) return
                    val isFinal = sentence.optBoolean("sentence_end", false)
                    mainHandler.post {
                        if (isFinal) onFinal(transcript)
                        else onPartial(transcript)
                    }
                }

                // 任务失败（Key 无效、模型不存在、音频格式错误等）
                "task-failed" -> {
                    val errMsg = header.optString("error_message", "识别任务失败")
                    isRunning = false
                    stopRecording()
                    mainHandler.post { onError(errMsg) }
                }

                // 正常结束，无需处理
                "task-finished" -> { }
            }
        } catch (_: Exception) { }
    }

    // ── 音频录制 ──────────────────────────────────────────────────────────────

    private fun startRecording() {
        val minBuf = AudioRecord.getMinBufferSize(
            AsrConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = maxOf(minBuf, AsrConfig.FRAME_BYTES * 4)  // 至少 4 帧缓冲

        @Suppress("MissingPermission")
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,  // 比 MIC 更适合 ASR
            AsrConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            mainHandler.post { onError("麦克风初始化失败，请检查 RECORD_AUDIO 权限") }
            isRunning = false
            return
        }

        audioRecord!!.startRecording()

        // 独立录音线程，持续推送 PCM 帧到 WebSocket
        recordingThread = Thread {
            val frame = ByteArray(AsrConfig.FRAME_BYTES)
            while (isRunning && !Thread.currentThread().isInterrupted) {
                val read = audioRecord?.read(frame, 0, frame.size) ?: break
                if (read > 0 && taskStarted) {
                    val bytes = if (read < frame.size) frame.copyOf(read) else frame
                    webSocket?.send(bytes.toByteString())   // 以二进制帧发送 PCM
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

    private fun sendFinishTask() {
        try { webSocket?.send(buildFinishTaskJson()) } catch (_: Exception) { }
    }

    // ── JSON 报文构建 ─────────────────────────────────────────────────────────

    private fun buildRunTaskJson() = JSONObject().apply {
        put("header", JSONObject().apply {
            put("action", "run-task")
            put("task_id", taskId)
            put("streaming", "duplex")
        })
        put("payload", JSONObject().apply {
            put("task_group", "audio")
            put("task", "asr")
            put("function", "recognition")
            put("model", AsrConfig.MODEL)
            put("parameters", JSONObject().apply {
                put("format", "pcm")
                put("sample_rate", AsrConfig.SAMPLE_RATE)
                put("language_hints", JSONArray(languageHints))
                put("punctuation_prediction_enabled", true)
                put("disfluency_removal_enabled", true)       // 去除"嗯""啊"等填充词
                put("inverse_text_normalization_enabled", true) // 数字/日期规范化
            })
            put("input", JSONObject())
        })
    }.toString()

    private fun buildFinishTaskJson() = JSONObject().apply {
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
```

### 5.6 在 Activity 中使用

```kotlin
class YourActivity : BaseMirrorActivity<YourBinding>() {

    private var speechEngine: SpeechEngine? = null
    private var isListening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 申请麦克风权限
        if (hasMicPermission()) {
            initSpeechEngine()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQ_MIC
            )
        }

        // 2. 注册镜腿手势
        collectTempleActions()
    }

    private fun initSpeechEngine() {
        speechEngine = SpeechEngine(
            context = this,
            onPartial = { text ->
                // 实时中间结果：展示给用户看，但不做业务处理
                mBindingPair.updateView { tvStatus.text = "$text…" }
            },
            onFinal = { text ->
                // 最终结果：展示并触发下游处理（翻译、搜索等）
                mBindingPair.updateView { tvStatus.text = text }
                doSomethingWith(text)
            },
            onError = { msg ->
                isListening = false
                mBindingPair.updateView { tvStatus.text = "错误: $msg" }
            }
        )
    }

    private fun collectTempleActions() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                templeActionViewModel.state.collect { action ->
                    when (action) {
                        is TempleAction.Click -> toggleListening()
                        is TempleAction.DoubleClick -> finish()
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun toggleListening() {
        isListening = !isListening
        if (isListening) {
            // language_hints 决定识别语言，可根据 UI 选择动态传入
            speechEngine?.start(listOf("en"))
            FToast.show("开始监听")
        } else {
            speechEngine?.stop()
            FToast.show("已暂停")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 必须调用 release()，否则 OkHttpClient 线程池不会退出
        speechEngine?.release()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            initSpeechEngine()
        }
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    companion object {
        private const val REQ_MIC = 100
    }
}
```

---

## 6. 支持的语言及 language_hints 对照表

`language_hints` 传入 ISO 639-1 语言代码即可：

| 语言 | `language_hints` 值 |
|------|---------------------|
| 英语 | `["en"]` |
| 普通话（中文） | `["zh"]` |
| 日语 | `["ja"]` |
| 韩语 | `["ko"]` |
| 法语 | `["fr"]` |
| 德语 | `["de"]` |
| 西班牙语 | `["es"]` |
| 俄语 | `["ru"]` |
| 多语言混合 | `["zh", "en"]`（最多两个） |

`paraformer-realtime-v2` 对中英混合支持尤为出色，可以自动识别句子中夹杂的英文词汇，无需切换语言。

---

## 7. 常见问题排查

### 问题一：onError 收到 "DashScope API Key 未配置"

**原因**：`AsrConfig.DASHSCOPE_API_KEY` 为空字符串。

**解决**：到 DashScope 控制台申请 Key 并填入（中国大陆：[dashscope.console.aliyun.com](https://dashscope.console.aliyun.com/)；海外：[bailian.console.alibabacloud.com](https://bailian.console.alibabacloud.com/)）。

---

### 问题二：onError 收到 "连接失败"

可能原因：
1. **网络不通**：眼镜是否连接了 Wi-Fi？检查 `adb shell ping dashscope.aliyuncs.com`（海外用户 ping `dashscope-intl.aliyuncs.com`）
2. **API Key 无效**：Key 填写错误或已过期
3. **Key 与端点不匹配**：国际版 Key 须搭配国际版端点 `wss://dashscope-intl.aliyuncs.com/...`，反之亦然
4. **账户欠费**：DashScope 免费额度用完且未充值

---

### 问题三：连接成功但一直没有识别结果

**排查步骤**：
1. 确认 `task-started` 事件已收到（即 `startRecording()` 被调用）
2. 检查 `AudioRecord.STATE_INITIALIZED`，若初始化失败则无音频推送
3. 用 `adb logcat` 过滤看是否有异常：
   ```bash
   adb logcat -s "AudioRecord" -s "OkHttp"
   ```
4. 确认 RECORD_AUDIO 权限在运行时已授予（设置 → 应用 → 权限）

---

### 问题四：识别结果乱码或语言不对

**原因**：`language_hints` 传入了错误的语言代码，或与实际说话语言不符。

**解决**：检查传入的语言代码是否正确（参见第 6 节对照表）。

---

### 问题五：停止后再次 start() 无效

**原因**：`stop()` 内部将 `isRunning` 置为 `false`，`start()` 有幂等守卫 `if (isRunning) return`，但在 `stop()` 返回前 `isRunning` 已为 `false`，所以应该正常。

**实际可能的原因**：上次 `stop()` 时 WebSocket 关闭尚未完成，新的 `connectWebSocket()` 被旧连接占用。

**解决**：在 `stop()` 后等待一个事件循环再 `start()`：
```kotlin
speechEngine?.stop()
Handler(Looper.getMainLooper()).postDelayed({
    speechEngine?.start(langHints)
}, 300)
```

---

### 问题六：长时间监听后连接断开

**原因**：NAT 超时或服务端主动关闭空闲连接。

**解决**：已在 `OkHttpClient` 中配置 `pingInterval(20, TimeUnit.SECONDS)`，保活 ping 每 20 秒发送一次，通常足够。若仍断开，检查路由器/防火墙的 WebSocket 超时配置。

---

## 8. 资源释放说明

| 时机 | 操作 |
|------|------|
| 用户暂停监听 | `engine.stop()` — 释放麦克风、关闭 WebSocket |
| `Activity.onDestroy()` | `engine.release()` — 同上 + 关闭 OkHttpClient 线程池 |
| 切换语言后重启 | `engine.stop()` → 延迟 300ms → `engine.start(newHints)` |

> **重要**：如果在 `onDestroy()` 中只调用 `stop()` 而不调用 `release()`，OkHttpClient 持有的后台线程池不会退出，进程可能无法正常终止。

---

## 9. 与翻译接口的集成示例

语音识别通常配合翻译接口使用。推荐在 `onFinal` 回调中触发翻译，在 `onPartial` 回调中只展示实时文字：

```kotlin
speechEngine = SpeechEngine(
    context = this,
    onPartial = { text ->
        // 只显示，不翻译（节省 API 调用次数）
        setOriginalText("$text…")
    },
    onFinal = { text ->
        setOriginalText(text)
        // 翻译在 IO 线程执行，结果回到主线程更新 UI
        lifecycleScope.launch(Dispatchers.IO) {
            val translated = TranslationManager.translate(text, fromLangCode)
            withContext(Dispatchers.Main) {
                setTranslatedText(translated)
            }
        }
    },
    onError = { msg ->
        setStatus("ASR 错误: $msg")
    }
)
```

翻译接口配置详见 [`translation/TranslationConfig.kt`](../06-recipes/)。

---

## 10. 下一步

- [`05-hardware-apis/01-camera.md`](./01-camera.md)：Camera2 双屏预览
- [`05-hardware-apis/02-imu-sensors.md`](./02-imu-sensors.md)：IMU 传感器与头部追踪
- [`06-recipes/`](../06-recipes/)：完整应用示例（含语音翻译 App）
