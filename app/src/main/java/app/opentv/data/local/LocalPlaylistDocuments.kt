/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.local

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns

/** OpenDocument grants temporary read access; the private copy needs no persisted URI permission. */
fun ContentResolver.playlistDisplayName(uri: Uri): String? =
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
