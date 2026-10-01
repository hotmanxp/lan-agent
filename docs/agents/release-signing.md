# lan-agent release 签名配置(待执行 runbook)

> **状态:未执行。** 本文是「哪天要发正式包时照着做一遍」的清单,不是当前生效的配置。
>
> 截至写入时,`app/build.gradle.kts` 的 `buildTypes.release` **没有 `signingConfig`**,
> 因此 `./gradlew :app:assembleRelease` 产出的是 `app-release-unsigned.apk` —— **unsigned APK 装不上手机**。
> 唯一可安装的产物是 debug 包(AGP 自动用 `~/.android/debug.keystore` 签 debug buildType,不用写任何配置)。
>
> 「不写 release」是根 `AGENTS.md` §非目标 的**主动决策**,不是技术做不到;本文只描述补齐签名这一步。

## 为什么现在只有 debug

| 层面 | 原因 |
|------|------|
| 技术 | release buildType 缺 `signingConfig` → unsigned APK → 不可安装 |
| 流程 | 现行分发方式是 `npx serve` + 带时间戳 URL 分享 `app-debug.apk`(见根 `AGENTS.md` 常用命令) |

## 参考:Agents-Anywhere 也不是靠 gradle 出正式包

`/Users/ethan/code/Agents-Anywhere`(`android/app/build.gradle.kts`)的 release buildType **同样没有 signingConfig**,
全仓 grep `signingConfig|storeFile|storePassword|keyAlias` 零命中。证据在它自己的文档里:

- `android/README.md:39-43` —— "The checked-in release build type does not define a signing configuration;
  `assembleRelease` alone does not produce a distributable signed update."
- `docs/releases/2.0.0.md:19,21` —— 「已上传**收到的**发布 APK」「Android APK 由**独立构建后提供**」

它下载页挂的 `agents-anywhere-2.0.0-release.apk` 是**仓外用私有 keystore 签好的成品二进制**。
**别把它当「它能出正式包所以我们也行」的先例** —— 两者 gradle 配置一模一样,差别只在 AA 私下做过签名这一步。

---

## 执行步骤

### 1. 生成 keystore

```bash
cd /Users/ethan/code/lan-agent
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
$JAVA_HOME/bin/keytool -genkeypair -v \
  -keystore app/lanagent-release.jks \
  -alias lanagent \
  -keyalg RSA -keysize 2048 -validity 10000
```

- 交互里问的 store 密码与后面 `keyPassword` **可以给同一个**
- 不写 `-storetype` 即 PKCS12(现代 keytool 默认),AGP 一样认
- `-validity 10000` ≈ 27 年;Google Play 要求有效期至少到 2033-10-22,这个数够
- ⚠️ `JAVA_HOME` 必须显式设 —— `/usr/libexec/java_home` 在这台机器是 broken(同 `AGENTS.md` 强制规则)

### 2. 生成密码

```bash
openssl rand -base64 24
```

### 3. 备份 keystore —— 这步不能省

`app/lanagent-release.jks` **丢失或密码遗忘 = 永远无法给已装用户推更新**
(除非走 Play 的重置签名密钥流程,个人应用基本等于没有)。拷到密码管理器 / 加密盘,别只留在仓库目录。

### 4. 补 `.gitignore`

现有 `## Keystore files` 段只挡了 `*.jks` / `*.keystore`,**`keystore.properties` 是漏的**。在该段下加一行:

```
keystore.properties
```

### 5. 写仓库根 `keystore.properties`(第 4 步做完才安全)

```properties
storeFile=lanagent-release.jks
keyAlias=lanagent
storePassword=<第 2 步生成的>
keyPassword=<同 storePassword>
```

### 6. 接进 `app/build.gradle.kts`

顶部加(与现有 `asrLocalProps` 同一套 `local.properties` 读法):

```kotlin
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
```

`android { }` 内加 `signingConfigs`,并给 `buildTypes.release` 挂上:

```kotlin
    signingConfigs {
        create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
```

`storeFile` 写相对路径时,`file()` 相对 **`app/` 模块目录**解析 —— 所以第 1 步把 jks 放在 `app/` 下是对的
(根 `.gitignore` 的 `*.jks` 无斜杠,匹配任意层级,`app/` 下同样被忽略)。

### 7. 构建 + 验签

```bash
./gradlew :app:assembleRelease
$JAVA_HOME/bin/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

看到 `Signer #1 certificate SHA-256 digest:` 即签名成功(默认 SHA-256,不用额外配置)。

### 8. 装机前必须先卸载(重要)

手机/模拟器上现装的是 debug 包(debug keystore 签的),换 release 签名后签名不一致:

```
adb install → INSTALL_FAILED_UPDATE_INCOMPATIBLE
```

只能先卸:

```bash
adb uninstall io.github.hotmanxp.lanagent
```

**卸载 = DataStore 全丢**:卡片列表、AA 登录 token、SSH 主机、自填 IP 白名单都要重配。
发第一版 release 前先想清楚要不要让用户重配一遍。

---

## 签名身份不可逆

签名一旦定下就**不能换**:换 key 后所有已装用户都无法原地升级,只能卸载重装(连带丢 DataStore)。
所以第 3 步的备份是整份文档里最重要的一条。
