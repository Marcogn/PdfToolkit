package com.marcogn.pdftoolkit.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec §9: AA contrast. WCAG 2.x asks 4.5:1 for normal text
 * ([SC 1.4.3](https://www.w3.org/TR/WCAG22/#contrast-minimum), formula in
 * [relative luminance](https://www.w3.org/TR/WCAG22/#dfn-relative-luminance)). Checks the fixed palette;
 * dynamic colour is the system's own.
 */
class ThemeContrastTest {

    private fun channel(value: Float): Double {
        val c = value.toDouble()
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Color) =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun contrast(a: Color, b: Color): Double {
        val (light, dark) = luminance(a).let { la -> luminance(b).let { lb -> maxOf(la, lb) to minOf(la, lb) } }
        return (light + 0.05) / (dark + 0.05)
    }

    private fun pairs(scheme: ColorScheme) = listOf(
        "onPrimary/primary" to (scheme.onPrimary to scheme.primary),
        "onPrimaryContainer/primaryContainer" to (scheme.onPrimaryContainer to scheme.primaryContainer),
        "onSecondary/secondary" to (scheme.onSecondary to scheme.secondary),
        "onSecondaryContainer/secondaryContainer" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
        "onTertiary/tertiary" to (scheme.onTertiary to scheme.tertiary),
        "onTertiaryContainer/tertiaryContainer" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
        "onBackground/background" to (scheme.onBackground to scheme.background),
        "onSurface/surface" to (scheme.onSurface to scheme.surface),
        "onSurfaceVariant/surface" to (scheme.onSurfaceVariant to scheme.surface),
        "onSurfaceVariant/surfaceVariant" to (scheme.onSurfaceVariant to scheme.surfaceVariant),
        "onSurface/surfaceContainerHighest" to (scheme.onSurface to scheme.surfaceContainerHighest),
        "onSurfaceVariant/surfaceContainerHighest" to (scheme.onSurfaceVariant to scheme.surfaceContainerHighest),
        "primary/surface" to (scheme.primary to scheme.surface),
        "tertiary/surface" to (scheme.tertiary to scheme.surface),
        "error/surface" to (scheme.error to scheme.surface),
    )

    private fun check(name: String, scheme: ColorScheme) {
        val failures = pairs(scheme).mapNotNull { (label, colors) ->
            val ratio = contrast(colors.first, colors.second)
            if (ratio < MIN_TEXT_CONTRAST) "$label %.2f".format(ratio) else null
        }
        assertTrue("$name below $MIN_TEXT_CONTRAST:1: $failures", failures.isEmpty())
    }

    @Test fun lightPaletteMeetsAa() = check("light", LightColors)

    @Test fun darkPaletteMeetsAa() = check("dark", DarkColors)

    private companion object {
        const val MIN_TEXT_CONTRAST = 4.5
    }
}
