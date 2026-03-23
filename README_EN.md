# OpenClaw

**[中文](README.md) | English**

OpenClaw is an intelligent voice AI assistant built for the [RayNeo X3 Pro](https://www.rayneo.com/) AR glasses.
It integrates real-time speech recognition (Alibaba Cloud DashScope Paraformer), streaming LLM conversations (OpenClaw Agent Gateway), Markdown rendering, and multilingual translation — all controlled entirely through temple touchpad gestures, keeping your hands free.

This repository also includes a complete [RayNeo X3 AR Development Guide](rayneo-x3-ar-dev-guide/README.md) (25 articles) for developers building their own AR applications.

---

## Table of Contents

- [Features](#features)
- [Gesture Controls](#gesture-controls)
- [Architecture](#architecture)
- [Requirements](#requirements)
- [OpenClaw Agent Gateway Deployment](#openclaw-agent-gateway-deployment)
- [Download & Install (Pre-built)](#download--install-pre-built)
- [Build from Source (Developers)](#build-from-source-developers)
- [Configuration Reference](#configuration-reference)
- [Project Structure](#project-structure)
- [Development Guide](#development-guide-rayneo-x3-ar-dev-guide)
- [Dependencies](#dependencies)
- [International Users Guide](#international-users-guide)
- [License](#license)

---

## Features

| Feature | Description |
|---------|-------------|
| Real-time Speech Recognition | Alibaba Cloud DashScope Paraformer via full-duplex WebSocket streaming, ultra-low latency |
| Streaming AI Conversations | Real-time LLM interaction through OpenClaw Agent Gateway (SSE protocol), token-by-token rendering |
| Markdown Rendering | AI responses support headings, bold, code blocks, tables and full Markdown (CommonMark + GFM Tables) |
| Multilingual ASR | 8 languages: Chinese, English, Japanese, Korean, French, German, Spanish, Russian |
| Multiple Translation Engines | 6 built-in providers: MyMemory / Baidu / Youdao / Tencent / DeepL / Azure |
| Binocular Sync Rendering | Inherits Mercury SDK `BaseMirrorActivity`, left and right eye displays perfectly synchronized |
| Full Gesture Control | 7 temple gestures cover all interactions, no phone needed |
| On-glasses Settings | Triple-tap temple to open settings: switch recognition language, listen mode, reset conversation |
| Runtime Configuration | No recompilation needed — override API keys and server addresses via `adb push` |

---

## Gesture Controls

| Gesture | Main Screen | Settings Screen |
|---------|-------------|-----------------|
| Single Tap | Start / Pause voice listening | Toggle current option |
| Double Tap | Exit app | Save settings and return |
| Triple Tap | Open settings | Cancel and return |
| Slide Forward | Scroll AI response up | Toggle current option |
| Slide Backward | Scroll AI response down | Toggle current option |
| Slide Up | Scroll AI response up | Switch to previous row |
| Slide Down | Scroll AI response down | Switch to next row |

> Note: Long press is not used — it triggers the system settings panel. Two-finger tap is unavailable due to limited temple touchpad area.

---

## Architecture

```
+--------------------------------------------------------+
|                    RayNeo X3 Pro Glasses                |
|                                                        |
|  +--------------+   +--------------+   +------------+  |
|  | SpeechEngine |   | OpenClawClient|   | Markdown   |  |
|  |  (WebSocket) |   |   (HTTP SSE) |   | Renderer   |  |
|  +------+-------+   +------+-------+   +-----+------+  |
|         |                  |                 |         |
|  +------+------------------+-----------------+------+  |
|  |           AgentChatActivity                       |  |
|  |     BaseMirrorActivity (binocular sync)           |  |
|  |     TempleAction (temple touch gestures)          |  |
|  +---------------------------------------------------+  |
|                                                        |
|          Bluetooth to phone -> shared network          |
+----------------------+---------------------------------+
                       | Internet
          +------------+------------+
          |                         |
   +------+------+          +------+------+
   |  DashScope  |          |  OpenClaw   |
   |  Paraformer |          |    Agent    |
   |    (ASR)    |          |   Gateway   |
   +-------------+          +-------------+
```

### Dual-Channel Network Communication

| Channel | Protocol | Purpose |
|---------|----------|---------|
| Speech Recognition | WebSocket full-duplex | Stream PCM audio (16kHz/16bit/mono) in real-time, receive recognition results |
| AI Conversation | HTTP SSE streaming | Send text to OpenClaw `/v1/responses`, receive AI response token by token |

### Key Design Decisions

| Design | Description |
|--------|-------------|
| Generation Counter | Incremented on each conversation reset; all streaming callbacks capture the value at start, expired callbacks are silently discarded, eliminating race conditions |
| Render Throttling | Markdown rendering has 120ms throttle (`STREAM_RENDER_THROTTLE_MS`), preventing frequent WebView loads from blocking UI |
| Credential Layering | `openclaw.conf` (runtime) > `AppSettings` (user settings) > `BuildConfig` (compile-time) > code defaults |
| HTML Security | WebView disables JavaScript and DOM storage; `MarkdownRenderer` sanitizes input before CommonMark parsing, preventing XSS |

---

## Requirements

### Hardware

| Device | Description |
|--------|-------------|
| RayNeo X3 Pro glasses | Primary runtime device (or other Mercury SDK compatible devices) |
| Phone (Bluetooth paired) | Glasses share network via Bluetooth to phone; supports LAN or public internet access |
| Computer | Install ADB for pushing APK and config files |

### External Services

| Service | Purpose | How to Get |
|---------|---------|------------|
| Alibaba Cloud DashScope | Paraformer real-time speech recognition | China: [Console](https://dashscope.console.aliyun.com/); International: [Console](https://bailian.console.alibabacloud.com/) |
| OpenClaw Agent Gateway | LLM streaming conversations | Self-deploy (see below) |

> **International users**: API keys from the China site (aliyun.com) and the international site (alibabacloud.com) are **NOT interchangeable**. Choose the platform corresponding to your region. International registration does not require a Chinese phone number. See [International Users Guide](#international-users-guide).

### Build Environment (only needed when building from source)

| Tool | Version |
|------|---------|
| Android Studio | Meerkat (2024.3.1) or above |
| JDK | 17 (Android Studio built-in JBR) |
| Gradle | 8.3+ (Wrapper auto-downloads) |
| compileSdk | 36 (Android 16) |
| minSdk | 26 (Android 8.0) |

---

## OpenClaw Agent Gateway Deployment

OpenClaw depends on the OpenClaw Agent Gateway for LLM streaming conversations.

### Install OpenClaw

```bash
# Uninstall old version (if any)
npm uninstall -g openclaw

# Install stable version (latest version has issues with function calls on some models)
npm install -g openclaw@2026.3.2
```

### Initialize Configuration and Register Service

```bash
openclaw onboard --install-daemon
```

During initialization, the following options must NOT use default values:

| Option | Recommended Value | Description |
|--------|-------------------|-------------|
| Gateway bind | `LAN` or `tailnet` | LAN mode enables local network access; tailnet requires additional Tailscale setup |
| Gateway auth | `Token` | Use Token authentication to secure the API |
| Gateway token | Self-generated strong key | Recommended: `openssl rand -hex 32` |

Configure other options according to your needs.

### Edit Configuration File

After deployment, edit `~/.openclaw/openclaw.json`:

1. Confirm `gateway.bind` is set to `lan` or `tailnet`
2. Add HTTP endpoint configuration:

```json
"http": {
  "endpoints": {
    "chatCompletions": { "enabled": true },
    "responses": { "enabled": true }
  }
}
```

### Verify Deployment

```bash
openclaw restart
openclaw status
```

---

## Download & Install (Pre-built)

Don't want to set up a development environment? Just download the pre-built APK.

> Note: RayNeo X3 Pro has no built-in app store. All third-party apps must be installed via ADB sideload.

### Step 1: Download APK

Go to the [GitHub Releases](https://github.com/RayNeo-AI-2025/OpenClaw/releases) page and download the latest `app-release.apk`.

### Step 2: Enable Developer Mode on the Glasses

Enabling developer mode on RayNeo X3 Pro differs from regular Android phones:

1. Open Settings on the glasses
2. In the settings screen, slide left 10 times on the right temple touchpad
3. Developer mode is now activated

> Performing the same action again (slide left 10 times) will disable developer mode. Make sure to use a USB-C data cable, not a charging-only cable.

### Step 3: Install ADB on Your Computer

ADB (Android Debug Bridge) is an official Android command-line tool. You don't need to install full Android Studio.

<details>
<summary><b>Windows</b></summary>

Download the Windows Platform Tools zip from the [Android official download page](https://developer.android.com/tools/releases/platform-tools), extract to any directory (e.g., `C:\platform-tools`).

Open PowerShell in that directory to run `.\adb` commands, or add the directory to your system `PATH` to use `adb` globally.

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

### Step 4: Connect Glasses and Install APK

```bash
# Verify connection (should show device serial number with "device" status)
adb devices

# Install APK
adb install app-release.apk
```

<details>
<summary><b>Connection Troubleshooting</b></summary>

| Problem | Solution |
|---------|----------|
| Empty list | Confirm using data cable, not charging cable; check developer mode is enabled; restart glasses |
| Status `unauthorized` | Check glasses screen for pending "Allow USB debugging" prompt |
| Windows 11 not recognizing | Known driver compatibility issue — use [Zadig](https://zadig.akeo.ie/) to install WinUSB driver for the glasses |
| Windows no device list (no Android Studio) | Computers without Android Studio lack Google USB Driver and latest platform-tools. No need to install the full IDE — just: 1. Download the driver from [Google USB Driver](https://developer.android.com/studio/run/win-usb) and install manually in Device Manager; 2. Ensure platform-tools is up to date. Reconnect the device after installing the driver |

</details>

### Step 5: Create and Push Configuration File

Create an `openclaw.conf` file on your computer:

```properties
# Alibaba Cloud DashScope — Speech Recognition
# China users: https://dashscope.console.aliyun.com/
# International users: https://bailian.console.alibabacloud.com/
DASHSCOPE_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx

# International users MUST uncomment and use the international endpoint
# DASHSCOPE_WS_ENDPOINT=wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference

# OpenClaw Agent Gateway
OPENCLAW_BASE_URL=http://your-server-address:18789
OPENCLAW_GATEWAY_TOKEN=your_gateway_token_here
OPENCLAW_AGENT_ID=main
```

| Field | Description |
|-------|-------------|
| `DASHSCOPE_API_KEY` | Get from DashScope console (China and international keys are NOT interchangeable) |
| `DASHSCOPE_WS_ENDPOINT` | International users MUST set to `wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference`; China users can leave blank |
| `OPENCLAW_BASE_URL` | OpenClaw gateway address (LAN IP or public domain both work) |
| `OPENCLAW_GATEWAY_TOKEN` | Gateway Token set during OpenClaw deployment |
| `OPENCLAW_AGENT_ID` | Keep default value `main` |

> Network note: The glasses share network via Bluetooth to your phone. As long as your phone can reach the OpenClaw server (LAN or public internet), the glasses can too.

Push the configuration to the glasses:

```bash
# Method 1: Direct push (Android 10 and below)
adb push openclaw.conf /sdcard/Android/data/com.openclaw.app/files/openclaw.conf

# Method 2: Push via run-as (for Android 11+ Scoped Storage restrictions)
# Use this if Method 1 gives "secure_mkdirs failed: Operation not permitted"
adb push openclaw.conf /data/local/tmp/openclaw.conf
adb shell run-as com.openclaw.app cp /data/local/tmp/openclaw.conf /data/data/com.openclaw.app/files/openclaw.conf
adb shell rm /data/local/tmp/openclaw.conf
```

### Step 6: Launch the App

```bash
adb shell am force-stop com.openclaw.app
adb shell am start -n com.openclaw.app/.AgentChatActivity
```

Or find and open the OpenClaw app in the glasses Launcher. Single-tap the temple to start speaking — AI responses will be rendered in real-time in front of your eyes.

### Update Configuration

To change API keys or switch servers, edit your local `openclaw.conf` and repeat Steps 5 and 6. No reinstallation needed.

---

## Build from Source (Developers)

### 1. Clone the Repository

```bash
git clone https://github.com/lunanightshade-z/OpenClaw.git
cd openclaw
```

### 2. Configure API Keys

```bash
cp local.properties.template local.properties
```

Edit `local.properties` (already in `.gitignore`, won't be committed):

```properties
# Android SDK path
sdk.dir=/Users/yourname/Library/Android/sdk      # macOS
# sdk.dir=C:\Users\yourname\AppData\Local\Android\Sdk  # Windows

# Alibaba Cloud DashScope — Speech Recognition
# China users: https://dashscope.console.aliyun.com/
# International users: https://bailian.console.alibabacloud.com/
DASHSCOPE_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx

# International users MUST uncomment and use the international endpoint
# DASHSCOPE_WS_ENDPOINT=wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference

# OpenClaw Agent Gateway
OPENCLAW_BASE_URL=http://your-server-address:18789
OPENCLAW_GATEWAY_TOKEN=your_gateway_token_here
OPENCLAW_AGENT_ID=main
```

### 3. Build and Install

Option A: Build script (Windows PowerShell)

```powershell
.\build_and_install.ps1
```

The script auto-locates `adb` (checks `ANDROID_HOME` / `ANDROID_SDK_ROOT` first, falls back to PATH).

Option B: Manual commands

```bash
# Debug build
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Release build (requires signing config in local.properties)
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

### 4. Runtime Configuration (Optional)

Change API keys or switch servers without recompiling:

```bash
cp openclaw.conf.template openclaw.conf
# Edit openclaw.conf with actual values

# Push config (use run-as method if direct push gives "Operation not permitted")
adb push openclaw.conf /data/local/tmp/openclaw.conf
adb shell run-as com.openclaw.app cp /data/local/tmp/openclaw.conf /data/data/com.openclaw.app/files/openclaw.conf
adb shell rm /data/local/tmp/openclaw.conf

adb shell am force-stop com.openclaw.app
adb shell am start -n com.openclaw.app/.AgentChatActivity
```

---

## Configuration Reference

### Configuration Priority (highest to lowest)

```
openclaw.conf (runtime, adb push)
        |
AppSettings (on-glasses settings page)
        |
BuildConfig (local.properties, compile-time injection)
        |
Code defaults
```

### local.properties (compile-time injection)

| Key | Description | Required |
|-----|-------------|----------|
| `sdk.dir` | Local Android SDK path | Yes |
| `DASHSCOPE_API_KEY` | Alibaba Cloud DashScope API Key | Yes |
| `DASHSCOPE_WS_ENDPOINT` | DashScope WebSocket endpoint (international users must use international endpoint) | No |
| `OPENCLAW_BASE_URL` | OpenClaw gateway address | Yes |
| `OPENCLAW_GATEWAY_TOKEN` | Gateway auth token | Yes |
| `OPENCLAW_AGENT_ID` | Agent ID (default: `main`) | No |
| `KEYSTORE_FILE` | Release signing keystore filename | Release builds |
| `KEYSTORE_PASSWORD` | Keystore password | Release builds |
| `KEY_ALIAS` | Key alias | Release builds |
| `KEY_PASSWORD` | Key password | Release builds |

### openclaw.conf (runtime override)

Format: Java Properties. Supported keys:

```properties
DASHSCOPE_API_KEY=sk-xxx
DASHSCOPE_WS_ENDPOINT=wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference  # Required for international users
OPENCLAW_BASE_URL=http://192.168.1.100:18789
OPENCLAW_GATEWAY_TOKEN=new_token
OPENCLAW_AGENT_ID=main
ASR_LANGUAGE=zh          # zh/en/ja/ko/fr/de/es/ru
ASR_LISTEN_MODE=continuous  # continuous / oneshot
```

Runtime config takes priority over BuildConfig, suitable for switching test environments. Security: `RuntimeConfig` only logs loaded key names, not values.

---

## Project Structure

```
openclaw/
├── app/
│   ├── build.gradle.kts                    Build config, API key injection, signing
│   ├── libs/
│   │   └── MercuryAndroidSDK-*.aar         RayNeo Mercury SDK (local AAR)
│   └── src/main/
│       ├── AndroidManifest.xml             Permissions (recording, network) + Activity registration
│       ├── assets/
│       │   └── markdown.css                AI response WebView styling
│       └── java/com/openclaw/app/
│           ├── AgentChatActivity.kt        Main UI: gestures + voice + streaming + rendering
│           ├── SettingsActivity.kt         On-glasses settings: language / listen mode / reset
│           ├── MyApplication.kt            Application: Mercury SDK initialization
│           ├── AppSettings.kt              SharedPreferences persistence
│           ├── RuntimeConfig.kt            Runtime config override system
│           ├── agent/
│           │   ├── AgentConfig.kt          OpenClaw gateway params (priority chain)
│           │   └── OpenClawClient.kt       HTTP SSE streaming client
│           ├── asr/
│           │   ├── SpeechEngine.kt         WebSocket full-duplex speech engine
│           │   ├── AsrConfig.kt            ASR params: 16kHz / 3200B frames / model
│           │   └── AppLanguage.kt          8-language enum
│           ├── translation/
│           │   ├── TranslationManager.kt   Factory (strategy pattern): selects engine by config
│           │   ├── TranslationConfig.kt    Translation provider key config
│           │   ├── TranslationProvider.kt  Translation interface
│           │   └── providers/              MyMemory·Baidu·Youdao·Tencent·DeepL·Azure
│           └── ui/
│               └── MarkdownRenderer.kt     CommonMark + GFM Tables -> HTML
│
├── rayneo-x3-ar-dev-guide/                 Complete AR development guide (see below)
├── local.properties.template               Compile-time API key config template
├── openclaw.conf.template                   Runtime config template
├── test_openclaw_gateway.py                OpenClaw gateway connectivity test script
├── build_and_install.ps1                   One-click build & install script (PowerShell)
└── LICENSE                                 MIT License
```

---

## Development Guide: rayneo-x3-ar-dev-guide

This repository includes a complete RayNeo X3 AR application development guide documenting the full experience of building AR apps from scratch.

| Chapter | Content |
|---------|---------|
| `01-introduction/` | X3 hardware specs, key differences between AR and standard Android development |
| `02-environment-setup/` | Dev environment setup, project scaffolding, Mercury SDK integration, API key management |
| `03-core-concepts/` | Dual-screen rendering (Fusion Vision), temple input events, focus management, 3D parallax |
| `04-ui-components/` | BaseMirrorActivity / Fragment / View, FToast / FDialog, RecyclerView gesture navigation |
| `05-hardware-apis/` | Camera2 binocular preview, IMU head tracking, device status monitoring, speech recognition |
| `06-recipes/` | 5 complete demos: Hello World · Menu Navigation · Scrolling List · Video Playback · Camera AR Overlay |
| `07-debugging/` | ADB commands, single-eye screen mirroring, performance profiling |
| `08-faq.md` | Frequently asked questions |

Entry point: [rayneo-x3-ar-dev-guide/README.md](rayneo-x3-ar-dev-guide/README.md)

---

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| Mercury Android SDK | 0.2.5 | RayNeo X3 hardware abstraction: binocular rendering, temple gestures |
| OkHttp | 4.12.0 | HTTP/WebSocket networking (SSE streaming) |
| commonmark-java | 0.24.0 | Markdown parsing (with GFM tables extension) |
| Kotlin Coroutines | 1.7.3 | Async concurrency |
| AndroidX Lifecycle | 2.6.1 | Lifecycle-aware coroutine scopes |

---

## International Users Guide

OpenClaw uses Alibaba Cloud DashScope for speech recognition. DashScope is available in both a China edition and an international edition. Their API keys and endpoints are **NOT interchangeable**.

### Comparison

| Item | China Edition | International Edition |
|------|---------------|----------------------|
| Registration | [aliyun.com](https://www.aliyun.com/) (requires Chinese phone number) | [alibabacloud.com](https://www.alibabacloud.com/) (international phone or email) |
| Product Name | DashScope / Bailian | Model Studio (DashScope merged into Model Studio) |
| Console | [dashscope.console.aliyun.com](https://dashscope.console.aliyun.com/) | [bailian.console.alibabacloud.com](https://bailian.console.alibabacloud.com/) |
| WebSocket Endpoint | `wss://dashscope.aliyuncs.com/api-ws/v1/inference` | `wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference` |
| Billing Currency | CNY | USD |

### Configuration Steps for International Users

1. Register an account at [alibabacloud.com](https://www.alibabacloud.com/) (no Chinese phone number required)
2. Go to [Model Studio Console](https://bailian.console.alibabacloud.com/) -> API-KEY Management, create an API Key
3. Configure in `openclaw.conf` (or `local.properties`):

```properties
DASHSCOPE_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
DASHSCOPE_WS_ENDPOINT=wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference
```

> **Important**: International API keys MUST be used with the international endpoint, and vice versa. Mixing them will cause authentication failures.

---

## Network Security Note

`AndroidManifest.xml` has `usesCleartextTraffic="true"` enabled.
Reason: The OpenClaw gateway address is user-configured at runtime and may point to local network HTTP services.
If your deployment uses HTTPS exclusively, you can safely remove this flag.

---

## License

[MIT License](LICENSE) (c) 2026 OpenClaw Contributors

> Mercury Android SDK (`app/libs/MercuryAndroidSDK-*.aar`) is copyrighted by RayNeo / FFalcon Technology, distributed with this project under its own license, solely for RayNeo X3 hardware support.
