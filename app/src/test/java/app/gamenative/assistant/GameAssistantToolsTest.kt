package app.gamenative.assistant

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.gamenative.service.SteamService
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.DebugReportUtils
import com.winlator.container.Container
import com.winlator.xenvironment.ImageFs
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GameAssistantToolsTest {
    private lateinit var context: Context
    private val game = "STEAM_42"
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        ImageFs::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true; set(null, null) }
        SteamService.keepAlive = false
        val root = File(ImageFs.find(context).rootDir, "home/xuser-$game").apply { mkdirs() }
        val container = Container(game).apply { setRootDir(root); screenSize = "1920x1080"; envVars = "SECRET_TOKEN=do-not-send" }
        assertTrue(container.saveDataChecked())
    }
    @Test fun readsExistingGameLogAndConfigThenAppliesAndRestoresThroughContainerLoader() {
        val log = DebugReportUtils.wineLogFile(context, game)
        log.parentFile!!.mkdirs()
        log.writeText("DXVK: shader compilation\npassword=do-not-send\n")
        val tools = GameAssistantTools(context, game)
        val snapshot = tools.readDiagnostics()
        assertTrue(snapshot.text.contains("shader compilation"))
        assertFalse(snapshot.text.contains("do-not-send"))
        assertFalse(snapshot.text.contains("SECRET_TOKEN"))
        assertTrue(snapshot.text.contains("1920x1080"))
        tools.applyValidated(ConfigProposal(30, "1280x720", "test"), snapshot.hash)
        val changed = ContainerUtils.getContainer(context, game)
        assertEquals("1280x720", changed.screenSize)
        assertEquals("30", changed.getExtra("fpsLimiterTarget"))
        assertEquals("SECRET_TOKEN=do-not-send", changed.envVars)
        GameAssistantTools(context, game).restore()
        assertEquals("1920x1080", ContainerUtils.getContainer(context, game).screenSize)
        assertFalse(tools.hasBackup())
    }
    @Test fun missingGameLogNeverFallsBackToAnotherGamesLog() {
        val other = DebugReportUtils.wineLogFile(context, "STEAM_99")
        other.parentFile!!.mkdirs()
        other.writeText("other-game-private-content")
        val text = GameAssistantTools(context, game).readDiagnostics().text
        assertFalse(text.contains("other-game-private-content"))
        assertEquals(JSONObject.NULL, JSONObject(text).get("logModifiedAtMs"))
    }
    @Test fun refusesLiveGameChanges() {
        val tools = GameAssistantTools(context, game)
        val snapshot = tools.readDiagnostics()
        SteamService.keepAlive = true
        try {
            assertTrue(runCatching { tools.applyValidated(ConfigProposal(30, null, "test"), snapshot.hash) }.isFailure)
            assertFalse(tools.hasBackup())
        } finally { SteamService.keepAlive = false }
    }
    @Test fun manifestHasCorrectIdentityProviderAndEnabledLauncher() {
        val expectedPackage = if (app.gamenative.BuildConfig.AI_ASSISTANT_ENABLED) "app.gamenative.aidev" else "app.gamenative"
        assertEquals(expectedPackage, context.packageName)
        val manager = context.packageManager
        if (app.gamenative.BuildConfig.AI_ASSISTANT_ENABLED) assertEquals("GameNative AI Dev", manager.getApplicationLabel(context.applicationInfo).toString())
        val launchers = manager.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), 0)
        assertTrue(launchers.any { it.activityInfo.name == "app.gamenative.MainActivityAliasDefault" })
        val assistant = manager.getActivityInfo(android.content.ComponentName(context, GameAssistantActivity::class.java), 0)
        assertFalse(assistant.exported)
        val providers = manager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PROVIDERS).providers.orEmpty()
        assertTrue(providers.any { it.authority == "$expectedPackage.fileprovider" })
        if (app.gamenative.BuildConfig.AI_ASSISTANT_ENABLED) assertFalse(providers.any { it.authority == "app.gamenative.fileprovider" })
    }

    @Test fun rejectsTraversalGameIdentifier() {
        assertTrue(runCatching { GameAssistantTools(context, "../other") }.isFailure)
    }
}
