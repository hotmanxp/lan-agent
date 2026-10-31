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

// ── Agents-Anywhere server 默认配置(同 asrWbAccessToken 一套 local.properties 模式) ──
// 真机调试 Agents-Anywhere 时把这两个写进 `local.properties`(已 gitignore):
//   agentsAnywhereBaseUrl=http://192.168.x.x:8000
//   agentsAnywhereToken=<server 登录拿到的 accessToken>
// UI 里手动填值优先级更高 —— 留空 = 默认未配置,屏上提示用户填。
val agentsAnywhereBaseUrl = asrCfg("agentsAnywhereBaseUrl")
val agentsAnywhereToken = asrCfg("agentsAnywhereToken")

android {
    namespace = "io.github.hotmanxp.lanagent"
    // 34 → 35(0.24.2 移植 AA 官方客户端):coil 3.3 / sora editor / termux
    // terminal-view 这些依赖要求 compileSdk ≥ 35。compileSdk 只决定「能编译
    // 哪些 API」,不改运行时行为 —— targetSdk 仍保持 34,系统行为不变。
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.hotmanxp.lanagent"
        minSdk = 26
        targetSdk = 34
        versionCode = 137
        versionName = "0.29.0"

        // ── 本地引擎(feat/local-engine)的原生库 ──────────────────────────────
        // libnode.so 是 Node 24 运行时,arm64-v8a 下单个 82MB;加上
        // libc++_shared.so 9MB,一个 APK 就 ~100MB。**APK 里只会有 arm64-v8a** ——
        // 别的 ABI 加进来 APK 直接翻倍,而现在本来就没打算支持 32 位设备
        // (Node 官方的 bionic 预编译也只出了 arm64)。
        //
        // .so 不入库:用 tools/fetch-native.sh 拉到 app/src/main/jniLibs/arm64-v8a/
        // (AGP 默认就吃这个目录,不用额外配 sourceSets)。没拉过就当没有本地
        // 引擎,App 照常当局域网客户端用 —— 引擎是可选能力,不是硬依赖。
        //
        // **ABI 白名单在下面的 packaging.jniLibs**:依赖树里带着 armeabi-v7a /
        // x86 / x86_64 的 .so(termux terminal-view、barhopper、oniguruma 等),
        // 不过滤的话 APK 里会有 4 份 .so,而 Node 的 bionic 预编译只有 arm64-v8a
        // 一份 —— 那些设备上 System.loadLibrary("node") 直接 UnsatisfiedLinkError
        // 崩掉,不是降级而是崩。
        //
        // 为什么用 packaging.jniLibs.excludes 而不是 `defaultConfig { ndk { abiFilters } }`:
        // abiFilters 只管**我们自己**编译出来的 .so,依赖树带进来的预编译 .so
        // (termux terminal-view / barhopper / onigurama 这些)不在它的作用域内 ——
        // 而恰恰是这些多 ABI 的 .so 把 APK 撑到 4 份 ABI。packaging 在**打包层**,
        // 对所有来源一视同仁。
        //
        // (注:`ndk { abiFilters }` 这个块本身是可用的,得写在 defaultConfig 里;
        //  写在 android { } 顶层会报 "Unresolved reference: ndk"。
        //  跟构建机装没装 NDK 无关 —— 我们只用预编译 .so,不本地编译。)

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

        // Agents-Anywhere server 默认值(local.properties 兜底,UI 优先级更高)
        buildConfigField("String", "AGENTS_ANYWHERE_BASE_URL", asrLiteral(agentsAnywhereBaseUrl))
        buildConfigField("String", "AGENTS_ANYWHERE_TOKEN", asrLiteral(agentsAnywhereToken))
        // 移植进来的 AA 官方客户端用这个字段当官方 server 兜底(原 OFFICIAL_SERVER_URL)。
        // 默认值对齐 Agents-Anywhere/android 的 officialServerUrl —— 之前接的是
        // local.properties 里的 agentsAnywhereBaseUrl(默认空),导致 AA 登录页
        // 不知道官方地址,连「Sign in to Agents Anywhere Cloud」都点不动。
        buildConfigField("String", "AA_SERVER_URL", asrLiteral(agentsAnywhereBaseUrl.ifBlank { "https://web.agents-anywhere.com" }))
    }

    // ── 只保留 arm64-v8a 的原生库 ──────────────────────────────────────
    // 依赖带进来的 armeabi-v7a / x86 / x86_64 三个 ABI 一共 ~17MB,而且
    // **那些架构上没有 libnode.so** —— 装了引擎的设备只要不是 arm64,
    // 一 System.loadLibrary("node") 就 UnsatisfiedLinkError 崩掉。
    // 排掉它们:非 arm64 设备上引擎功能自动不存在,App 退回纯局域网客户端。
    packaging {
        jniLibs {
            excludes += setOf(
                "**/armeabi-v7a/**",
                "**/x86/**",
                "**/x86_64/**",
            )
            // **必须解到磁盘**(legacy packaging = extractNativeLibs=true)。
            //
            // 默认的 modern packaging 把 .so 压缩在 APK 里、**不落到
            // nativeLibraryDir** —— 于是:
            //   1. EngineInstaller.isInstalled() 查 File(nativeDir,"libnode.so")
            //      永远 false → 引擎入口整个不出现;
            //   2. EngineShims 建 rg 软链指向 nativeLibraryDir/librg.so,
            //      那个文件不存在 → Grep/Glob 工具全废。
            //
            // JNI 加载(System.loadLibrary)两种模式都能用,但引擎需要
            // **磁盘上真实存在的 .so 文件**做软链目标,所以走 legacy。
            useLegacyPackaging = true
        }
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
    // ── AA 官方客户端(0.24.2 移植)所需的依赖 ──
    // 终端(terminal-view)与代码编辑器(sora)看着重,但 AA 的
    // SessionDetailScreen 入口就注入了 terminalPool,删不掉;
    // 编译通过后会回头按实际引用再剪一轮。
    implementation(platform(libs.sora.bom))
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.compose.shimmer)
    implementation(libs.telephoto.zoomable.image.coil3)
    implementation(libs.sora.editor)
    implementation(libs.sora.language.textmate)
    implementation(libs.sora.oniguruma.native)
    implementation(libs.androidx.camera.mlkit.vision)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.foundation)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // 0.21.0:全项目图标已换成 Lucide 线性图标。material-icons-extended 暂时
    // 保留(下个版本 grep 确认零残留再删),避免编译失败时多一个排查维度。
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.lucide.icons)
    // commonmark-java:Markdown 解析(替掉原自研 MarkdownParser)。
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.autolink)
    implementation(libs.commonmark.ext.task.list.items)
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
    implementation(libs.highlights)
    // Agents-Anywhere accessToken / mobile-login refreshToken 用 Keystore 主密钥
    // 包出来的 EncryptedSharedPreferences(见 data/SecureTokenStore.kt)。**只**
    // 加密这两类敏感凭据 —— baseUrl / clientId / BuildConfig 兜底走普通 DataStore。
    implementation(libs.androidx.security.crypto)
    // QR 生成(mobile-login dialog 用);core 只做编码,不需要 journeyapps 的
    // 扫码封装。
    implementation(libs.zxing.core)

    testImplementation(kotlin("test"))
    // 真实 org.json 实现，覆盖 Android stub —— voice/WorkBuddyApi 解析用。
    testImplementation(libs.org.json)
}
