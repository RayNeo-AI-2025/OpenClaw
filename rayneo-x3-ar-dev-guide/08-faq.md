# 常见问题（FAQ）

---

## 开发环境类

### Q1: 构建失败，提示 `Minimum supported Gradle version is X.X`

**原因**：Gradle 版本低于 AGP 要求。

**解决**：更新 `gradle/wrapper/gradle-wrapper.properties`：
```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-8.4-bin.zip
```
AGP 与 Gradle 版本对照见 [`02-environment-setup/01-prerequisites.md`](02-environment-setup/01-prerequisites.md)。

---

### Q2: 提示 `Cannot resolve symbol BaseMirrorActivity`

**原因**：AAR 未正确引入或 Gradle Sync 失败。

**解决步骤**：
1. 确认 `MercuryAndroidSDK-*.aar` 在 `app/libs/` 目录下
2. 确认 `build.gradle` 中有 `implementation(fileTree("libs"))`
3. 执行 `File → Sync Project with Gradle Files`
4. 如果还不行，尝试 `Build → Clean Project` 后重新 Build

---

### Q3: 编译报错 `minSdkVersion XX is lower than version 26`

**原因**：项目的 `minSdkVersion` 低于 Mercury SDK 要求的 26。

**解决**：在 `app/build.gradle` 的 `defaultConfig` 中设置：
```groovy
minSdk 26
```

---

### Q4: 报错 `Cannot add extension with name 'kotlin'`，是不是 Kotlin 插件冲突？

**是，通常是重复应用插件导致的。**

**现象**：在 AGP 9.x 工程中，添加 `id("org.jetbrains.kotlin.android")` 后构建失败，提示同名扩展 `kotlin` 已存在。  

**处理顺序（推荐按这个顺序做）**：
1. 检查 root `build.gradle(.kts)` 和 `app/build.gradle(.kts)`，不要重复声明 Kotlin Android 插件。
2. 如果仍报错，先移除显式 Kotlin 插件声明，只保留 Android 插件，先把工程构建跑通。
3. 执行 `Sync` + `assembleDebug` 验证。

---

### Q5: `build.gradle.kts` 里写了 `kotlinOptions`，却提示 `Unresolved reference`

**原因**：当前插件/DSL 组合下，该扩展没有可见；直接写会导致脚本编译失败。  

**建议做法**：
1. 先删除或注释 `kotlinOptions {}`，避免卡死在脚本编译阶段。
2. 仅保留 `compileOptions`（例如 Java 17）保证构建可继续。
3. 待工程可构建后，再按当前 Kotlin 插件版本补充正确的 Kotlin 编译配置。

---

### Q6: `checkDebugAarMetadata` 失败，提示依赖要求更高 `compileSdk`

**现象**：报错类似  
- `androidx.core:core(-ktx):1.17.0 requires compileSdk 36`  
- `androidx.recyclerview:1.4.0 requires compileSdk 35+`

**原因**：文档示例的 `compileSdk 34` 与你项目实际依赖版本不匹配。  

**解决**：
1. 提升 `compileSdk` 到依赖要求版本（推荐 `36`）。
2. `targetSdk` 可按业务策略保留（例如 `32`），两者不必强制相同。
3. 重新构建确认通过。

---

## 双屏显示类

### Q7: 在手机/电脑显示器上看到两份并排的 UI，这是 bug 吗？

**不是 bug**。这是正常现象。未佩戴眼镜时，你看到的是完整的逻辑屏幕（左屏 + 右屏并排）。佩戴 X3 眼镜后，两块物理屏幕各显示一份，大脑双目融合后看到的是单一清晰的画面。

---

### Q8: 应用在眼镜上画面有撕裂感

**原因**：没有正确使用合目组件，直接将 UI 居中显示导致左右眼各看到半边。

**解决**：继承 `BaseMirrorActivity<B>`，通过 `mBindingPair.updateView {}` 更新 UI，让相同内容出现在左右两个区域。参见 [`03-core-concepts/01-dual-screen-rendering.md`](03-core-concepts/01-dual-screen-rendering.md)。

---

### Q9: 应用图标不显示在眼镜 Launcher 中

**原因**：`AndroidManifest.xml` 中缺少 meta-data 标签。

**解决**：在 `<application>` 节点下添加：
```xml
<meta-data
    android:name="com.rayneo.mercury.app"
    android:value="true" />
```

---

### Q10: 如何让页面背景透明，看到现实世界？

将背景色设为纯黑 `#000000`（或 `@android:color/black`）：
```xml
<!-- 布局根节点 -->
<ConstraintLayout android:background="#000000" ...>

<!-- 主题中 -->
<item name="android:windowBackground">@android:color/black</item>
```
黑色在 AR 眼镜中显示为完全透明，用户可以看到真实世界。

---

## 交互与手势类

### Q11: 镜腿单击没有反应

**检查清单**：
1. Activity 是否继承自 `BaseMirrorActivity`（或其父类）？
2. 是否在 `lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.RESUMED) { ... } }` 中收集 `templeActionViewModel.state`？
3. Activity 是否实际处于 RESUMED 状态（未被其他窗口覆盖）？
4. 如果使用了焦点系统，`focusObj.reqFocus()` 是否已调用？

---

### Q12: `BaseEventActivity` 的手势回调方法无法 override？

**原因**：`BaseEventActivity` 将 `onClick()`、`onDoubleClick()` 等方法声明为 `final`，这是有意设计的——SDK 需要在这些方法中统一将手势映射为 TempleAction。

**正确做法**：不要 override 这些方法，改为收集 `templeActionViewModel.state` Flow：
```kotlin
templeActionViewModel.state.collect { action ->
    when (action) {
        is TempleAction.Click -> handleClick()
        // ...
    }
}
```

---

### Q13: `updateView {}` 中的代码为什么执行了两次？

`updateView {}` 的设计就是这样：它会对左屏和右屏各执行一次，以实现双屏同步更新。这是正常行为，不是 bug。

如果你不希望某段逻辑执行两次，有两个选择：
1. 将该逻辑放到 `setLeft {}` 中（只执行一次）
2. 在 `updateView {}` 内部用 `checkIsLeft(this)` 判断当前是左还是右，只在一侧执行

---

### Q14: 前滑和后滑的方向搞反了

**原因**：用户可能在系统设置中开启了"非自然模式"。

**说明**：X3 有两种滑动模式：
- 自然模式（默认）：向前滑（朝镜片方向）= `SlideBackward`
- 非自然模式：向前滑（朝镜片方向）= `SlideForward`

**建议**：不要依赖物理方向，而是依赖手势语义。`SlideForward` = "向前导航"，`SlideBackward` = "向后导航"，让用户在系统设置中选择他们习惯的物理映射。

---

## 性能与资源类

### Q15: 应用耗电量很高

**常见原因**：
- IMU 传感器在后台继续运行（忘记在 `onPause` 中注销）
- 相机在 `onPause` 后未关闭
- `MirroringView` 未在 `onDestroy` 中调用 `stopMirroring()`

**最佳实践**：
```kotlin
override fun onPause() {
    super.onPause()
    sensorManager.unregisterListener(this)  // IMU
    cameraCaptureSession?.stopRepeating()    // Camera
}

override fun onDestroy() {
    super.onDestroy()
    mirrorView.stopMirroring()   // MirroringView
    cameraDevice?.close()        // Camera
    mediaPlayer?.release()       // MediaPlayer
}
```

---

### Q16: 列表滚动不流畅（跟手效果有延迟）

**原因**：`observeOriginMotionEventStream` 的坐标转换出现了性能问题，或者 `MotionEvent` 对象未被正确回收。

**解决**：在每次使用完 `MotionEvent.obtain()` 创建的对象后调用 `recycle()`：
```kotlin
slidingTracker.observeOriginMotionEventStream(motionEventDispatcher) { event ->
    val newEvent = MotionEvent.obtain(...)
    // SDK 会自动处理新 event 的分发和回收
    newEvent  // 返回新事件即可
}
```

---

## 调试工具类

### Q17: 如何在 PC 屏幕上只看到单眼的画面？

使用 scrcpy：
```bash
# 先查询实际分辨率
adb shell wm size

# 假设输出 Physical size: 2560x720，单眼宽 1280
scrcpy --crop 1280:720:0:0
```

详见 [`07-debugging/02-single-eye-preview.md`](07-debugging/02-single-eye-preview.md)。

---

### Q18: 如何强制眼镜不进入休眠？

**方式 1**（ADB，临时）：
```bash
adb shell settings put global deep_suspend_disabled_persist 1
```

**方式 2**（代码，调试模式）：
```kotlin
if (BuildConfig.DEBUG) {
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}
```

**记得在发布版本或开发结束后恢复默认。**

---

### Q19: PAG 动效如何在眼镜上显示？

SDK 支持 PAG 动效的合目显示。参考 SDK Sample 中的 `PAGActivity`，PAG 文件放在 `assets/` 目录下，通过 PAG 相关 API 加载并渲染到合目组件中。

---

### Q20: 如何支持 X2 和 X3 的共同版本？

```kotlin
import com.ffalcon.mercury.android.sdk.util.DeviceUtil

if (DeviceUtil.isX3Device()) {
    // X3 专有：上滑、下滑、双指手势
} else {
    // X2 兼容模式
}
```

X3 新增的手势（`SlideUpwards`、`SlideDownwards`、`DoubleFingerClick` 等）在 X2 上不会触发，代码本身不会崩溃，只是功能不可用。

---

### Q21: 混淆后应用崩溃

**解决**：在 `proguard-rules.pro` 中添加：
```proguard
# Mercury SDK
-keep class com.ffalcon.mercury.** { *; }

# ViewBinding
-keep class * implements androidx.viewbinding.ViewBinding { *; }

# Kotlin 协程
-keepnames class kotlinx.coroutines.** { *; }
```

注意：使用 `implementation(fileTree("libs"))` 引入 AAR 时，AAR 内置的 `proguard.txt` 会被 Gradle 自动合并，通常不需要手动添加 SDK 的混淆规则。如果仍崩溃，检查是否是其他类被误混淆。

---

## 语音识别类

### Q22: 使用 `SpeechRecognizer` 调用语音识别，点击后界面没有任何反应

**原因**：RayNeo X3 上没有标准的 Android ASR（语音识别）引擎。设备安装的 `com.rayneo.aispeech` 是**唤醒词检测服务**（只识别 "Hi RayNeo" 等固定词），不是通用 ASR 引擎。`SpeechRecognizer` 找不到对应的 `RecognitionService`，因此静默失败。

**验证方法**：
```kotlin
// 返回 false 即可确认设备无 ASR 引擎
SpeechRecognizer.isRecognitionAvailable(context)
```

**正确方案**：使用 `AudioRecord` 直接采集麦克风 PCM 音频，通过 WebSocket 流式上传到云端 ASR API。

完整实现见 [`05-hardware-apis/04-speech-recognition.md`](05-hardware-apis/04-speech-recognition.md)。

---

### Q23: 选择云端 ASR 时，推荐哪个服务？

推荐 **阿里云 DashScope `paraformer-realtime-v2`**：

| 对比项 | DashScope | 科大讯飞 | Azure |
|--------|-----------|---------|-------|
| 认证复杂度 | 低（Bearer Token） | 高（HMAC 签名） | 中 |
| 流式识别 | ✅ WebSocket 双工 | ✅（限 60s/次） | ✅ |
| 多语言 | 中英日韩法德西俄 | 主要中英 | 多语言 |
| 会话限制 | 无 | 60 秒 | 无 |

申请地址：
- 中国大陆：https://dashscope.console.aliyun.com/ → API-KEY 管理
- 海外用户：https://bailian.console.alibabacloud.com/ → API-KEY 管理（无需中国手机号，API Key 与端点须使用国际版）

---

### Q24: 使用 AudioRecord 时应该选哪个音频源（AudioSource）？

使用 `MediaRecorder.AudioSource.VOICE_RECOGNITION`，不要用 `MIC`。

```kotlin
AudioRecord(
    MediaRecorder.AudioSource.VOICE_RECOGNITION,  // ✅ 优化用于语音识别
    16000,
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT,
    bufferSize
)
```

`VOICE_RECOGNITION` 与 `MIC` 的区别：前者会应用针对语音识别优化的音频处理（如自动增益控制的特定调校），识别准确率更高。

---

### Q25: 云端 ASR 的 `onFinal` 和 `onPartial` 有什么区别，应该分别用来做什么？

| 回调 | 触发时机 | 推荐用途 |
|------|---------|---------|
| `onPartial` | 用户说话过程中，实时输出中间结果 | 更新 UI 展示"正在说的话" |
| `onFinal` | 一句话说完（检测到停顿），输出最终确认文本 | 触发翻译、搜索等下游处理 |

只在 `onFinal` 里触发翻译，避免对每个字符都发送翻译请求浪费 API 配额。
