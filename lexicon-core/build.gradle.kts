plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // libime 二进制词库（version 2）用 zstd 压缩；Android 侧由 app 引入同版本 AAR。
    compileOnly(libs.zstd.jni)
    testImplementation(libs.zstd.jni)
    testImplementation(libs.junit)
}
