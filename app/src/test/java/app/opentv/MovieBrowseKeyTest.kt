package app.opentv.data.repo

import app.opentv.data.model.Movie
import app.opentv.data.parser.MovieBrowseKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

internal fun movieFixture(id: Long, name: String, year: Int? = null) = Movie(
    id = id, sourceId = 1, streamId = "$id", name = name, year = year,
    categoryId = "films", posterUrl = null, rating = null, plot = null,
    durationSeconds = null, containerExtension = "mkv", streamUrl = "https://example.com/$id",
)

class MovieBrowseKeyTest {
    @Test fun `year field takes precedence and every embedded year is stripped`() {
        assertThat(MovieBrowseKey.from("Film 1999 2001 HD", 2020).groupKey)
            .isEqualTo(MovieBrowseKey.from("Film 4K", 2020).groupKey)
        assertThat(MovieBrowseKey.from("Film 1999 HD", null).groupKey)
            .isNotEqualTo(MovieBrowseKey.from("Film 2001 HD", null).groupKey)
    }

    @Test fun `unknown years merge across providers but editions remain separate`() {
        val films = listOf(
            movieFixture(1, "Film HD"),
            movieFixture(2, "Film 4K").copy(sourceId = 2, categoryId = "other"),
            movieFixture(3, "Film IMAX HD"),
            movieFixture(4, "Film EXTENDED HD"),
            movieFixture(5, "Film 3D HD"),
        )
        val groups = collapseMovieVariants(films)
        assertThat(groups).hasSize(4)
        assertThat(groups.first().primary.id).isEqualTo(2)
        assertThat(groups.first().variants.map { it.movie.id }).containsExactly(2L, 1L).inOrder()
    }

    @Test fun `group order is first appearance and equal quality is sorted by raw name`() {
        val films = listOf(movieFixture(1, "Zed HD"), movieFixture(2, "Film HD"), movieFixture(3, "Film FHD"))
        val groups = collapseMovieVariants(films)
        assertThat(groups.map { it.primary.id }).containsExactly(1L, 3L).inOrder()
        val equal = collapseMovieVariants(listOf(movieFixture(4, "Film HD"), movieFixture(5, "Film  HD")))
        assertThat(equal.single().variants.map { it.movie.id }).containsExactly(5L, 4L).inOrder()
    }
}
