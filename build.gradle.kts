// Kotlin is built into AGP 9, so there is deliberately no `org.jetbrains.kotlin.android` plugin here.
plugins {
    alias(libs.plugins.android.application)  apply false
    alias(libs.plugins.kotlin.compose)       apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
