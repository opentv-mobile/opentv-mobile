package app.opentv

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.opentv.core.AppSettings
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class NavigationRotationSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE)

    @Before fun resetSettings() {
        // AppSettings is process-scoped; each scenario must simulate a fresh app launch.
        ReflectionHelpers.setStaticField(AppSettings::class.java, "instance", null)
        prefs.edit().clear().commit()
    }

    @After fun clearSettings() {
        ReflectionHelpers.setStaticField(AppSettings::class.java, "instance", null)
        prefs.edit().clear().commit()
    }

    @Test fun `absent old default follows system without persisting an explicit choice`() {
        assertThat(prefs.contains("phone_auto_rotate")).isFalse()
        assertThat(AppSettings.get(context).phoneAutoRotate.value).isTrue()
        assertThat(prefs.contains("phone_auto_rotate")).isFalse()
    }

    @Test fun `explicitly disabled rotation is preserved on upgrade`() {
        prefs.edit().putBoolean("phone_auto_rotate", false).commit()
        assertThat(AppSettings.get(context).phoneAutoRotate.value).isFalse()
        assertThat(prefs.getBoolean("phone_auto_rotate", true)).isFalse()
    }

    @Test fun `explicitly enabled rotation is preserved on upgrade`() {
        prefs.edit().putBoolean("phone_auto_rotate", true).commit()
        assertThat(AppSettings.get(context).phoneAutoRotate.value).isTrue()
    }

    @Test fun `toggle updates navigation preference and persists explicit portrait choice`() {
        val settings = AppSettings.get(context)
        settings.setPhoneAutoRotate(false)
        assertThat(settings.phoneAutoRotate.value).isFalse()
        assertThat(prefs.contains("phone_auto_rotate")).isTrue()
        assertThat(prefs.getBoolean("phone_auto_rotate", true)).isFalse()
        settings.setPhoneAutoRotate(true)
        assertThat(settings.phoneAutoRotate.value).isTrue()
        assertThat(prefs.getBoolean("phone_auto_rotate", false)).isTrue()
    }
}
