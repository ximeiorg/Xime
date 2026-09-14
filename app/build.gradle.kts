import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

apply(from = "build-logic/tasks-native.gradle.kts")
apply(from = "build-logic/tasks-plugin-dev.gradle.kts")

// 获取 Git 提交哈希
fun getGitHash(): String {
    return try {
        val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(rootDir)
            .start()
        process.inputStream.bufferedReader().readText().trim()
    } catch (e: Exception) {
        "unknown"
    }
}

// 获取构建时间已移除：构建时刻会写入 BuildConfig 进而进入 classes.dex，
// 破坏 F-Droid 可复现构建（不同环境构建时间不同导致产物不一致）。

// 加载签名配置
val keystorePropertiesFile = rootProject.file("app/keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "com.kingzcheung.xime"
    compileSdk = 36

    // JVM 单测中未 mock 的 Android 框架方法（如 android.util.Log）返回默认值而非抛异常，
    // 使服务层状态机（如语音收尾流程）可直接实例化测试
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    defaultConfig {
        applicationId = "com.kingzcheung.xime"
        minSdk = 28
        targetSdk = 35
        versionCode = 20260911
        versionName = "2.9.0-beta1"


        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // NDK 配置
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }

        // 构建信息
        buildConfigField("String", "GIT_HASH", "\"${getGitHash()}\"")
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                // 优先使用 storeFile（已存在的 keystore 文件）；否则解码 keyBase64。
                // keyBase64 方式用于 CI/F-Droid 从 secrets 注入签名。
                val storeFileProp = keystoreProperties.getProperty("storeFile")?.let { file(it) }
                storeFile = if (storeFileProp != null && storeFileProp.exists()) {
                    storeFileProp
                } else {
                    keystoreProperties.getProperty("keyBase64")?.let { keyBase64 ->
                        val ks = File(layout.buildDirectory.get().asFile, "release-keystore.jks")
                        ks.writeBytes(Base64.getDecoder().decode(keyBase64.replace("\n", "").trim()))
                        ks
                    }
                }
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
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
            // 只在本地有 keystore.properties 时才使用签名配置
            // GitHub Actions 使用自己的签名方式
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xunused")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    // NDK 构建配置
    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // 打包配置
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    ndkVersion = "29.0.14206865"

    // F-Droid 一致性验证：不在 APK 中写入依赖元数据（Dependency metadata 签名块）。
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // 测试 classpath 包含 main assets，使 T9Decoder() 无参构造可加载 pinyin_lm.bin
    sourceSets {
        getByName("test") {
            resources.srcDirs("src/main/assets")
        }
    }
    lint {
        checkReleaseBuilds = false
        checkGeneratedSources = false
        abortOnError = false
        checkDependencies = true
    }

    // 分架构打包
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
}

android.applicationVariants.all {
    val appName = "Xime"
    outputs.all {
        val abi = filters.find { it.filterType.toString() == "ABI" }?.identifier ?: "universal"
        (this as BaseVariantOutputImpl).outputFileName = "$appName-$versionName-$abi.apk"
    }
}

// Nightly 构建通过 androidComponents API 覆盖 versionCode/versionName
androidComponents {
    onVariants { variant ->
        val vc = project.findProperty("versionCode")?.toString()?.toIntOrNull()
        val vn = project.findProperty("versionName")?.toString()
        if (vc != null && vn != null) {
            variant.outputs.forEach { output ->
                output.versionCode.set(vc)
                output.versionName.set(vn)
            }
        }
    }
}
dependencies {
    implementation(project(":plugin-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Kotlin stdlib - CRITICAL for plugin compatibility
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
    implementation(libs.kotlinx.coroutines.core)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.activity.compose)

    // Material Icons
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // SavedState
    implementation(libs.androidx.savedstate)

    // Coil (Image Loading)
    implementation(libs.coil)

    // OkHttp for WebSocket and model download
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    // okhttp-sse for SSE stream parsing (plugin streaming API)
    implementation("com.squareup.okhttp3:okhttp-sse:5.4.0")
    // Apache Commons Compress for tar.bz2 extraction
    implementation("org.apache.commons:commons-compress:1.28.0")

    // Kaml for YAML parsing
    implementation(libs.kaml)

    // Autofill inline suggestions (API 30+)
    implementation(libs.androidx.autofill)

    // exp4j for calculator expression evaluation
    implementation(libs.exp4j)

    // ZXing for QR code generation
    implementation("com.google.zxing:core:3.5.4")

    // Ktor embedded server for wireless import
    implementation("io.ktor:ktor-server-core:3.5.2")
    implementation("io.ktor:ktor-server-cio:3.5.2")
    implementation(libs.kotlinx.serialization.json)

    // Room 3.0 (SQLite)
    implementation(libs.androidx.room3.runtime)
    ksp(libs.androidx.room3.compiler)

    // Sora Code Editor for YAML viewing/editing
    implementation(platform("io.github.rosemoe:editor-bom:0.24.6"))
    implementation("io.github.rosemoe:editor")
    implementation("io.github.rosemoe:language-textmate")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.3.0")
    // JVM 单测使用真实 org.json 实现（android.jar 内为抛异常的 stub）
    testImplementation("org.json:json:20240303")

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.concurrent:concurrent-futures:1.2.0")
}

// Align concurrent-futures version: espresso 3.7.0 requires 1.2.0
dependencies {
    constraints {
        implementation("androidx.concurrent:concurrent-futures:1.2.0") {
            because("test dependencies (espresso 3.7.0) require 1.2.0")
        }
    }
}
