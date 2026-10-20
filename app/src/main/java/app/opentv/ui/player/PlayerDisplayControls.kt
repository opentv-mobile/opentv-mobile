/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.player

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import app.opentv.MainActivity
import app.opentv.R
import app.opentv.core.AppSettings
import app.opentv.core.PlayerOrientation
import app.opentv.core.findActivity
import app.opentv.isRunningOnTelevision

/** Live and every VOD source share the same preference and controls; no player is rebuilt. */
@OptIn(UnstableApi::class)
@Composable
fun PlayerDisplayControls(
    settings: AppSettings,
    onMenuVisibilityChanged: (Boolean) -> Unit = {},
    onInteraction: () -> Unit,
) {
    val resizeMode by settings.playerResizeMode.collectAsState()
    var sizeMenu by remember { mutableStateOf(false) }
    var orientationMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val activity = context.findActivity() as? MainActivity
    DisposableEffect(sizeMenu, orientationMenu) {
        onMenuVisibilityChanged(sizeMenu || orientationMenu)
        onDispose { onMenuVisibilityChanged(false) }
    }
    Row {
        androidx.compose.foundation.layout.Box {
            TextButton(onClick = { sizeMenu = true; onInteraction() }) {
                Icon(Icons.Filled.AspectRatio, contentDescription = null, tint = Color.White)
                Text(stringResource(R.string.player_display_size), color = Color.White)
            }
            DropdownMenu(expanded = sizeMenu, onDismissRequest = { sizeMenu = false; onInteraction() }) {
                listOf(
                    AspectRatioFrameLayout.RESIZE_MODE_FIT to R.string.player_display_fit,
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to R.string.player_display_fill,
                    AspectRatioFrameLayout.RESIZE_MODE_FILL to R.string.player_display_stretch,
                ).forEach { (mode, label) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        leadingIcon = { if (mode == resizeMode) Icon(Icons.Filled.Check, contentDescription = null) },
                        onClick = { settings.setPlayerResizeMode(mode); sizeMenu = false; onInteraction() },
                    )
                }
            }
        }
        // Rotation on a living-room TV is neither useful nor reliably supported.
        if (activity != null && !isRunningOnTelevision(context)) {
            androidx.compose.foundation.layout.Box {
                TextButton(onClick = { orientationMenu = true; onInteraction() }) {
                    Icon(Icons.Filled.ScreenRotation, contentDescription = null, tint = Color.White)
                    Text(stringResource(R.string.player_display_orientation), color = Color.White)
                }
                DropdownMenu(expanded = orientationMenu, onDismissRequest = { orientationMenu = false; onInteraction() }) {
                    listOf(
                        PlayerOrientation.LANDSCAPE to R.string.player_display_landscape,
                        PlayerOrientation.PORTRAIT to R.string.player_display_portrait,
                        PlayerOrientation.SYSTEM to R.string.player_display_system,
                        PlayerOrientation.LOCKED to R.string.player_display_lock,
                    ).forEach { (orientation, label) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(label)) },
                            leadingIcon = {
                                if (activity.playerOrientationSession.orientation == orientation)
                                    Icon(Icons.Filled.Check, contentDescription = null)
                            },
                            onClick = {
                                activity.selectPlayerOrientation(orientation)
                                orientationMenu = false
                                onInteraction()
                            },
                        )
                    }
                }
            }
        }
    }
}
