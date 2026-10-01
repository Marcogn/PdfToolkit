// AGP 9 compiles Kotlin itself (built-in Kotlin): there is no org.jetbrains.kotlin.android plugin.
// The Kotlin version comes from the compose/serialization plugins below (see docs/plan.md).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.ksp) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
}
