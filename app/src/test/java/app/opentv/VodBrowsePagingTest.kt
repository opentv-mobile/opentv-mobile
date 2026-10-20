package app.opentv.data.repo

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.opentv.data.db.OpenTvDatabase
import app.opentv.data.model.Series
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], application = android.app.Application::class)
class VodBrowsePagingTest {
    private lateinit var db: OpenTvDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            OpenTvDatabase::class.java).build()
    }

    @After fun tearDown() { db.close() }

    @Test fun `on demand selector keeps all qualities and providers but respects browse scope`() = runTest {
        db.movies().upsertAll(listOf(
            movieFixture(1, "Film HD").copy(categoryId = "7"),
            movieFixture(2, "Film 4K").copy(sourceId = 2, categoryId = "7"),
            movieFixture(3, "Film FHD").copy(categoryId = "8"),
            movieFixture(4, "Different HD").copy(categoryId = "7"),
        ))
        val repository = VodBrowseRepository(db.movies(), db.series())
        val group = page(db.movies().browseGroups(null, null), size = 10).data.single { it.variantCount == 3 }
        val all = repository.variants(group.key, VodBrowseFilter())
        assertThat(all.map { it.id }).containsExactly(2L, 3L, 1L).inOrder()
        assertThat(all.map { it.streamUrl }.distinct()).hasSize(3)
        assertThat(repository.variants(group.key, VodBrowseFilter(sourceId = 1)).map { it.id })
            .containsExactly(3L, 1L).inOrder()
        assertThat(repository.variants(group.key, VodBrowseFilter(category = VodCategoryKey(2, "7"))).map { it.id })
            .containsExactly(2L)
        assertThat(repository.variants(group.key, VodBrowseFilter(category = VodCategoryKey(1, "7"))).map { it.id })
            .containsExactly(1L)
        db.movies().deleteForSource(2)
        assertThat(repository.variants(group.key, VodBrowseFilter(category = VodCategoryKey(2, "7")))).isEmpty()
    }

    private suspend fun <T : Any> page(source: PagingSource<Int, T>, key: Int? = null, size: Int = 2): PagingSource.LoadResult.Page<Int, T> {
        val params = if (key == null) PagingSource.LoadParams.Refresh<Int>(null, size, false)
            else PagingSource.LoadParams.Append(key, size, false)
        val result = source.load(params)
        check(result is PagingSource.LoadResult.Page) { "Room load failed: $result" }
        return result
    }

    @Test fun `pages contain complete logical groups and match existing grouping across providers`() = runTest {
        val movies = listOf(
            movieFixture(1, "Film 1999 HD"),
            movieFixture(2, "Film 4K", 1999).copy(sourceId = 2),
            movieFixture(3, "Film HD"),
            movieFixture(4, "Film 4K").copy(sourceId = 2),
            movieFixture(5, "Film 2001 HD"),
            movieFixture(6, "Film IMAX HD"),
            movieFixture(7, "Film EXTENDED HD"),
            movieFixture(8, "Film 3D HD"),
            movieFixture(9, "Film 2000 HD", 1999),
            movieFixture(10, "Film  HD"),
        )
        db.movies().upsertAll(movies)
        val source = db.movies().browseGroups(null, null)
        val groups = mutableListOf<MovieBrowseGroup>()
        var next: Int? = null
        do {
            val loaded = page(source, next)
            groups += loaded.data
            next = loaded.nextKey
        } while (next != null)
        assertThat(groups).hasSize(collapseMovieVariants(movies).size)
        assertThat(groups.map { it.key }.distinct()).hasSize(groups.size)
        for (group in groups) {
            val expected = collapseMovieVariants(movies).single { it.primary.id == group.primary.id }
            assertThat(group.variantCount).isEqualTo(expected.variants.size)
            assertThat(db.movies().browseVariants(group.key, null, null).map { it.id })
                .containsExactlyElementsIn(expected.variants.map { it.movie.id }).inOrder()
        }
        assertThat(groups.sumOf { it.variantCount }).isEqualTo(movies.size)
    }

    @Test fun `provider and colliding category scope applies before representative and count`() = runTest {
        db.movies().upsertAll(listOf(
            movieFixture(1, "Film HD").copy(categoryId = "7"),
            movieFixture(2, "Film 4K").copy(sourceId = 2, categoryId = "7"),
            movieFixture(3, "Film FHD").copy(categoryId = "8"),
            movieFixture(4, "Other HD").copy(sourceId = 2, categoryId = "7"),
        ))
        val a = page(db.movies().browseGroups(1, "7")).data.single()
        assertThat(a.primary.id).isEqualTo(1)
        assertThat(a.variantCount).isEqualTo(1)
        val b = page(db.movies().browseGroups(2, "7")).data
        assertThat(b.map { it.primary.id }).containsExactly(2L, 4L)
        val counts = db.movies().browseCategoryCounts(null)
            .associate { VodCategoryKey(it.sourceId, it.categoryId).token to it.count }
        assertThat(counts).containsExactly("1:7", 1, "1:8", 1, "2:7", 2)
        val all = page(db.movies().browseGroups(null, null), size = 10).data
        assertThat(all.first { it.primary.name.startsWith("Film") }.primary.id).isEqualTo(2)
        assertThat(page(db.movies().browseGroups(1, "missing")).data).isEmpty()
        val key = VodCategoryKey(2, "7:extra")
        assertThat(VodCategoryKey.fromToken(key.token)).isEqualTo(key)
        assertThat(VodBrowseFilter(category = key).effectiveSourceId).isEqualTo(2)
    }

    @Test fun `equal timestamp and quality ties have stable ordering across small pages`() = runTest {
        db.movies().upsertAll(listOf(
            movieFixture(9, "Zebra HD"), movieFixture(8, "Alpha HD"),
            movieFixture(7, "Alpha HD"), movieFixture(6, "Beta HD"),
        ).map { it.copy(addedMillis = 100) })
        suspend fun read(): List<Long> {
            val source = db.movies().browseGroups(null, null)
            val a = page(source, size = 1)
            val b = page(source, a.nextKey, 1)
            val c = page(source, b.nextKey, 1)
            assertThat(c.nextKey).isNull()
            return (a.data + b.data + c.data).map { it.primary.id }
        }
        assertThat(read()).containsExactly(7L, 6L, 9L).inOrder()
        assertThat(read()).isEqualTo(read())
        val alpha = page(db.movies().browseGroups(null, null), size = 1).data.single()
        assertThat(db.movies().browseVariants(alpha.key, null, null).map { it.id })
            .containsExactly(7L, 8L).inOrder()
    }

    @Test fun `series pages are source scoped stable and explicitly empty for missing category`() = runTest {
        fun series(id: Long, source: Long = 1) = Series(
            id = id, sourceId = source, seriesId = "$id", name = "Show", categoryId = "7",
            posterUrl = null, rating = null, year = null, plot = null, addedMillis = 10,
        )
        db.series().upsertAll(listOf(series(3, 2), series(2), series(1)))
        val source = db.series().browse(1, "7")
        val a = page(source, size = 1)
        val b = page(source, a.nextKey, 1)
        assertThat((a.data + b.data).map { it.id }).containsExactly(1L, 2L).inOrder()
        assertThat(b.nextKey).isNull()
        assertThat(page(db.series().browse(2, "7")).data.map { it.id }).containsExactly(3L)
        assertThat(page(db.series().browse(1, "missing")).data).isEmpty()
    }

    @Test fun `new sync invalidates logical group paging source`() = runTest {
        db.movies().upsertAll(listOf(movieFixture(1, "Film HD")))
        val source = db.movies().browseGroups(null, null)
        assertThat(page(source).data.single().primary.id).isEqualTo(1)
        val invalidated = CompletableDeferred<Unit>()
        source.registerInvalidatedCallback { invalidated.complete(Unit) }
        db.movies().upsertAll(listOf(movieFixture(2, "Film 4K")))
        withContext(Dispatchers.IO) { withTimeout(5_000) { invalidated.await() } }
        assertThat(source.invalid).isTrue()
        val refreshed = page(db.movies().browseGroups(null, null)).data.single()
        assertThat(refreshed.primary.id).isEqualTo(2)
        assertThat(refreshed.variantCount).isEqualTo(2)
    }

    @Test fun `group recency uses newest variant rather than best quality and Home samples stay capped`() = runTest {
        db.movies().upsertAll(listOf(
            movieFixture(1, "Film 4K").copy(addedMillis = 1),
            movieFixture(2, "Film HD").copy(addedMillis = 20),
            movieFixture(3, "Other HD").copy(addedMillis = 10),
            movieFixture(4, "Outside HD").copy(sourceId = 2, addedMillis = 30),
        ))
        val first = page(db.movies().browseGroups(1, null), size = 1).data.single()
        assertThat(first.primary.id).isEqualTo(1)
        assertThat(first.newestAddedMillis).isEqualTo(20)
        assertThat(first.variantCount).isEqualTo(2)
        assertThat(db.movies().browseFeedSample(1, 2).map { it.id }).containsExactly(2L, 3L).inOrder()
    }
}
