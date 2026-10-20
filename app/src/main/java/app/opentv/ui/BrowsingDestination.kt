/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

/**
 * Non-player destinations own one safe viewport for both chrome and content, regardless of layout
 * class or rotation. Keep this INSIDE each destination: padding the NavHost would also constrain
 * fullscreen video/PiP and make outgoing screens jump during navigation to/from the player.
 */
fun NavGraphBuilder.browsingDestination(
    route: String,
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable(route) { entry ->
        BrowsingViewport { content(entry) }
    }
}

/**
 * safeDrawing is the union (not the sum) of system bars, display cutout and IME. Applying and
 * consuming it here protects interactive bounds on all four physical edges, including a left/right
 * three-button bar. Descendant Material navigation bars/inset modifiers see only the remainder,
 * so portrait does not reserve the navigation bar twice. The Activity's background stays edge-to-edge.
 */
@Composable
internal fun BrowsingViewport(
    insets: WindowInsets = WindowInsets.safeDrawing,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().windowInsetsPadding(insets)) {
        content()
    }
}
