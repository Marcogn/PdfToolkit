package com.marcogn.pdftoolkit.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * App language through the AndroidX per-app APIs (from API 26 thanks to the backport, not only
 * from 33). A null [tag] follows the system language. Same scheme as the reference projects.
 */
enum class AppLanguage(val tag: String?) {
    SISTEMA(null),
    ITALIANO("it"),
    ENGLISH("en"),
}

fun currentAppLanguage(): AppLanguage {
    val currentTag = AppCompatDelegate.getApplicationLocales().toLanguageTags().takeIf { it.isNotEmpty() }
    return AppLanguage.entries.firstOrNull { it.tag == currentTag } ?: AppLanguage.SISTEMA
}

/**
 * autoStoreLocales (manifest) persists the choice on its own. No manual `recreate()`:
 * `setApplicationLocales()` already recreates the activity, but only if it extends
 * `AppCompatActivity`.
 */
fun applyAppLanguage(language: AppLanguage) {
    val locales = language.tag?.let(LocaleListCompat::forLanguageTags) ?: LocaleListCompat.getEmptyLocaleList()
    AppCompatDelegate.setApplicationLocales(locales)
}
