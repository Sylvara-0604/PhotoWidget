plugins {
    // AGP 9 起内置 Kotlin 支持，不再 apply org.jetbrains.kotlin.android
    id("com.android.application") version "9.4.1" apply false
    // Kotlin 2.0+ 启用 Compose 必须加编译器插件；版本必须与 AGP 内置 Kotlin 一致
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
