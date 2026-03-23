import java.util.Properties

// 读取 local.properties（不进版本库，存放个人凭证）
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.openclaw.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(localProps.getProperty("KEYSTORE_FILE", "openclaw-release.keystore"))
            storePassword = localProps.getProperty("KEYSTORE_PASSWORD", "")
            keyAlias = localProps.getProperty("KEY_ALIAS", "openclaw")
            keyPassword = localProps.getProperty("KEY_PASSWORD", "")
        }
    }

    defaultConfig {
        applicationId = "com.openclaw.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 将 local.properties 中的 Key 注入 BuildConfig
        // 访问方式：BuildConfig.DASHSCOPE_API_KEY
        buildConfigField("String", "DASHSCOPE_API_KEY",
            "\"${localProps.getProperty("DASHSCOPE_API_KEY", "")}\"")
        // OpenClaw 智能体网关配置（从 local.properties 读取）
        buildConfigField("String", "OPENCLAW_GATEWAY_TOKEN",
            "\"${localProps.getProperty("OPENCLAW_GATEWAY_TOKEN", "")}\"")
        buildConfigField("String", "OPENCLAW_BASE_URL",
            "\"${localProps.getProperty("OPENCLAW_BASE_URL", "http://127.0.0.1:18789")}\"")
        buildConfigField("String", "OPENCLAW_AGENT_ID",
            "\"${localProps.getProperty("OPENCLAW_AGENT_ID", "main")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true   // 启用 BuildConfig 生成
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(fileTree("libs"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
