/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.repo

import app.opentv.R
import app.opentv.core.AppText
import app.opentv.data.db.SourceDao
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.local.isLocalPlaylist
import app.opentv.data.db.OpenTvDatabase
import androidx.room.withTransaction
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class SourceRepository(
    private val dao: SourceDao,
    private val api: XtreamApi,
    private val stalkerApi: StalkerApi,
    private val database: OpenTvDatabase? = null,
) {
    fun observeAll(): Flow<List<Source>> = dao.observeAll()

    suspend fun enabled(): List<Source> = dao.enabled()

    suspend fun byId(id: Long): Source? = dao.byId(id)

    suspend fun save(source: Source): Long = withContext(Dispatchers.IO) {
        database?.withTransaction { saveRows(source) } ?: saveRows(source)
    }

    private suspend fun saveRows(source: Source): Long {
        // Only the explicit import path may publish/change a private snapshot.
        val previous = if (source.id != 0L) dao.byId(source.id) else null
        if (previous?.isLocalPlaylist == true || source.isLocalPlaylist) {
            val local = checkNotNull(previous)
            require(local.isLocalPlaylist)
            dao.update(local.copy(name = source.name, epgUrl = source.epgUrl, userAgent = source.userAgent))
            return source.id
        }
        val normalised = source.copy(url = normaliseUrl(source.url, source.kind))
        return if (source.id == 0L) dao.insert(normalised)
        else {
            dao.update(normalised)
            source.id
        }
    }

    /** Checks the details work before the user is committed to them. */
    suspend fun test(source: Source): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            when (source.kind) {
                SourceKind.XTREAM -> {
                    val info = api.authenticate(
                        source.copy(url = normaliseUrl(source.url, source.kind)),
                    )
                    buildString {
                        append(AppText.get(R.string.msg_connected))
                        info.username?.let { append(AppText.get(R.string.msg_connected_as, it)) }
                        info.maxConnections?.let { append(AppText.get(R.string.msg_connections, it)) }
                        info.expiryMillis?.let {
                            append(AppText.get(R.string.msg_expires, java.text.DateFormat.getDateInstance().format(java.util.Date(it))))
                        }
                    }
                }
                SourceKind.M3U -> AppText.get(R.string.msg_playlist_valid)
                SourceKind.STALKER -> {
                    stalkerApi.handshakeTest(source.copy(url = normaliseUrl(source.url, source.kind)))
                    AppText.get(R.string.msg_portal_ok)
                }
            }
        }
    }

    companion object {
        /**
         * Users paste all sorts of things. Accept them all rather than making someone guess
         * the format: a trailing slash, a missing scheme, or a full `get.php` URL copied out
         * of a provider's welcome email.
         *
         * For an Xtream source we want the bare host, so API paths are stripped. For an M3U
         * source the URL *is* the playlist — stripping the path there would destroy it, which
         * is why [kind] is not optional.
         *
         * Pure and static so it can be tested without standing up the repository.
         */
        fun normaliseUrl(raw: String, kind: SourceKind): String {
            var url = raw.trim()
            if (url.isEmpty()) return url
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                url = "http://$url"
            }
            if (kind == SourceKind.M3U) return url
            // A Stalker portal URL is the portal path itself (e.g. .../stalker_portal or .../c) —
            // stripping Xtream API paths would be wrong; just tidy the trailing slash.
            if (kind == SourceKind.STALKER) return url.trimEnd('/')

            url = url.substringBefore("/player_api.php")
                .substringBefore("/panel_api.php")
                .substringBefore("/get.php")
                .substringBefore("/xmltv.php")
            return url.trimEnd('/')
        }
    }
}
