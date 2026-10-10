import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---- Signing ----
// A local `keystore.properties` (git-ignored, never committed) may point at a signing key:
//   storeFile=/abs/path/to/key.jks
//   storePassword=...   keyAlias=...   keyPassword=...
// When present, BOTH debug and release use that key, so the two build types can overwrite
// each other with `adb install -r` (no INSTALL_FAILED_UPDATE_INCOMPATIBLE).
// When absent, release builds stay unsigned and only the debug build (standard Android
// debug key) is installable — a fresh clone therefore builds without any secrets.
val keystorePropsFile = rootProject.file("keystore.properties")
val hasLocalKeystore = keystorePropsFile.exists()
val localKeystoreProps = Properties().apply {
    if (hasLocalKeystore) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.android.gallery3d.photowidget"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "com.android.gallery3d.photowidget"
        // 用户 2026-10-02 令：抛弃 API 32 及以下，删掉相关兜底代码
        minSdk = 33
        targetSdk = 37
        versionCode = 15
        versionName = "1.14"
        vectorDrawables.useSupportLibrary = true

        // 用户 2026-10-02 令：只适配 arm64-v8a，其余 ABI 的 native 库不打包
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (hasLocalKeystore) {
            create("local") {
                storeFile = rootProject.file(localKeystoreProps.getProperty("storeFile"))
                storePassword = localKeystoreProps.getProperty("storePassword")
                keyAlias = localKeystoreProps.getProperty("keyAlias")
                keyPassword = localKeystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // Same key as release when a local keystore is configured
            if (hasLocalKeystore) signingConfig = signingConfigs.getByName("local")
        }
        release {
            // 用户 2026-10-03 令：改成 release 减小体积（R8 混淆 + 资源压缩 + 正式签名）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Same key as debug when a local keystore is configured; otherwise stays unsigned
            if (hasLocalKeystore) signingConfig = signingConfigs.getByName("local")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// 离线开发工具：列出 releaseRuntimeClasspath 最终选中的全部坐标
// 用法： tools\build_photowidget.ps1 :app:listRuntimeDeps --offline
tasks.register("listRuntimeDeps") {
    doLast {
        configurations.getByName("releaseRuntimeClasspath").incoming.resolutionResult.allComponents
            .mapNotNull { c -> c.moduleVersion?.let { "${it.group}:${it.name}:${it.version}" } }
            .distinct()
            .sorted()
            .forEach { println("DEP $it") }
    }
}

dependencies {
    // 离线钉版本：compose ui-unit 的 POM 点名 androidx.collection:collection-ktx:1.5.0，
    // 但本机 Gradle 缓存里只有 1.6.0（带 jar；1.4.2 只有 pom）。显式钉 1.6.0 走缓存，不联网。
    implementation("androidx.collection:collection-ktx:1.6.0")
    // 同理：tracing 1.2.0 在缓存里只有 .module/.pom（无 aar），能用的是 2.0.0（tracing-android 有 aar）
    implementation("androidx.tracing:tracing:2.0.0")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.annotation:annotation:1.11.0")
    // Material 3 Expressive 的 XML 主题来源（Theme.Material3Expressive.*，共 45 个可用主题）
    implementation("com.google.android.material:material:1.14.0")
    // material 的传递依赖点名了缓存里没有的旧版，这里显式钉到缓存里有的版本（离线要求）
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // 用户 2026-10-03 令：去除 debug（不再引入 debug-only 的 ui-tooling）
}
