package app.gamenative.assistant

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.view.Display
import app.gamenative.BuildConfig
import app.gamenative.PluviaApp
import app.gamenative.powercontrol.PowerManager
import app.gamenative.powercontrol.metrics.MetricsSnapshot
import com.winlator.container.Container
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Only observes the existing collector. All persistent work is isolated from the input/render path. */
object GameOptimizationSession {
    internal val capture = OptimizationCapture(SystemClock::elapsedRealtime, System::currentTimeMillis)
    private data class Launch(val context: Context, val game: String, val token: String, val config: File,
        val configuration: String, val settings: String, val debug: Boolean)
    private var launch: Launch? = null
    private var environment: String = ""
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var saveJob: Job? = null
    @Volatile private var saveFailure: Pair<String, String>? = null
    fun saveError(game: String): String? = saveFailure?.takeIf { it.first == game }?.second
    fun store(context: Context, game: String) = GameOptimizationStore(File(context.noBackupFilesDir, "assistant/optimization"), game)

    @JvmStatic @Synchronized fun attach(context: Context, game: String, container: Container, debug: Boolean) {
        if (!BuildConfig.AI_ASSISTANT_ENABLED) return
        // Measurement setup must never prevent a game from launching.
        launch = runCatching {
            val token = requireNotNull(LiveGameSession.view(game)?.token)
            check(container.configFile.length() in 1..2_000_000)
            val config = JSONObject(container.configFile.readText())
            val safe = JSONObject()
            GameSettingCatalog.settings.forEach { safe.put(it.id, it.current(config)) }
            listOf("graphicsDriver", "graphicsDriverVersion", "dxwrapper", "dxwrapperConfig", "emulator", "fexcoreVersion", "fexcorePreset", "wineVersion", "containerVariant").forEach {
                safe.put(it, DiagnosticRedactor.text(config.optString(it, "default")).take(1000))
            }
            Launch(context.applicationContext, game, token, container.configFile, GameOptimizationStore.fingerprint(config), safe.toString(), debug)
        }.getOrNull()
        capture.reset()
    }
    @Synchronized fun start(context: Context, game: String, target: Int, scene: String) {
        require(scene.isNotBlank() && scene.length <= 120 && target in GameOptimizationStore.targets) { "Ange en kort scen som går att upprepa och välj FPS-mål." }
        val info = requireNotNull(launch?.takeIf { it.game == game && LiveGameSession.view(game)?.token == it.token }) { "Starta spelet med denna appversion före mätning." }
        check(PluviaApp.isActivityInForeground && LiveGameSession.view(game)?.paused == false) { "Återuppta spelet före mätning." }
        check(!info.debug) { "Använd vanlig spelstart utan diagnostikläge. Stäng även av eventuell Wine/Box64-debugloggning före jämförande mätning." }
        check(CodexDebugSession.status.value?.let { it.game == game && it.recording } != true) { "Avsluta felsökningsinsamlingen före en jämförande FPS-mätning." }
        check(GameOptimizationStore.fingerprint(JSONObject(info.config.readText())) == info.configuration) { "Konfigurationen ändrades sedan spelstart. Starta om spelet före mätning." }
        store(context, game).goal(target, scene)
        environment = environment(info.context)
        saveFailure = null
        capture.start(game, info.token, target, DiagnosticRedactor.text(scene).trim().take(120), info.configuration, info.settings, environment)
    }
    @Synchronized fun sample(token: String?, metrics: MetricsSnapshot, stride: Int) {
        val info = launch?.takeIf { it.token == token } ?: return
        if (capture.view(info.game)?.active != true) return
        val live = LiveGameSession.view(info.game)
        capture.sample(token, metrics, live?.status == "live" && !live.assistantVisible, stride)
        poll()
    }
    @Synchronized fun poll() { if (capture.due()) finish("completed") }
    @Synchronized fun stop(game: String, reason: String) { if (launch?.game == game) finish(reason) }
    @Synchronized fun interrupt(token: String?, reason: String) { if (launch?.token == token) finish(reason) }
    @Synchronized fun end() { finish("Spelomgången avslutades."); launch = null }
    private fun finish(reason: String) {
        val info = launch ?: return
        val result = capture.finish(reason) ?: return
        val expectedEnvironment = environment
        saveJob = writer.launch {
            try {
                val record = JSONObject(result)
                val issues = record.getJSONArray("issues")
                if (GameOptimizationStore.fingerprint(JSONObject(info.config.readText())) != info.configuration) issues.put("Sparad konfiguration ändrades sedan spelstart.")
                if (environment(info.context) != expectedEnvironment) issues.put("Ström- eller bildskärmsförhållanden ändrades under mätningen.")
                record.put("usable", issues.length() == 0)
                store(info.context, info.game).add(record)
            } catch (e: Exception) {
                saveFailure = info.game to "Mätningen kunde inte sparas. Tidigare historik är bevarad."
            }
        }
    }
    suspend fun awaitSaved() { saveJob?.join() }

    private fun environment(context: Context): String {
        val refresh = runCatching { context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).refreshRate }.getOrNull()
        val charging = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) }.getOrNull()
        val power = runCatching { PowerManager.currentProfile }.getOrNull()
        val powerSave = runCatching { context.getSystemService(android.os.PowerManager::class.java).isPowerSaveMode }.getOrNull()
        return JSONObject().put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("android", Build.VERSION.SDK_INT)
            .put("app", BuildConfig.VERSION_NAME).put("displayHz", refresh?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "unknown")
            .put("plugged", charging ?: JSONObject.NULL).put("powerSave", powerSave ?: JSONObject.NULL)
            .put("powerProfile", power?.let { JSONObject().put("enabled", it.enablePowerControl).put("adaptiveCap", it.adaptiveFpsCapEnabled)
                .put("autoTuning", it.enableAutoTuning).put("perCluster", it.enablePerClusterTuning).put("strategy", it.tuningStrategy.name)
                .put("governor", it.governor.name).put("minCpu", it.minCpuFreq).put("maxCpu", it.maxCpuFreq)
                .put("minGpu", it.minGpuPowerLevel).put("maxGpu", it.maxGpuPowerLevel) } ?: JSONObject.NULL).toString()
    }
}
