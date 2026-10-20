/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.local

import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.parser.M3uParser
import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.UUID

val Source.isLocalPlaylist: Boolean
    get() = kind == SourceKind.M3U && localPlaylistFileName != null

/** Shared by every automatic/manual catalogue refresh entry point. EPG is independent. */
fun Source.canRefreshCatalogue(): Boolean = !isLocalPlaylist

enum class LocalPlaylistProblem { TOO_LARGE, INVALID, HLS, REMOTE_ONLY, FILE_TYPE }

class LocalPlaylistException(val problem: LocalPlaylistProblem) : Exception(problem.name)

/** Provider metadata is a label, never a path (including when restored from the database). */
fun safePlaylistDisplayName(raw: String?): String = raw.orEmpty()
    .substringAfterLast('/').substringAfterLast('\\')
    .filterNot { Character.isISOControl(it) || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }
    .trim().take(160).ifBlank { "playlist.m3u" }

/** Immutable, app-private snapshots. A failed copy/validation owns and removes only its new file. */
class LocalPlaylistStore(private val directory: File) {
    // Captured before this store can prepare imports. A later sweep must never touch a file
    // prepared by a concurrent import, even before that import publishes its database row.
    private val startupFiles = directory.listFiles().orEmpty().filter {
        FILE_NAME.matches(it.name) && it.isFile && it.canonicalFile == File(directory.canonicalFile, it.name)
    }.map { it.name }.toSet()

    @Synchronized
    fun reconcileStartup(referencedNames: Set<String>) {
        (startupFiles - referencedNames).forEach(::delete)
    }

    data class Prepared(val fileName: String, val displayName: String, val parsed: M3uParser.Result)

    fun prepare(displayName: String?, open: () -> InputStream, checkCancelled: () -> Unit = {}): Prepared {
        val label = safePlaylistDisplayName(displayName)
        if (!label.endsWith(".m3u", true) && !label.endsWith(".m3u8", true)) {
            throw LocalPlaylistException(LocalPlaylistProblem.FILE_TYPE)
        }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create playlist storage" }
        val name = "${UUID.randomUUID()}.m3u"
        val target = resolve(name)
        check(target.createNewFile()) { "Playlist snapshot already exists" }
        try {
            open().use { input ->
                val fileOutput = target.outputStream()
                fileOutput.buffered().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var copied = 0L
                    while (true) {
                        checkCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > MAX_BYTES) throw LocalPlaylistException(LocalPlaylistProblem.TOO_LARGE)
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
            target.inputStream().use { validate(it, checkCancelled) }
            checkCancelled()
            val parsed = target.inputStream().use { M3uParser.parse(it, 0) }
            if (parsed.channels.isEmpty() || parsed.skippedEntries > 0) {
                throw LocalPlaylistException(LocalPlaylistProblem.INVALID)
            }
            checkCancelled()
            return Prepared(name, label, parsed)
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
    }

    fun delete(fileName: String?) {
        // Reject corrupt/legacy database paths instead of deleting outside this directory.
        if (fileName != null && FILE_NAME.matches(fileName)) resolve(fileName).delete()
    }

    private fun resolve(fileName: String): File {
        require(FILE_NAME.matches(fileName))
        return File(directory, fileName)
    }

    companion object {
        const val MAX_BYTES = 50L * 1024 * 1024
        private val FILE_NAME = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.m3u")
        private const val MAX_LINE = 64 * 1024
        private val REMOTE_SCHEMES = setOf("http", "https", "rtsp", "rtmp", "rtp", "udp")

        /** Strict envelope around the deliberately tolerant remote M3U parser. */
        fun validate(input: InputStream, checkCancelled: () -> Unit = {}) {
            var header = false
            var pending = false
            var count = 0
            java.io.InputStreamReader(input, Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)).buffered().use { reader ->
                val line = StringBuilder()
                fun accept() {
                    checkCancelled()
                    val text = line.toString().trim().removePrefix("\uFEFF")
                    line.setLength(0)
                    if (text.isEmpty()) return
                    if (!header) {
                        if (!text.equals("#EXTM3U", true) && !text.startsWith("#EXTM3U ", true)) {
                            throw LocalPlaylistException(LocalPlaylistProblem.INVALID)
                        }
                        header = true
                    } else if (text.startsWith("#EXT-X-", true)) {
                        throw LocalPlaylistException(LocalPlaylistProblem.HLS)
                    } else if (text.startsWith("#EXTINF:", true)) {
                        if (pending || !text.contains(',')) throw LocalPlaylistException(LocalPlaylistProblem.INVALID)
                        pending = true
                    } else if (!text.startsWith('#')) {
                        if (!pending) throw LocalPlaylistException(LocalPlaylistProblem.INVALID)
                        val uri = runCatching { URI(text) }.getOrNull()
                        if (uri == null || uri.scheme?.lowercase() !in REMOTE_SCHEMES ||
                            uri.rawAuthority.isNullOrBlank()) {
                            throw LocalPlaylistException(LocalPlaylistProblem.REMOTE_ONLY)
                        }
                        pending = false
                        count++
                    }
                }
                while (true) {
                    val c = reader.read()
                    if (c == -1) break
                    if (c == '\n'.code || c == '\r'.code) accept()
                    else {
                        if (line.length >= MAX_LINE || c == 0) throw LocalPlaylistException(LocalPlaylistProblem.INVALID)
                        line.append(c.toChar())
                    }
                }
                accept()
            }
            if (!header || pending || count == 0) throw LocalPlaylistException(LocalPlaylistProblem.INVALID)
        }
    }
}
