package app.gamenative.assistant

import android.app.Application
import android.content.Context
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PluviaApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Robolectric's libc setenv/getenv are no-ops; capture that boundary explicitly. */
@Implements(Os::class)
class ShadowControllerEnvironment {
    companion object {
        private val values = mutableMapOf<String, String>()
        @JvmStatic @Implementation fun setenv(name: String, value: String, overwrite: Boolean) {
            if (overwrite || name !in values) values[name] = value
        }
        @JvmStatic @Implementation fun getenv(name: String): String? = values[name]
        @JvmStatic @Implementation fun unsetenv(name: String) { values.remove(name) }
    }
}

/** Application.attach runs before providers/onCreate can load WinHandler and libevshim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [ShadowControllerEnvironment::class])
class ControllerNativeEnvironmentTest {
    private val previous = Os.getenv("EVSHIM_BASE_PATH")
    @After fun cleanup() {
        if (previous == null) Os.unsetenv("EVSHIM_BASE_PATH") else Os.setenv("EVSHIM_BASE_PATH", previous, true)
    }

    @Test fun attachingApplicationSetsNativeHostPathBeforeOnCreate() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        // Reproduce the old library fallback and make sure the fork replaces it early.
        Os.setenv("EVSHIM_BASE_PATH", "/data/data/app.gamenative/files", true)
        ReflectionHelpers.callInstanceMethod<Void>(PluviaApp(), "attach", ClassParameter.from(Context::class.java, context))
        assertEquals(context.filesDir.absolutePath, Os.getenv("EVSHIM_BASE_PATH"))
    }
}
