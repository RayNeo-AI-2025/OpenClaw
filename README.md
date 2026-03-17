# RayClaw

RayClaw 是一款专为 [RayNeo X3 Pro](https://www.rayneo.com/) AR 眼镜打造的智能语音 AI 助手。
它将实时语音识别（阿里云 DashScope Paraformer）、流式大模型对话（OpenClaw 智能体网关）、Markdown 渲染和多语言翻译集成在一起，全程通过镜腿触控手势驱动，双手彻底解放。

本仓库同时附带一份完整的 [RayNeo X3 AR 应用开发指引](rayneo-x3-ar-dev-guide/README.md)（25 篇文章），供开发者搭建自己的 AR 应用参考。

---

## 目录

- [功能特性](#功能特性)
- [手势操作](#手势操作)
- [技术架构](#技术架构)
- [环境要求](#环境要求)
- [OpenClaw 智能体网关部署](#openclaw-智能体网关部署)
- [下载与安装（免编译）](#下载与安装免编译)
- [从源码构建（开发者）](#从源码构建开发者)
- [配置详解](#配置详解)
- [项目结构](#项目结构)
- [开发指引](#开发指引rayneo-x3-ar-dev-guide)
- [依赖说明](#依赖说明)
- [License](#license)

---

## 功能特性

| 功能 | 说明 |
|------|------|
| 实时语音识别 | 接入阿里云 DashScope Paraformer，WebSocket 全双工流式推送，延迟极低 |
| 流式 AI 对话 | 通过 OpenClaw 智能体网关（SSE 协议）与 LLM 实时交互，逐字渲染 |
| Markdown 渲染 | AI 回复支持标题、加粗、代码块、表格等完整 Markdown 格式（CommonMark + GFM 表格） |
| 多语言 ASR | 支持中文、英语、日语、韩语、法语、德语、西班牙语、俄语共 8 种语言 |
| 多翻译引擎 | 内置 6 种提供商：MyMemory / 百度 / 有道 / 腾讯 / DeepL / Azure |
| 双眼同步渲染 | 继承 Mercury SDK `BaseMirrorActivity`，左右眼画面完全同步 |
| 全手势操控 | 7 种镜腿手势覆盖全部交互，无需手机 |
| 眼镜内设置 | 三击镜腿打开设置页，可切换识别语言、监听模式、重置对话 |
| 运行时配置 | 无需重编译，通过 `adb push` 覆盖 API Key 和服务器地址 |

---

## 手势操作

| 手势 | 主界面动作 | 设置页动作 |
|------|-----------|-----------|
| 单击 | 开始 / 暂停语音监听 | 切换当前选项值 |
| 双击 | 退出应用 | 保存设置并返回 |
| 三击 | 打开设置页 | 取消并返回 |
| 向前滑动 | AI 回复向上滚动 | 切换当前选项值 |
| 向后滑动 | AI 回复向下滚动 | 切换当前选项值 |
| 向上滑动 | AI 回复向上滚动 | 切换上一行 |
| 向下滑动 | AI 回复向下滚动 | 切换下一行 |

> 注意： 长按（LongClick）未使用——会触发系统设置面板。双指点击因镜腿触控面积有限不可用。

---

## 技术架构

```
┌────────────────────────────────────────────────────────┐
│                    RayNeo X3 Pro 眼镜                    │
│                                                        │
│  ┌──────────────┐   ┌──────────────┐   ┌────────────┐  │
│  │ SpeechEngine │   │ OpenClawClient│   │ Markdown   │  │
│  │  (WebSocket) │   │   (HTTP SSE) │   │ Renderer   │  │
│  └──────┬───────┘   └──────┬───────┘   └─────┬──────┘  │
│         │                  │                 │         │
│  ┌──────┴──────────────────┴─────────────────┴──────┐  │
│  │           AgentChatActivity                       │  │
│  │     BaseMirrorActivity（双屏同步渲染）             │  │
│  │     TempleAction（镜腿触控手势）                   │  │
│  └───────────────────────────────────────────────────┘  │
│                                                        │
│          蓝牙连接手机 → 共享手机网络                      │
└──────────────────────┬─────────────────────────────────┘
                       │ 互联网
          ┌────────────┴────────────┐
          │                         │
   ┌──────┴──────┐          ┌──────┴──────┐
   │  DashScope  │          │  OpenClaw   │
   │  Paraformer │          │  智能体网关  │
   │  (语音识别)  │          │  (LLM 对话) │
   └─────────────┘          └─────────────┘
```

### 双通道网络通信

| 通道 | 协议 | 用途 |
|------|------|------|
| 语音识别 | WebSocket 全双工 | 实时推送 PCM 音频（16kHz/16bit/单声道），接收识别结果 |
| AI 对话 | HTTP SSE 流式 | 发送文本到 OpenClaw `/v1/responses`，逐字接收 AI 回复 |

### 关键设计决策

| 设计 | 说明 |
|------|------|
| Generation 计数器 | 每次重置对话递增 `generation`，所有流式回调捕获启动时的值，过期回调静默丢弃，彻底避免 race condition |
| 渲染节流 | Markdown 渲染有 120ms 节流（`STREAM_RENDER_THROTTLE_MS`），避免频繁 WebView load 阻塞 UI |
| 密钥分级 | `rayclaw.conf`（运行时）> `AppSettings`（用户设置）> `BuildConfig`（编译时）> 代码默认值 |
| HTML 安全 | WebView 禁用 JavaScript 和 DOM 存储；`MarkdownRenderer` 在 CommonMark 解析前 sanitize 输入，防止 XSS |

---

## 环境要求

### 硬件

| 设备 | 说明 |
|------|------|
| RayNeo X3 Pro 眼镜 | 主运行设备（或其他基于 Mercury SDK 的兼容设备） |
| 手机（蓝牙配对） | 眼镜通过蓝牙连接手机共享网络，支持局域网或公网访问 |
| 电脑 | 安装 ADB，用于推送 APK 和配置文件 |

### 外部服务

| 服务 | 用途 | 获取方式 |
|------|------|----------|
| 阿里云 DashScope | Paraformer 实时语音识别 | [控制台申请](https://dashscope.console.aliyun.com/) |
| OpenClaw 智能体网关 | LLM 流式对话 | 自行部署（见下方） |

### 编译环境（仅从源码构建时需要）

| 工具 | 版本 |
|------|------|
| Android Studio | Meerkat（2024.3.1）以上 |
| JDK | 17（Android Studio 内置 JBR） |
| Gradle | 8.3+（Wrapper 自动下载） |
| compileSdk | 36（Android 16） |
| minSdk | 26（Android 8.0） |

---

## OpenClaw 智能体网关部署

RayClaw 依赖 OpenClaw 智能体网关提供 LLM 流式对话能力。

### 安装 OpenClaw

```bash
# 卸载旧版本（如有）
npm uninstall -g openclaw

# 安装稳定版本（最新版本在部分模型的 function call 上存在问题）
npm install -g openclaw@2026.3.2
```

### 初始化配置并注册服务

```bash
openclaw onboard --install-daemon
```

在初始化过程中，以下选项不能使用默认值：

| 配置项 | 推荐值 | 说明 |
|--------|--------|------|
| Gateway bind | `LAN` 或 `tailnet` | LAN 模式支持局域网访问；tailnet 需额外配置 Tailscale |
| Gateway auth | `Token` | 使用 Token 认证保护 API 安全 |
| Gateway token | 自生成强密钥 | 建议：`openssl rand -hex 32` |

其他配置项根据个人需求选择。

### 修改配置文件

部署完成后编辑 `~/.openclaw/openclaw.json`：

1. 确认 `gateway.bind` 设置为 `lan` 或 `tailnet`
2. 新增 HTTP 端点配置：

```json
"http": {
  "endpoints": {
    "chatCompletions": { "enabled": true },
    "responses": { "enabled": true }
  }
}
```

### 验证部署

```bash
openclaw restart
openclaw status
```

---

## 下载与安装（免编译）

不想搭建开发环境？直接下载预编译的 APK 即可。

> 说明： RayNeo X3 Pro 没有内置应用商店，所有第三方应用均需通过 ADB sideload 安装。

### 第一步：下载 APK

前往本项目的 [GitHub Releases](https://github.com/lunanightshade-z/rayclaw/releases) 页面，下载最新版本的 `app-release.apk`。

### 第二步：在眼镜上开启开发者模式

RayNeo X3 Pro 的开发者模式开启方式与普通 Android 手机不同：

1. 在眼镜上打开 设置（Settings）
2. 在设置界面中，用右镜腿触控板向左滑动 10 次
3. 完成后开发者模式即被激活

> 再次执行相同操作（向左滑动 10 次）会关闭开发者模式。请确保使用 USB-C 数据线而非纯充电线。

### 第三步：在电脑上安装 ADB

ADB（Android Debug Bridge）是 Android 官方命令行工具，无需安装完整 Android Studio。

<details>
<summary><b>Windows</b></summary>

从 [Android 官方下载页](https://developer.android.com/tools/releases/platform-tools) 下载 Windows 版 Platform Tools 压缩包，解压到任意目录（如 `C:\platform-tools`）。

解压后在该目录中打开 PowerShell，即可运行 `.\adb` 命令；或将该目录添加到系统环境变量 `PATH` 后全局使用 `adb`。

</details>

<details>
<summary><b>macOS</b></summary>

```bash
brew install android-platform-tools
```

</details>

<details>
<summary><b>Linux</b></summary>

```bash
# Ubuntu / Debian
sudo apt install android-tools-adb

# Fedora / RHEL
sudo dnf install android-tools
```

</details>

### 第四步：连接眼镜并安装 APK

```bash
# 验证连接（应出现设备序列号，状态为 device）
adb devices

# 安装 APK
adb install app-release.apk
```

<details>
<summary><b>连接故障排查</b></summary>

| 问题 | 解决方案 |
|------|----------|
| 列表为空 | 确认使用数据线而非充电线；检查开发者模式是否已开启；重启眼镜 |
| 状态 `unauthorized` | 检查眼镜屏幕是否有待确认的"允许 USB 调试"弹窗 |
| Windows 11 无法识别 | 已知驱动兼容问题——使用 [Zadig](https://zadig.akeo.ie/) 为眼镜安装 WinUSB 驱动后重试 |

</details>

### 第五步：创建并推送配置文件

在电脑上新建 `rayclaw.conf` 文件：

```properties
# 阿里云 DashScope — 语音识别
DASHSCOPE_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx

# OpenClaw 智能体网关
OPENCLAW_BASE_URL=http://your-server-address:18789
OPENCLAW_GATEWAY_TOKEN=your_gateway_token_here
OPENCLAW_AGENT_ID=main
```

| 字段 | 说明 |
|------|------|
| `DASHSCOPE_API_KEY` | 阿里云 DashScope 控制台获取 |
| `OPENCLAW_BASE_URL` | OpenClaw 网关地址（局域网 IP 或公网域名均可） |
| `OPENCLAW_GATEWAY_TOKEN` | 部署 OpenClaw 时设置的 Gateway Token |
| `OPENCLAW_AGENT_ID` | 保持默认值 `main` 即可 |

> 网络说明： 眼镜通过蓝牙连接手机共享网络，只要手机能访问 OpenClaw 服务器即可（局域网或公网均可）。

推送配置到眼镜：

```bash
adb push rayclaw.conf /sdcard/Android/data/com.rayclaw.app/files/rayclaw.conf

或者

"$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" push rayclaw.conf /sdcard/Android/data/com.rayclaw.app/files/rayclaw.conf
```

### 第六步：启动应用

```bash
adb shell am force-stop com.rayclaw.app
adb shell am start -n com.rayclaw.app/.AgentChatActivity
```

或直接在眼镜 Launcher 中找到 rayclaw 应用打开。单击镜腿开始说话，AI 回复将实时渲染在眼前。

### 更新配置

如需更换 API Key 或切换服务器，修改本地的 `rayclaw.conf`，重新执行第五、六步即可，无需重新安装。

---

## 从源码构建（开发者）

### 1. 克隆仓库

```bash
git clone https://github.com/lunanightshade-z/rayclaw.git
cd rayclaw
```

### 2. 配置 API Keys

```bash
cp local.properties.template local.properties
```

编辑 `local.properties`（已在 `.gitignore` 中，不会被提交）：

```properties
# Android SDK 路径
sdk.dir=/Users/yourname/Library/Android/sdk      # macOS
# sdk.dir=C:\Users\yourname\AppData\Local\Android\Sdk  # Windows

# 阿里云 DashScope — 语音识别
DASHSCOPE_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx

# OpenClaw 智能体网关
OPENCLAW_BASE_URL=http://your-server-address:18789
OPENCLAW_GATEWAY_TOKEN=your_gateway_token_here
OPENCLAW_AGENT_ID=main
```

### 3. 构建并安装

方式 A：构建脚本（Windows PowerShell）

```powershell
.\build_and_install.ps1
```

脚本自动定位 `adb`（优先读取 `ANDROID_HOME` / `ANDROID_SDK_ROOT`，回退到 PATH）。

方式 B：手动命令

```bash
# Debug 版本
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Release 版本（需在 local.properties 配置签名信息）
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

### 4. 运行时配置（可选）

无需重新编译即可更换 API Key 或切换服务器：

```bash
cp rayclaw.conf.template rayclaw.conf
# 编辑 rayclaw.conf 填入实际参数
adb push rayclaw.conf /sdcard/Android/data/com.rayclaw.app/files/rayclaw.conf
adb shell am force-stop com.rayclaw.app
adb shell am start -n com.rayclaw.app/.AgentChatActivity
```

---

## 配置详解

### 配置优先级（从高到低）

```
rayclaw.conf（运行时 adb push）
        ↓
AppSettings（眼镜内设置页修改）
        ↓
BuildConfig（local.properties 编译时注入）
        ↓
代码默认值
```

### local.properties（编译时注入）

| Key | 说明 | 必填 |
|-----|------|------|
| `sdk.dir` | Android SDK 本地路径 | 是 |
| `DASHSCOPE_API_KEY` | 阿里云 DashScope API Key | 是 |
| `OPENCLAW_BASE_URL` | OpenClaw 网关地址 | 是 |
| `OPENCLAW_GATEWAY_TOKEN` | 网关鉴权 Token | 是 |
| `OPENCLAW_AGENT_ID` | Agent ID（默认 `main`） | 否 |
| `KEYSTORE_FILE` | Release 签名密钥库文件名 | Release 构建时 |
| `KEYSTORE_PASSWORD` | 密钥库密码 | Release 构建时 |
| `KEY_ALIAS` | 密钥别名 | Release 构建时 |
| `KEY_PASSWORD` | 密钥密码 | Release 构建时 |

### rayclaw.conf（运行时覆盖）

格式为 Java Properties，支持的 Key：

```properties
DASHSCOPE_API_KEY=sk-xxx
OPENCLAW_BASE_URL=http://192.168.1.100:18789
OPENCLAW_GATEWAY_TOKEN=new_token
OPENCLAW_AGENT_ID=main
ASR_LANGUAGE=zh          # zh/en/ja/ko/fr/de/es/ru
ASR_LISTEN_MODE=continuous  # continuous（持续监听）/ oneshot（单次识别）
```

运行时配置优先级高于 BuildConfig，适合切换测试环境。日志安全：`RuntimeConfig` 只记录已加载的 key 名称，不记录 key 值。

---

## 项目结构

```
rayclaw/
├── app/
│   ├── build.gradle.kts                    构建配置、API Key 注入、签名
│   ├── libs/
│   │   └── MercuryAndroidSDK-*.aar         RayNeo Mercury SDK（本地 AAR）
│   └── src/main/
│       ├── AndroidManifest.xml             权限（录音、网络）+ Activity 注册
│       ├── assets/
│       │   └── markdown.css                AI 回复 WebView 样式
│       └── java/com/rayclaw/app/
│           ├── AgentChatActivity.kt        主界面：手势 + 语音 + 流式对话 + 渲染
│           ├── SettingsActivity.kt         眼镜内设置：语言 / 监听模式 / 重置
│           ├── MyApplication.kt            Application：Mercury SDK 初始化
│           ├── AppSettings.kt              SharedPreferences 持久化
│           ├── RuntimeConfig.kt            运行时配置覆盖系统
│           ├── agent/
│           │   ├── AgentConfig.kt          OpenClaw 网关参数（优先级链）
│           │   └── OpenClawClient.kt       HTTP SSE 流式客户端
│           ├── asr/
│           │   ├── SpeechEngine.kt         WebSocket 全双工语音引擎
│           │   ├── AsrConfig.kt            ASR 参数：16kHz / 3200B 帧 / 模型
│           │   └── AppLanguage.kt          8 种语言枚举
│           ├── translation/
│           │   ├── TranslationManager.kt   工厂（策略模式）：按配置选择引擎
│           │   ├── TranslationConfig.kt    各翻译商 Key 配置
│           │   ├── TranslationProvider.kt  翻译接口
│           │   └── providers/              MyMemory·百度·有道·腾讯·DeepL·Azure
│           └── ui/
│               └── MarkdownRenderer.kt     CommonMark + GFM 表格 → HTML
│
├── rayneo-x3-ar-dev-guide/                 AR 开发完整指引（见下方）
├── local.properties.template               编译时 API Key 配置模板
├── rayclaw.conf.template                   运行时配置模板
├── test_openclaw_gateway.py                OpenClaw 网关连通性测试脚本
├── build_and_install.ps1                   一键构建安装脚本（PowerShell）
└── LICENSE                                 MIT License
```

---

## 开发指引：rayneo-x3-ar-dev-guide

本仓库内置完整的 RayNeo X3 AR 应用开发文档，记录了从零搭建 AR 应用的全部经验。

| 章节 | 内容 |
|------|------|
| `01-introduction/` | X3 硬件特性、AR 开发与普通 Android 开发的核心差异 |
| `02-environment-setup/` | 开发环境配置、项目搭建、Mercury SDK 接入、API Key 管理 |
| `03-core-concepts/` | 双屏渲染（Fusion Vision）原理、镜腿输入事件、焦点管理、3D 视差 |
| `04-ui-components/` | BaseMirrorActivity / Fragment / View、FToast / FDialog、RecyclerView 手势导航 |
| `05-hardware-apis/` | Camera2 双眼预览、IMU 头部姿态、设备状态监控、语音识别 |
| `06-recipes/` | 5 个完整 Demo：Hello World · 菜单导航 · 滚动列表 · 视频播放 · Camera AR 叠层 |
| `07-debugging/` | ADB 常用命令、单眼投屏调试、性能分析 |
| `08-faq.md` | 高频问题汇总 |

入口：[rayneo-x3-ar-dev-guide/README.md](rayneo-x3-ar-dev-guide/README.md)

---

## 依赖说明

| 依赖 | 版本 | 用途 |
|------|------|------|
| Mercury Android SDK | 0.2.5 | RayNeo X3 硬件抽象：双眼渲染、镜腿手势 |
| OkHttp | 4.12.0 | HTTP/WebSocket 网络层（SSE 流式请求） |
| commonmark-java | 0.24.0 | Markdown 解析（含 GFM 表格扩展） |
| Kotlin Coroutines | 1.7.3 | 异步并发 |
| AndroidX Lifecycle | 2.6.1 | Lifecycle-aware 协程作用域 |

---

## 网络安全说明

`AndroidManifest.xml` 中启用了 `usesCleartextTraffic="true"`。
原因：OpenClaw 网关地址由用户在运行时自行配置，可能指向本地网络的 HTTP 服务。
若你的部署环境全程使用 HTTPS，可安全移除该标志。

---

## License

[MIT License](LICENSE) © 2026 RayClaw Contributors

> Mercury Android SDK（`app/libs/MercuryAndroidSDK-*.aar`）版权归 RayNeo / FFalcon Technology 所有，以自有许可随本项目分发，仅用于 RayNeo X3 硬件支持。
