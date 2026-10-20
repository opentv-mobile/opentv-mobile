package app.opentv

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.opentv.ui.BrowsingViewport
import app.opentv.ui.PhoneLandscapeRail
import app.opentv.ui.Tab
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BrowsingViewportTest {
    private val compose = createComposeRule()

    // JVM test dependencies do not merge their manifests into the app manifest. Register
    // Compose's host only in Robolectric, before the Compose rule launches its activity.
    private val hostActivity = object : ExternalResource() {
        override fun before() {
            val context = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(context.packageManager).addActivityIfNotPresent(
                ComponentName(context, ComponentActivity::class.java),
            ).apply {
                exported = true
                theme = android.R.style.Theme_Material_Light_NoActionBar
            }
        }
    }

    @get:Rule val rules: RuleChain = RuleChain.outerRule(hostActivity).around(compose)

    @Test fun `safe bounds follow left and right navigation bars cutouts and IME without double padding`() {
        val insets = mutableStateOf(WindowInsets(0.dp))
        compose.setContent {
            Box(Modifier.fillMaxSize().testTag("window")) {
                BrowsingViewport(insets.value) {
                    // A descendant inset-aware component must not reserve the same edges again.
                    Box(Modifier.fillMaxSize().windowInsetsPadding(insets.value).testTag("content"))
                }
            }
        }
        // Physical left/top/right/bottom: portrait gesture, both landscape rotations with
        // three-button navigation opposite a cutout, bottom three-button, keyboard, and TV.
        val cases = listOf(
            listOf(0, 24, 0, 24),
            listOf(48, 24, 32, 0),
            listOf(32, 24, 48, 0),
            listOf(0, 24, 0, 48),
            listOf(48, 24, 32, 120),
            listOf(0, 0, 0, 0),
        )
        for ((left, top, right, bottom) in cases) {
            compose.runOnIdle {
                insets.value = WindowInsets(left.dp, top.dp, right.dp, bottom.dp)
            }
            val window = compose.onNodeWithTag("window").getUnclippedBoundsInRoot()
            val content = compose.onNodeWithTag("content").getUnclippedBoundsInRoot()
            assertThat(content.left).isEqualTo(window.left + left.dp)
            assertThat(content.top).isEqualTo(window.top + top.dp)
            assertThat(content.right).isEqualTo(window.right - right.dp)
            assertThat(content.bottom).isEqualTo(window.bottom - bottom.dp)
        }
    }

    @Test fun `portrait Material navigation bar does not add consumed bottom inset again`() {
        val insets = WindowInsets(bottom = 48.dp)
        compose.setContent {
            MaterialTheme {
                BrowsingViewport(insets) {
                    NavigationBar(modifier = Modifier.testTag("tabs"), windowInsets = insets) {}
                }
            }
        }
        compose.onNodeWithTag("tabs").assertHeightIsEqualTo(80.dp)
    }

    @Test fun `landscape actions are directly tappable inside safe bounds while sync prevents duplicate requests`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val search = context.getString(R.string.nav_search)
        val refresh = context.getString(R.string.phone_refresh)
        val settings = context.getString(R.string.nav_settings)
        val syncing = mutableStateOf(false)
        val calls = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(320.dp, 280.dp).testTag("window")) {
                    BrowsingViewport(WindowInsets(left = 32.dp, top = 24.dp, right = 48.dp)) {
                        PhoneLandscapeRail(
                            tabs = Tab.entries, current = Tab.LIVE, onSelect = {},
                            isSyncing = syncing.value, onRefresh = { calls.add(refresh) },
                            onOpenSearch = { calls.add(search) },
                            onOpenSettings = { calls.add(settings) },
                            onOpenProfiles = { calls.add("Test profile") },
                            activeProfileName = "Test profile",
                        )
                    }
                }
            }
        }
        val window = compose.onNodeWithTag("window").getUnclippedBoundsInRoot()
        for (label in listOf(search, refresh, "Test profile", settings)) {
            val action = compose.onNodeWithContentDescription(label)
            action.assertIsDisplayed().assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
            val bounds = action.getUnclippedBoundsInRoot()
            assertThat(bounds.left >= window.left + 32.dp).isTrue()
            assertThat(bounds.right <= window.right - 48.dp).isTrue()
            assertThat(bounds.top >= window.top + 24.dp).isTrue()
            assertThat(bounds.bottom <= window.bottom).isTrue()
            action.performClick()
        }
        compose.runOnIdle {
            assertThat(calls).containsExactly(search, refresh, "Test profile", settings).inOrder()
            syncing.value = true
        }
        compose.onNodeWithContentDescription(refresh).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithContentDescription(settings).assertIsDisplayed().performClick()
        compose.runOnIdle { assertThat(calls.last()).isEqualTo(settings) }
    }

    @Test fun `very short landscape keeps settings and last tab reachable by scrolling`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        var settingsOpened = false
        var selected = Tab.LIVE
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(320.dp, 160.dp)) {
                    BrowsingViewport(WindowInsets(top = 24.dp)) {
                        PhoneLandscapeRail(
                            tabs = Tab.entries, current = Tab.LIVE, onSelect = { selected = it },
                            isSyncing = false, onRefresh = {}, onOpenSearch = {},
                            onOpenSettings = { settingsOpened = true }, onOpenProfiles = {},
                            activeProfileName = "Test profile",
                        )
                    }
                }
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.nav_settings))
            .performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.nav_downloads))
            .performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertThat(settingsOpened).isTrue()
            assertThat(selected).isEqualTo(Tab.DOWNLOADS)
        }
    }
}
