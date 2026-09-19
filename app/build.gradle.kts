import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

// ── 语音识别（腾讯云实时 ASR）配置 ──────────────────────────────────────────
// 一律从 local.properties 读 —— 那个文件已在 .gitignore 里，凭据不会进 git。
// 也支持 gradle.properties / -P 同名属性，但**别把 secretKey 写进 gradle.properties**，
// 它是入库的。
//
// 全部留空 = 两条路（后端签发 / 端上自签）都没开 → App 回落到系统
// SpeechRecognizer，行为跟改动前一致。
val asrLocalProps = Properties().apply {
    rootProject.file("local.properties")
        .takeIf { it.isFile }
        ?.inputStream()
        ?.use { load(it) }
}

fun asrCfg(key: String): String =
    (asrLocalProps.getProperty(key) ?: providers.gradleProperty(key).getOrNull() ?: "").trim()

fun asrBool(key: String): Boolean = asrCfg(key).equals("true", ignoreCase = true)

/** 转义成 Kotlin 字符串字面量，避免凭据里的引号/反斜杠搞坏 buildConfigField。 */
fun asrLiteral(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

// 端上自签三件套（腾讯云 CAM 密钥）
val asrAppId = asrCfg("asrAppId")
val asrSecretId = asrCfg("asrSecretId")
val asrSecretKey = asrCfg("asrSecretKey")
// 引擎模型，留空 = 16k_zh
val asrEngine = asrCfg("asrEngine")
// true = 走后端签发（secretKey 只在服务端），需要实例侧实现 /api/voice/asr-token
val asrSignViaBackend = asrBool("asrSignViaBackend")

// ── WorkBuddy 直连（复用 WorkBuddy 自己的登录态，不占用腾讯云账号）─────────────
// 打开 asrUseWorkBuddy=true 后，App 会连 copilot.tencent.com/clientcap/v2/asr/stream，
// 用 Bearer <accessToken> 鉴权。凭据取自
//   ~/Library/Application Support/CodeBuddyExtension/Data/Public/auth/workbuddy-desktop.info
// 的 auth.accessToken / auth.refreshToken / account.uid。
// ⚠️ accessToken 约 3 天、refreshToken 约 7 天到期。想长期可用请用 asrSignViaBackend。
val asrUseWorkBuddy = asrBool("asrUseWorkBuddy")
val asrWbAccessToken = asrCfg("asrWbAccessToken")
val asrWbRefreshToken = asrCfg("asrWbRefreshToken")
val asrWbUid = asrCfg("asrWbUid")
val asrWbEndpoint = asrCfg("asrWbEndpoint")

android {
    namespace = "io.github.hotmanxp.lanagent"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.hotmanxp.lanagent"
        minSdk = 26
        targetSdk = 34
        versionCode = 57
        versionName = "0.16.2"

        // 语音识别凭据 / 开关。见文件头注释；空值 = 未配置，走系统 SpeechRecognizer。
        buildConfigField("String", "ASR_APP_ID", asrLiteral(asrAppId))
        buildConfigField("String", "ASR_SECRET_ID", asrLiteral(asrSecretId))
        buildConfigField("String", "ASR_SECRET_KEY", asrLiteral(asrSecretKey))
        buildConfigField("String", "ASR_ENGINE", asrLiteral(asrEngine))
        buildConfigField("Boolean", "ASR_SIGN_VIA_BACKEND", asrSignViaBackend.toString())

        // WorkBuddy 直连模式
        buildConfigField("Boolean", "ASR_USE_WORKBUDDY", asrUseWorkBuddy.toString())
        buildConfigField("String", "ASR_WB_ACCESS_TOKEN", asrLiteral(asrWbAccessToken))
        buildConfigField("String", "ASR_WB_REFRESH_TOKEN", asrLiteral(asrWbRefreshToken))
        buildConfigField("String", "ASR_WB_UID", asrLiteral(asrWbUid))
        buildConfigField("String", "ASR_WB_ENDPOINT", asrLiteral(asrWbEndpoint))
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false  // spec §2.2: 不写 release
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        // voice/VoiceAsrConfig.kt 通过 BuildConfig 读凭据，必须打开。
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.okhttp)
    implementation(libs.jsch)

    testImplementation(kotlin("test"))
    // 真实 org.json 实现，覆盖 Android stub —— voice/WorkBuddyApi 解析用。
    testImplementation(libs.org.json)
}
