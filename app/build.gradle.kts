import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 签名信息放 keystore.properties（已在 .gitignore 中，不入库）。
// 没有这个文件时 release 回退到 debug 签名 —— 至少能出一个可安装的包，
// 而不是一个装不上的 unsigned APK。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "com.ipodplayer3.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ipodplayer3.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "3.0.0"
        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 只保留中英文资源，去掉依赖库里其它语言的翻译（几 MB 级别）。
        resourceConfigurations += listOf("zh", "en")
    }

    if (hasReleaseKeystore) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/*.kotlin_module",
                "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json"
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // 需要时用 `gradlew :app:updateLintBaseline` 生成基线再取消注释
        // baseline = file("lint-baseline.xml")
        abortOnError = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // 注意：Media3 的 @UnstableApi 用的是 androidx.annotation.RequiresOptIn，
        // Kotlin 不把它当 opt-in 标记（加了 -opt-in 只会得到一条「不是标记」的告警）。
        // 真正的把关在 lint（UnsafeOptInUsageError）：我们的类都标了 @UnstableApi，
        // 由 `lintVitalRelease` 验证。
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")

    // Media3
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    // PlayerController 直接用 ListenableFuture / MoreExecutors，
    // 它只是 media3-session 的传递依赖：显式声明，版本与 media3 1.4.1 对齐。
    implementation("com.google.guava:guava:32.1.3-android")

    // Data
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Coil for album art
    implementation("io.coil-kt:coil-compose:2.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

// ─────────────────────────────────────────────────────────────────────────────
// 证书轮换（APK Signing Certificate Rotation）
//
// AGP 只会用一把钥匙签整包，导致「装过旧签名包的设备」无法覆盖升级。打包后用
// apksigner 附加 lineage，让同一个包在不同平台呈现不同身份：
//   v2 / v3（SDK 24–32）→ 旧证书，与老设备上已安装的包一致，**直接覆盖**；
//   v3.1（Android 13+） → 正式证书，lineage 里含旧证书，**轮换**到正式证书，
//                          配合 lineage 的 installed-data 能力，应用数据不丢。
//
// signing-lineage.bin 只含证书链（公开数据，无私钥），随仓库提交；
// 缺 lineage 或 keystore.properties（克隆仓库 / 没有密钥的 CI）时自动跳过。
// ─────────────────────────────────────────────────────────────────────────────
val lineageBin = rootProject.file("signing-lineage.bin")
val ksPropsFile = rootProject.file("keystore.properties")

tasks.matching { it.name == "packageDebug" || it.name == "packageRelease" }.configureEach {
    val variant = if (name.endsWith("Debug")) "debug" else "release"
    doLast {
        if (!lineageBin.exists() || !ksPropsFile.exists()) {
            logger.info("[lineage] 跳过：缺少 ${lineageBin.name} 或 keystore.properties")
            return@doLast
        }
        try {
            val apk = File(layout.buildDirectory.get().asFile, "outputs/apk/$variant/app-$variant.apk")
            if (!apk.exists()) error("找不到产物 $apk")

            val props = Properties().apply { ksPropsFile.inputStream().use { load(it) } }
            fun req(key: String) = props.getProperty(key) ?: error("keystore.properties 缺少 $key")
            val storeFile = rootProject.file(req("storeFile"))
            if (!storeFile.exists()) error("密钥库不存在：$storeFile")

            // 旧证书：Android 调试密钥（AGP 自动初始化的 debug 签名配置）
            val debugCfg = android.signingConfigs.findByName("debug")
            val debugStore = debugCfg?.storeFile ?: File(System.getProperty("user.home"), ".android/debug.keystore")
            val debugAlias = debugCfg?.keyAlias ?: "androiddebugkey"
            val debugStorePass = debugCfg?.storePassword ?: "android"
            val debugKeyPass = debugCfg?.keyPassword ?: "android"
            if (!debugStore.exists()) error("找不到调试密钥库 $debugStore")

            // Android SDK / build-tools / apksigner
            val sdkProps = Properties().apply {
                val lp = rootProject.file("local.properties")
                if (lp.exists()) lp.inputStream().use { load(it) }
            }
            val sdkDir = File(
                sdkProps.getProperty("sdk.dir")
                    ?: System.getenv("ANDROID_SDK_ROOT")
                    ?: System.getenv("ANDROID_HOME")
                    ?: error("找不到 Android SDK（local.properties 的 sdk.dir 或 ANDROID_SDK_ROOT）")
            )
            val buildTools = File(sdkDir, "build-tools")
                .listFiles()?.filter { it.isDirectory }
                ?.maxByOrNull { it.name }
                ?: error("SDK 下没有 build-tools 目录")
            val isWindows = System.getProperty("os.name").lowercase().contains("windows")
            val apksigner = File(buildTools, if (isWindows) "apksigner.bat" else "apksigner")
            if (!apksigner.exists()) error("找不到 ${apksigner.name}")

            val args = mutableListOf(
                "sign",
                "--lineage", lineageBin.absolutePath,
                "--ks", debugStore.absolutePath, "--ks-pass", "pass:$debugStorePass",
                "--ks-key-alias", debugAlias, "--key-pass", "pass:$debugKeyPass",
                "--next-signer",
                "--ks", storeFile.absolutePath, "--ks-pass", "pass:${req("storePassword")}",
                "--ks-key-alias", req("keyAlias"), "--key-pass", "pass:${req("keyPassword")}",
                apk.absolutePath
            )
            val cmd = if (isWindows) listOf("cmd.exe", "/c", apksigner.absolutePath) + args
                      else listOf(apksigner.absolutePath) + args
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            if (code != 0) error("apksigner 退出码 $code：${output.trim().lines().lastOrNull() ?: ""}")
            logger.lifecycle("[lineage] $variant 已附加轮换链 → ${apk.name}")
        } catch (e: Exception) {
            logger.warn("[lineage] 附加轮换链失败，保留原签名（覆盖升级可能受影响）：${e.message}")
        }
    }
}
