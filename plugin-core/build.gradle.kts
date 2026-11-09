plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.kingzcheung.xime.plugin.core"
    compileSdk = 37
    // 平台按小版本安装（SDK 里是 platforms;android-37.2），不写 minor 时 AGP 会去找 android-37.0
    compileSdkMinor = 2

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }
}

dependencies {
    constraints {
        implementation("org.jetbrains:annotations:23.0.0")
    }
    
    api(kotlin("stdlib"))
    api(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.core)

    api(platform(libs.androidx.compose.bom))
    api("androidx.compose.runtime:runtime")
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
    api(libs.androidx.activity.compose)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.lifecycle.runtime.compose)

    // JS 脚本插件运行时（QuickJS，main.js 沙箱执行）
    api("io.github.dokar3:quickjs-kt-android:1.0.17")
    // manifest.json 解析（kotlinx Json 宽松模式：注释 + 尾逗号；不用 kaml，插件链路与 JS 生态对齐）
    implementation(libs.kotlinx.serialization.json)
    
    testImplementation("junit:junit:4.13.2")
}

// quickjs-kt 不提供纯 JVM 的 Android 替代：本地单元测试用 -jvm artifact（不含 .so）
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android"))
            .using(module("io.github.dokar3:quickjs-kt-jvm:1.0.15"))
    }
}
