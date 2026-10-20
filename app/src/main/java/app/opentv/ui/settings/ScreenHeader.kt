/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.opentv.R
import app.opentv.ui.LayoutClass
import app.opentv.ui.LocalLayoutClass

/**
 * The title row every settings-style screen starts with.
 *
 * TV: big title, actions, then a focusable Done button on the right — reachable with the d-pad.
 * Phone: a back arrow and a single-line title, the standard Android top bar; a wide Done button
 * next to a headline-sized title was what squashed it out of shape on narrow screens.
 */
@Composable
fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    icon: ImageVector? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    if (LocalLayoutClass.current == LayoutClass.PHONE) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_done))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.weight(1f))
        actions()
        Spacer(Modifier.width(12.dp))
        OutlinedButton(onClick = onBack) { Text(stringResource(R.string.common_done)) }
    }
}

/**
 * Design spacing inside the browsing destination's safe viewport: generous on a TV (overscan),
 * tight on a phone. System/IME insets belong to the destination, not the header or a scrolling item.
 */
fun Modifier.screenPadding(): Modifier = composed {
    if (LocalLayoutClass.current == LayoutClass.PHONE) {
        padding(horizontal = 12.dp, vertical = 4.dp)
    } else {
        padding(horizontal = 32.dp, vertical = 24.dp)
    }
}
