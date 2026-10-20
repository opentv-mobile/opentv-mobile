/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration

/**
 * Which shell the app draws. Decided once at the root and read anywhere via [LocalLayoutClass],
 * so screens branch on one value instead of each re-deriving "am I on a TV?".
 *
 *  - [TV]: d-pad first, ten-foot sizes, side rails. The original OpenTV layout.
 *  - [PHONE]: touch first, portrait browsing, bottom navigation.
 *  - [TABLET]: touch, but wide enough for the side-by-side TV layout to fit.
 */
enum class LayoutClass {
    TV, PHONE, TABLET;

    val isTouch: Boolean get() = this != TV
}

val LocalLayoutClass = staticCompositionLocalOf { LayoutClass.TV }

/** Keep landscape phones in the touch UI, but spend width rather than scarce height on chrome. */
@Composable
fun isPhoneLandscape(): Boolean = LocalLayoutClass.current == LayoutClass.PHONE &&
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

/** Phones are anything under the standard 600dp smallest-width tablet breakpoint. */
@Composable
fun rememberLayoutClass(isTelevision: Boolean): LayoutClass {
    if (isTelevision) return LayoutClass.TV
    val sw = LocalConfiguration.current.smallestScreenWidthDp
    return if (sw < 600) LayoutClass.PHONE else LayoutClass.TABLET
}
