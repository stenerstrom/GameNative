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
        app.gamenative.PrefManager.init(context)
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
    @Test fun controllerProposalUsesRealContainerAndSettingsUiValuesAndRestoresAfterReopening() {
        val initial = ContainerUtils.getContainer(context, game)
        initial.inputType = 1 // DirectInput only, as stored by the Controller tab.
        initial.dinputMapperType = 1
        initial.isSdlControllerAPI = true
        initial.putExtra("useSteamInput", true)
        assertTrue(initial.saveDataChecked())
        val original = initial.configFile.readText()
        val tools = GameAssistantTools(context, game)
        val snapshot = tools.readDiagnostics()
        val controller = JSONObject(snapshot.text).getJSONObject("configuration").getJSONObject("controller")
        assertEquals("DINPUT", controller.getString("inputApi"))
        assertEquals("STANDARD", controller.getString("directInputMapper"))
        assertTrue(controller.getBoolean("sdlControllerAPI"))
        assertTrue(controller.getBoolean("useSteamInput"))
        assertEquals(JSONObject.NULL, JSONObject(snapshot.text).get("logModifiedAtMs"))
        tools.applyValidated(ConfigProposal(null, null, "Enable XInput and test mapper", ControllerInputApi.BOTH, DirectInputMapper.XINPUT), snapshot.hash)
        val changed = ContainerUtils.getContainer(context, game)
        assertEquals(3, changed.inputType)
        assertEquals(2.toByte(), changed.dinputMapperType)
        val ui = ContainerUtils.toContainerData(changed)
        assertTrue(ui.enableXInput)
        assertTrue(ui.enableDInput)
        assertEquals(2.toByte(), ui.dinputMapperType)
        assertTrue(ui.sdlControllerAPI)
        assertTrue(ui.useSteamInput)
        assertEquals("1920x1080", changed.screenSize)
        assertEquals("", changed.getExtra("fpsLimiterTarget"))
        assertEquals("SECRET_TOKEN=do-not-send", changed.envVars)
        GameAssistantTools(context, game).restore()
        assertEquals(JSONObject(original).toString(), JSONObject(ContainerUtils.getContainer(context, game).configFile.readText()).toString())
    }
    @Test fun allInputApiChoicesMatchGameNativesControllerSwitches() {
        val tools = GameAssistantTools(context, game)
        ControllerInputApi.entries.forEach { api ->
            val container = ContainerUtils.getContainer(context, game)
            container.inputType = api.nativeApi.ordinal
            assertTrue(container.saveDataChecked())
            val snapshot = JSONObject(tools.readDiagnostics().text).getJSONObject("configuration").getJSONObject("controller")
            assertEquals(api.name, snapshot.getString("inputApi"))
            val ui = ContainerUtils.toContainerData(ContainerUtils.getContainer(context, game))
            assertEquals(api == ControllerInputApi.XINPUT || api == ControllerInputApi.BOTH, ui.enableXInput)
            assertEquals(api == ControllerInputApi.DINPUT || api == ControllerInputApi.BOTH, ui.enableDInput)
        }
    }
    @Test fun diagnosticsRespectLoaderDefaultsAndReportUnknownEnumValuesAsUnknown() {
        val config = ContainerUtils.getContainer(context, game).configFile
        val json = JSONObject(config.readText()).apply { remove("inputType"); remove("dinputMapperType") }
        config.writeText(json.toString())
        fun controller() = JSONObject(GameAssistantTools(context, game).readDiagnostics().text).getJSONObject("configuration").getJSONObject("controller")
        assertEquals("BOTH", controller().getString("inputApi"))
        assertEquals("STANDARD", controller().getString("directInputMapper"))
        config.writeText(json.put("inputType", 99).put("dinputMapperType", 99).toString())
        assertEquals(JSONObject.NULL, controller().get("inputApi"))
        assertEquals(JSONObject.NULL, controller().get("directInputMapper"))
    }

    @Test fun expandedSettingsRoundTripThroughRealContainerAndUndoPreservesOtherValues() {
        val tools = GameAssistantTools(context, game)
        GameSettingCatalog.settings.forEach { setting ->
            val before = ContainerUtils.getContainer(context, game).configFile.readText()
            val json = JSONObject(before)
            val target = setting.choices.first { it != setting.current(json) }
            val snapshot = tools.readDiagnostics()
            tools.applyValidated(ConfigProposal(null, null, "Test ${setting.id}", settings = mapOf(setting.id to target)), snapshot.hash)
            val loaded = ContainerUtils.getContainer(context, game)
            val data = ContainerUtils.toContainerData(loaded)
            val value: Any = when (setting.id) {
                "screenSize" -> data.screenSize
                "fpsLimiterEnabled" -> loaded.getExtra("fpsLimiterEnabled").toBoolean()
                "fpsLimiterTarget" -> loaded.getExtra("fpsLimiterTarget").toInt()
                "inputApi" -> loaded.inputType
                "directInputMapper" -> data.dinputMapperType.toInt()
                "sdlControllerAPI" -> data.sdlControllerAPI
                "useSteamInput" -> data.useSteamInput
                "disableMouseInput" -> data.disableMouseInput
                "touchscreenMode" -> data.touchscreenMode
                "shooterMode" -> data.shooterMode
                "vibrationIntensity" -> data.vibrationIntensity.toString()
                "audioDriver" -> data.audioDriver
                "pulseaudioLowLatency" -> data.pulseaudioLowLatency
                "rendererPresentMode" -> data.rendererPresentMode
                "box64Preset" -> data.box64Preset
                "useDRI3" -> data.useDRI3
                "portraitMode" -> data.portraitMode
                "externalDisplayMode" -> data.externalDisplayMode
                "externalDisplaySwap" -> data.externalDisplaySwap
                "suspendPolicy" -> data.suspendPolicy
                else -> error("Untested setting ${setting.id}")
            }
            assertEquals(setting.id, setting.decode(target), value)
            assertEquals("SECRET_TOKEN=do-not-send", loaded.envVars)
            GameAssistantTools(context, game).restore()
            assertEquals(setting.id, JSONObject(before).toString(), JSONObject(loaded.configFile.readText()).toString())
        }
    }

    @Test fun explicitKeepAllowsNextChangeButRetainsBackupIfConfigurationChangedElsewhere() {
        val tools = GameAssistantTools(context, game)
        tools.applyValidated(ConfigProposal(30, null, "test"), tools.readDiagnostics().hash)
        tools.keepChanges()
        assertFalse(tools.hasBackup())
        tools.applyValidated(ConfigProposal(40, null, "test"), tools.readDiagnostics().hash)
        val container = ContainerUtils.getContainer(context, game)
        container.putExtra("fpsLimiterTarget", 45)
        assertTrue(container.saveDataChecked())
        assertTrue(runCatching { tools.keepChanges() }.isFailure)
        assertTrue(tools.hasBackup())
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
        val updater = manager.getActivityInfo(android.content.ComponentName(context, app.gamenative.updates.AiDevUpdateActivity::class.java), 0)
        assertFalse(updater.exported)
        val providers = manager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PROVIDERS).providers.orEmpty()
        assertTrue(providers.any { it.authority == "$expectedPackage.fileprovider" })
        if (app.gamenative.BuildConfig.AI_ASSISTANT_ENABLED) assertFalse(providers.any { it.authority == "app.gamenative.fileprovider" })
    }

    @Test fun rejectsTraversalGameIdentifier() {
        assertTrue(runCatching { GameAssistantTools(context, "../other") }.isFailure)
    }
}
