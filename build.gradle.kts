// AGP 9.0 以降、Kotlin サポートは AGP に組み込まれた。
// `org.jetbrains.kotlin.android` を適用すると
// 「The plugin is no longer required for Kotlin support since AGP 9.0」で落ちる。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
