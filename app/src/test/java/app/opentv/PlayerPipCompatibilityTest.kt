package app.opentv

import android.app.Application
import android.content.pm.PackageManager
import app.opentv.core.PipState
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 25], application = Application::class)
class PlayerPipCompatibilityTest {
    @Test fun `pre Oreo devices never enter PiP even with playback and advertised feature`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        val owner = Any()
        val packageManager = shadowOf(activity.packageManager)
        packageManager.setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        try {
            PipState.setActivityVisible(true)
            PipState.attach(owner) {}
            PipState.update(owner, true, 16f / 9f)
            assertThat(PipState.eligible).isTrue()
            assertThat(PipState.isPlaying).isTrue()
            assertThat(activity.pipSupported()).isFalse()
            activity.enterPipNow()
            activity.maybeEnterPip()
            assertThat(PipState.inPip.value).isFalse()
        } finally {
            PipState.detach(owner)
            PipState.setActivityVisible(false)
            packageManager.setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, false)
        }
    }
}
