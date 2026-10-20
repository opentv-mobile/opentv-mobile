package app.opentv.data.local

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalPlaylistStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val valid = "#EXTM3U\n#EXTINF:-1 tvg-id=\"one\",One\nhttps://example.com/live.m3u8\n"

    @Test fun `startup reconciliation deletes only generated orphans and never races new preparations`() {
        val previousProcess = LocalPlaylistStore(temporary.root)
        val referenced = previousProcess.prepare("kept.m3u", { valid.byteInputStream() })
        val orphan = previousProcess.prepare("orphan.m3u", { valid.byteInputStream() })
        val unrelated = java.io.File(temporary.root, "notes.m3u").apply { writeText("keep") }
        val directory = java.io.File(temporary.root, "00000000-0000-0000-0000-000000000000.m3u").apply { mkdir() }
        val restarted = LocalPlaylistStore(temporary.root)
        val inFlight = restarted.prepare("import.m3u", { valid.byteInputStream() })
        restarted.reconcileStartup(setOf(referenced.fileName))
        assertThat(java.io.File(temporary.root, orphan.fileName).exists()).isFalse()
        assertThat(java.io.File(temporary.root, referenced.fileName).readText()).isEqualTo(valid)
        assertThat(java.io.File(temporary.root, inFlight.fileName).readText()).isEqualTo(valid)
        assertThat(unrelated.exists()).isTrue()
        assertThat(directory.isDirectory).isTrue()
    }

    @Test fun `accepts remote IPTV and never uses document name as path`() {
        val store = LocalPlaylistStore(temporary.root)
        val first = store.prepare("../../a.m3u8", { valid.byteInputStream() })
        val second = store.prepare("../../a.m3u8", { valid.byteInputStream() })
        assertThat(first.fileName).isNotEqualTo(second.fileName)
        assertThat(first.fileName).doesNotContain("a.m3u8")
        assertThat(first.displayName).isEqualTo("a.m3u8")
        assertThat(first.parsed.channels.single().streamUrl).isEqualTo("https://example.com/live.m3u8")
        assertThat(temporary.root.listFiles()!!.size).isEqualTo(2)
        assertThat(safePlaylistDisplayName("x/evil\u202e\n.m3u")).isEqualTo("evil.m3u")
        store.delete("../../outside.m3u") // Corrupt database paths are ignored.
        assertThat(temporary.root.listFiles()!!.size).isEqualTo(2)
    }

    @Test fun `rejects empty malformed HLS relative and local media without retaining a file`() {
        val store = LocalPlaylistStore(temporary.root)
        for (text in listOf(
            "", "#EXTM3U\n", "<html>bad</html>",
            "#EXTM3U\n#EXTINF:-1,One\n", // Truncated entry.
            "#EXTM3U\n#EXTINF:-1,One\n#EXTINF:-1,Two\nhttps://example.com/two",
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\nhttps://example.com/a.m3u8",
            "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nhttps://example.com/a.ts",
            "#EXTM3U\n#EXTINF:-1,One\nrelative.ts",
            "#EXTM3U\n#EXTINF:-1,One\nfile:///sdcard/video.mp4",
            "#EXTM3U\n#EXTINF:-1,One\ncontent://media/video/1",
            valid + "#EXTINF:-1,Two\n../relative.ts",
            "#EXTM3U\n" + "x".repeat(65537),
        )) {
            assertThrows(LocalPlaylistException::class.java) { store.prepare("list.m3u", { text.byteInputStream() }) }
            assertThat(temporary.root.listFiles()).isEmpty()
        }
        assertThrows(LocalPlaylistException::class.java) { store.prepare("list.txt", { valid.byteInputStream() }) }
    }

    @Test fun `copy errors and cancellation leave previous snapshot byte identical`() {
        val store = LocalPlaylistStore(temporary.root)
        val previous = store.prepare("list.m3u", { valid.byteInputStream() })
        assertThrows(IOException::class.java) {
            store.prepare("list.m3u", { object : InputStream() {
                override fun read(): Int = throw IOException("Read failed")
            } })
        }
        assertThrows(CancellationException::class.java) {
            store.prepare("list.m3u", { valid.byteInputStream() }) { throw CancellationException() }
        }
        assertThat(temporary.root.listFiles()!!.map { it.name }).containsExactly(previous.fileName)
        assertThat(java.io.File(temporary.root, previous.fileName).readText()).isEqualTo(valid)
    }

    @Test fun `enforces byte cap during streaming even without a provider size`() {
        var remaining = LocalPlaylistStore.MAX_BYTES + 1
        var closed = false
        val huge = object : InputStream() {
            override fun read(): Int = if (remaining-- > 0) 'x'.code else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining == 0L) return -1
                val count = minOf(remaining, length.toLong()).toInt()
                buffer.fill('x'.code.toByte(), offset, offset + count)
                remaining -= count
                return count
            }
            override fun close() { closed = true }
        }
        val error = assertThrows(LocalPlaylistException::class.java) {
            LocalPlaylistStore(temporary.root).prepare("huge.m3u", { huge })
        }
        assertThat(error.problem).isEqualTo(LocalPlaylistProblem.TOO_LARGE)
        assertThat(closed).isTrue()
        assertThat(temporary.root.listFiles()).isEmpty()
    }
}
