package com.marcogn.pdftoolkit.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * The viewer's Edit button grows into the edit hub (spec §9: "FAB → Hub: container transform").
 * Both ends carry [editContainerBounds]: the button in the viewer and the root of the edit screen.
 * Compose's shared bounds do the morph; where only one end exists (the hub opened from Home) the
 * modifier has nothing to match and changes nothing.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
internal val LocalSharedTransitionScope = compositionLocal<SharedTransitionScope?>()

/** The animation scope of the nav destination being composed; provided by the nav graph for the two ends. */
internal val LocalDestinationScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

private inline fun <reified T> compositionLocal() = compositionLocalOf<T?> { null }

private const val EDIT_CONTAINER_KEY = "edit-container"

// Spec §9: navigation never above 300 ms; same length as the screen's own enter transition.
private const val EDIT_CONTAINER_MS = 250

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.editContainerBounds(): Modifier {
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    val contentScope = LocalDestinationScope.current ?: return this
    return with(sharedScope) {
        this@editContainerBounds.sharedBounds(
            sharedContentState = rememberSharedContentState(EDIT_CONTAINER_KEY),
            animatedVisibilityScope = contentScope,
            boundsTransform = BoundsTransform { _, _ -> tween(EDIT_CONTAINER_MS, easing = FastOutSlowInEasing) },
        )
    }
}
