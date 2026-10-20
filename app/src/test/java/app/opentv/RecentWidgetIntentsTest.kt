package app.opentv.widget

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.MainActivity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RecentWidgetIntentsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `explicit ID only launch intents are unique and have no credentials or URLs`() {
        val first = RecentWidgetIntents.intent(context, 41)
        val second = RecentWidgetIntents.intent(context, 42)
        assertThat(first.component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(first.getLongExtra(MainActivity.EXTRA_PLAY_CHANNEL, 0)).isEqualTo(41L)
        assertThat(first.extras!!.keySet()).containsExactly(MainActivity.EXTRA_PLAY_CHANNEL)
        assertThat(first.dataString).isEqualTo("opentv-widget://recent/41")
        assertThat(first.filterEquals(second)).isFalse()
        assertThat(RecentWidgetIntents.pending(context, 41)).isNotEqualTo(RecentWidgetIntents.pending(context, 42))
        assertThat(shadowOf(RecentWidgetIntents.pending(context, 41)).isImmutable).isTrue()
    }

    @Test fun `empty state opens app without synthesizing playback request`() {
        val home = RecentWidgetIntents.intent(context)
        assertThat(home.component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(home.hasExtra(MainActivity.EXTRA_PLAY_CHANNEL)).isFalse()
        assertThat(home.filterEquals(RecentWidgetIntents.intent(context, 1))).isFalse()
    }
}
