# API Key 安全管理

AR 应用通常需要接入多个云端服务（语音识别、翻译、视觉识别等），每个服务都有对应的 API Key。如何安全地管理这些凭证，让它们既在本机可用，又永远不会出现在 Git 历史中，是每个开发者必须掌握的基础技能。

---

## 1. 为什么不能把 Key 写在代码里

```kotlin
// ❌ 错误做法：Key 直接写在源码
object Config {
    const val API_KEY = "sk-a319046d1c4247ab885536437e42c2e8"
}
```

**风险**：
- 一旦推送到 GitHub/GitLab，Key 永久留在 Git 历史中，`git log` 或 `git blame` 均可找到
- 即便后续删除该行并重新提交，历史记录中的 Key 仍然可被检索
- 公开仓库泄露后，恶意用户可立即盗用你的账户额度
- 私有仓库也有风险：内部人员离职、仓库误设为公开等

**正确思路**：Key 存放在**不进版本库的本地文件**中，构建时由 Gradle 读取并注入到代码里，源文件中只引用一个符号名，不出现任何明文。

---

## 2. Android 标准方案：`local.properties` + `BuildConfig`

Android 项目在创建时会自动生成 `local.properties`，并且默认在 `.gitignore` 中排除它。这是存放本地凭证的官方推荐位置。

### 整体流程

```
local.properties（gitignored，只在本机）
       │
       ▼  build.gradle.kts 在构建期读取
BuildConfig.YOUR_KEY（编译期注入，每次构建自动生成）
       │
       ▼  代码中通过符号名引用
YourConfig.kt
```

### 第一步：确认 `local.properties` 已被排除

检查项目根目录的 `.gitignore`，确认包含以下内容（Android Studio 新建项目时会自动生成）：

```gitignore
local.properties
```

如果没有，手动添加这一行。

### 第二步：在 `local.properties` 中写入 Key

```properties
# local.properties
# 此文件不进版本库，在本机保存你的个人凭证

sdk.dir=C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk

# ── API Keys ──────────────────────────────────
DASHSCOPE_API_KEY=sk-xxxxxxxxxxxxxxxxxxxx
BAIDU_APP_ID=your_baidu_app_id
BAIDU_SECRET_KEY=your_baidu_secret_key
```

> **注意**：`local.properties` 使用 Java Properties 格式，Windows 路径中的反斜杠需要转义（`\\`），但普通字符串（API Key）直接写即可，不需要转义。

#### 关于 `sdk.dir` 配置项

**作用说明**：
- `sdk.dir` 指向你本机 Android SDK 的安装路径
- Gradle 构建系统需要这个路径来定位：
  - `platform-tools/adb.exe`（用于安装 APK 到设备）
  - `build-tools/`（编译工具，如 `aapt`、`dx`、`zipalign`）
  - `platforms/android-XX/`（Android API 版本）
  - `sources/android-XX/`（源码）
- 如果路径配置错误，构建时会报错：`SDK location not found` 或 `Failed to find Build Tools`

**新手如何找到这个路径**：

**方法一：通过 Android Studio（推荐）**
1. 打开 Android Studio
2. 菜单栏：`File` → `Settings`（Windows/Linux）或 `Android Studio` → `Preferences`（macOS）
3. 左侧导航：`Appearance & Behavior` → `System Settings` → `Android SDK`
4. 在页面顶部的 **"Android SDK Location"** 字段中，你会看到完整路径
   - Windows 默认路径：`C:\Users\<你的用户名>\AppData\Local\Android\Sdk`
   - macOS 默认路径：`~/Library/Android/sdk`
   - Linux 默认路径：`~/Android/Sdk`

**方法二：通过命令行（Windows PowerShell）**
```powershell
# 检查环境变量（如果已设置）
$env:ANDROID_HOME

# 或者检查默认安装位置
Test-Path "$env:LOCALAPPDATA\Android\Sdk"
# 如果返回 True，路径就是：$env:LOCALAPPDATA\Android\Sdk
```

**方法三：检查 Android Studio 自动生成的配置**
- Android Studio 首次打开项目时，会自动检测并生成 `local.properties` 文件
- 如果文件已存在且 `sdk.dir` 已配置，通常不需要修改
- 如果文件不存在或路径错误，Android Studio 会在 Sync 时提示你配置

**常见路径示例**：
- Windows：`C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk`
- macOS：`/Users/YourName/Library/Android/sdk`
- Linux：`/home/YourName/Android/Sdk`

**验证路径是否正确**：
配置后，检查路径下是否存在以下目录：
- `platform-tools/`（包含 `adb.exe`）
- `build-tools/`
- `platforms/`

如果这些目录都存在，说明路径配置正确。

### 第三步：`build.gradle.kts` 读取并注入

```kotlin
// app/build.gradle.kts

import java.util.Properties

// 读取 local.properties
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    // ...

    defaultConfig {
        // ...

        // 将每个 Key 注入为 BuildConfig 字段
        // 第一个参数：Java 类型（"String" / "int" / "boolean"）
        // 第二个参数：字段名（在代码中用 BuildConfig.字段名 访问）
        // 第三个参数：字段值（字符串必须加引号，因为这里是 Java 源码字面量）
        buildConfigField("String", "DASHSCOPE_API_KEY",
            "\"${localProps.getProperty("DASHSCOPE_API_KEY", "")}\"")

        buildConfigField("String", "BAIDU_APP_ID",
            "\"${localProps.getProperty("BAIDU_APP_ID", "")}\"")

        buildConfigField("String", "BAIDU_SECRET_KEY",
            "\"${localProps.getProperty("BAIDU_SECRET_KEY", "")}\"")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true   // ← 必须启用，否则 BuildConfig 类不会生成
    }
}
```

**关于默认值**：`getProperty("KEY", "")` 的第二个参数是 Key 不存在时的默认值。填 `""` 可以保证在 CI/CD 环境（没有 `local.properties`）下编译不报错，但运行时会因为 Key 为空而产生 API 错误——这是预期行为，说明需要配置凭证。

### 第四步：在代码中通过 BuildConfig 访问

```kotlin
// asr/AsrConfig.kt
import com.example.yourapp.BuildConfig

object AsrConfig {
    // 通过 BuildConfig 访问，源码中无明文 Key
    val DASHSCOPE_API_KEY: String get() = BuildConfig.DASHSCOPE_API_KEY

    const val MODEL = "paraformer-realtime-v2"
    // ...
}
```

```kotlin
// translation/TranslationConfig.kt
import com.example.yourapp.BuildConfig

object TranslationConfig {
    object Baidu {
        val APP_ID:     String get() = BuildConfig.BAIDU_APP_ID
        val SECRET_KEY: String get() = BuildConfig.BAIDU_SECRET_KEY
    }
}
```

### 第五步：验证 Key 是否成功注入

构建后，`BuildConfig` 类会生成在：

```
app/build/generated/source/buildConfig/debug/
    com/example/yourapp/BuildConfig.java
```

打开这个文件，可以看到：

```java
public final class BuildConfig {
    public static final String DASHSCOPE_API_KEY = "sk-xxxxxxxxxxxx";
    public static final String BAIDU_APP_ID = "your_id";
    // ...
}
```

> **重要**：这个生成文件不进版本库（在 `build/` 目录下，已被 `.gitignore` 排除），但编译出的 APK 中包含这些值。APK 是二进制文件，Key 可以通过反编译提取，但这需要物理接触设备，风险远低于 Git 仓库泄露。

---

## 3. 团队协作工作流

多人团队中，每位开发者都有自己的 Key，或者共享同一组 Key 但不进代码库。推荐流程：

### 提供模板文件

在仓库中添加一个 `local.properties.example`（**注意：这个文件进版本库**）：

```properties
# local.properties.example
# 复制此文件为 local.properties，填入真实凭证后即可构建。
# local.properties 已被 .gitignore 排除，不会提交到版本库。

sdk.dir=/path/to/your/Android/Sdk

# DashScope 语音识别 API Key
# 申请（中国大陆）：https://dashscope.console.aliyun.com/ → API-KEY 管理
# 申请（海外）：https://bailian.console.alibabacloud.com/ → API-KEY 管理
DASHSCOPE_API_KEY=

# 百度翻译（可选）
# 申请：https://fanyi-api.baidu.com/
BAIDU_APP_ID=
BAIDU_SECRET_KEY=
```

新成员 clone 仓库后：

```bash
cp local.properties.example local.properties
# 编辑 local.properties，填入自己的 Key
```

### 在 README 中说明

确保项目 `README.md` 的"快速开始"章节明确告知：

```markdown
## 快速开始

### 3. 配置 API Keys

将 `local.properties.example` 复制为 `local.properties`，
填入对应的 API Key（向团队负责人获取，或自行申请）。
此文件不会提交到版本库。
```

---

## 4. CI/CD 环境（GitHub Actions / Jenkins 等）

CI 服务器上没有 `local.properties`，需要通过环境变量传入 Key。

### 方案：在 `build.gradle.kts` 中同时支持两种来源

```kotlin
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

// 辅助函数：优先读 local.properties，其次读系统环境变量
fun getProp(key: String): String =
    localProps.getProperty(key)          // 本地开发：从 local.properties 读
        ?: System.getenv(key)            // CI：从环境变量读
        ?: ""                            // 都没有：返回空字符串

android {
    defaultConfig {
        buildConfigField("String", "DASHSCOPE_API_KEY",
            "\"${getProp("DASHSCOPE_API_KEY")}\"")
    }
}
```

### 在 GitHub Actions 中配置 Secret

1. 仓库页面 → **Settings → Secrets and variables → Actions → New repository secret**
2. 添加：`DASHSCOPE_API_KEY`，值填入真实 Key
3. 在 workflow 文件中注入：

```yaml
# .github/workflows/build.yml
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Build APK
        env:
          DASHSCOPE_API_KEY: ${{ secrets.DASHSCOPE_API_KEY }}
        run: ./gradlew assembleDebug
```

---

## 5. 如果 Key 已经被意外提交

如果已经把 Key 推送到远程仓库，**第一步是立即在 API 控制台撤销/重新生成该 Key**，撤销动作比清理历史更重要——历史清理往往无法覆盖所有已拉取副本。

清理 Git 历史（可选，针对私有仓库）：

```bash
# 使用 git filter-repo 删除历史中的敏感内容
pip install git-filter-repo
git filter-repo --path-glob '*.kt' --replace-text <(echo 'sk-a319046d1c4247ab885536437e42c2e8==>REDACTED')

# 强制推送覆盖远程历史（需要管理员权限）
git push --force --all
```

> **注意**：强制重写历史会影响所有协作者，他们需要重新 clone 或执行 `git fetch --all && git reset --hard origin/main`。建议在仓库管理员的指导下操作。

---

## 6. 本项目的配置示例

本项目（RayNeo X3 AR 翻译应用）已按上述方案配置完毕，可以直接参考：

**`local.properties`**（不进版本库，每人本地维护）：
```properties
sdk.dir=C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk

DASHSCOPE_API_KEY=sk-xxxxxxxxxxxxxxxxxxxx
```

**`app/build.gradle.kts`**（关键片段）：
```kotlin
import java.util.Properties

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    defaultConfig {
        buildConfigField("String", "DASHSCOPE_API_KEY",
            "\"${localProps.getProperty("DASHSCOPE_API_KEY", "")}\"")
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}
```

**`asr/AsrConfig.kt`**（代码中无明文 Key）：
```kotlin
import com.example.xrappinit.BuildConfig

object AsrConfig {
    val DASHSCOPE_API_KEY: String get() = BuildConfig.DASHSCOPE_API_KEY
    // ...
}
```

如需添加翻译引擎的 Key，在 `local.properties` 增加对应条目，在 `build.gradle.kts` 增加 `buildConfigField`，在 `TranslationConfig.kt` 改为 `BuildConfig.XXX` 引用，三步完成迁移。

---

## 下一步

- [`03-sdk-integration.md`](./03-sdk-integration.md)：SDK 接入与初始化
- [`../05-hardware-apis/04-speech-recognition.md`](../05-hardware-apis/04-speech-recognition.md)：语音识别（使用 DashScope API Key 的完整示例）
