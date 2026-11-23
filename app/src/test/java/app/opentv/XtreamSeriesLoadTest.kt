package app.opentv

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.remote.XtreamApi
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class XtreamSeriesLoadTest {
    private lateinit var db: OpenTvDatabase
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val source = Source(id = 17, name = "Test", kind = SourceKind.XTREAM,
        url = "http://xtream.test", username = "user", password = "pass")

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, OpenTvDatabase::class.java).build()
        context.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() { db.close() }

    private fun client(body: String, code: Int = 200) = OkHttpClient.Builder().addInterceptor(
        Interceptor {
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(code)
                .message(if (code == 200) "OK" else "Failed")
                .body(body.toResponseBody()).build()
        },
    ).build()

    @Test fun `series array converts and persists every item`() = runTest {
        val api = XtreamApi(client("""[{"series_id":1,"name":"One"},{"series_id":"2","name":"Two"}]"""))
        val batches = mutableListOf<Int>()
        api.series(source) { batch -> batches += batch.size; db.series().upsertAll(batch) }
        assertThat(batches).containsExactly(2)
        assertThat(db.series().count()).isEqualTo(2)
    }

    @Test fun `valid empty array succeeds with no batches`() = runTest {
        var called = false
        XtreamApi(client("[]")).series(source) { called = true }
        assertThat(called).isFalse()
    }

    @Test fun `http error and non array response fail`() = runTest {
        for ((body, code) in listOf("[]" to 503, "{}" to 200, "" to 200)) {
            val result = runCatching { XtreamApi(client(body, code)).series(source) {} }
            assertThat(result.isFailure).isTrue()
        }
    }

    @Test fun `invalid and truncated json fail after prior batch`() = runTest {
        val valid = (1..1000).joinToString(",") { "{\"series_id\":$it,\"name\":\"S$it\"}" }
        for (suffix in listOf(",{\"series_id\":}", ",{\"series_id\":1001,\"name\":\"last\"")) {
            var persisted = 0
            val result = runCatching {
                XtreamApi(client("[$valid$suffix")).series(source) { persisted += it.size }
            }
            assertThat(result.isFailure).isTrue()
            assertThat(persisted).isEqualTo(1000)
        }
    }

    @Test fun `network and persistence failures propagate`() = runTest {
        val network = OkHttpClient.Builder().addInterceptor { throw IOException("offline") }.build()
        assertThat(runCatching { XtreamApi(network).series(source) {} }.isFailure).isTrue()

        val api = XtreamApi(client("""[{"series_id":1,"name":"One"}]"""))
        val result = runCatching { api.series(source) { throw IOException("database full") } }
        assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
    }

    @Test fun `series sync rethrows cancellation`() = runTest {
        val settings = AppSettings.get(context).also { it.setSeriesEnabled(true); it.setMoviesEnabled(false) }
        val token = CancellationException("cancelled")
        val cancelClient = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.queryParameter("action") == "get_series") throw token
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body("[]".toResponseBody()).build()
        }.build()
        val repository = app.opentv.data.repo.CatalogRepository(
            db.sources(), db.channels(), db.categories(), db.movies(), db.series(), db.episodes(), db.positions(),
            XtreamApi(cancelClient), app.opentv.data.remote.StalkerApi(cancelClient), cancelClient, settings, db,
        )
        val result = runCatching { repository.syncVod(source, 1L) }
        assertThat(result.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
    }

    @Test fun `series sync rethrows io failure`() = runTest {
        val settings = AppSettings.get(context).also { it.setSeriesEnabled(true); it.setMoviesEnabled(false) }
        val ioClient = OkHttpClient.Builder().addInterceptor { chain ->
            if (chain.request().url.queryParameter("action") == "get_series") throw IOException("offline")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body("[]".toResponseBody()).build()
        }.build()
        val repository = app.opentv.data.repo.CatalogRepository(
            db.sources(), db.channels(), db.categories(), db.movies(), db.series(), db.episodes(), db.positions(),
            XtreamApi(ioClient), app.opentv.data.remote.StalkerApi(ioClient), ioClient, settings, db,
        )
        val result = runCatching { repository.syncVod(source, 1L) }
        assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
    }
}
