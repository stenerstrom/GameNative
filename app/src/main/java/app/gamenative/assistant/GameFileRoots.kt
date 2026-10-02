package app.gamenative.assistant

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.mods.ModContainerResolver
import app.gamenative.service.SteamService
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGService
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.CustomGameScanner
import java.io.File
import java.nio.file.Files

/** Read-only discovery from GameNative's own game metadata. Never creates/migrates a container. */
object GameFileRoots {
    fun discover(context: Context, appId: String): List<GameTextFiles.Root> {
        val result = mutableListOf<GameTextFiles.Root>()
        val gamePath = runCatching {
            val id = ContainerUtils.extractGameIdFromContainerId(appId)
            when (ContainerUtils.extractGameSourceFromContainerId(appId)) {
                GameSource.STEAM -> {
                    val installed = SteamService.getInstalledApp(id)
                    val info = SteamService.getAppInfoOf(id)
                    // An empty Steam name resolves to the entire library, which must never become a root.
                    if (installed?.isImported == true) installed.customInstallPath
                    else if (!info?.name.isNullOrBlank()) SteamService.getAppDirPath(id) else null
                }
                GameSource.GOG -> GOGService.getInstallPath(id.toString())
                GameSource.EPIC -> EpicService.getInstallPath(id)
                GameSource.AMAZON -> AmazonService.getInstallPathByAppId(id)
                GameSource.CUSTOM_GAME -> CustomGameScanner.getFolderPathFromAppId(appId)
            }
        }.getOrNull()
        gamePath?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf {
            it.isAbsolute && it.isDirectory && it.canonicalFile.parentFile?.parentFile != null && !Files.isSymbolicLink(it.toPath())
        }?.let { result += GameTextFiles.Root("game", "Spelmapp", it) }

        val prefix = File(ModContainerResolver.getWinePrefix(context, appId))
        val users = File(prefix, "drive_c/users")
        // Wine often links Documents to shared Android storage. Do not follow those links.
        fun privateDirectory(file: File): Boolean {
            if (!file.isDirectory || !file.canonicalFile.toPath().startsWith(prefix.canonicalFile.toPath())) return false
            var current: File? = file
            while (current != null && current != prefix.parentFile) {
                if (Files.isSymbolicLink(current.toPath())) return false
                current = current.parentFile
            }
            return true
        }
        if (privateDirectory(users)) users.listFiles().orEmpty().sortedBy { it.name }.take(16).forEach { user ->
            if (!privateDirectory(user) || user.name.equals("Public", true)) return@forEach
            listOf("Documents", "AppData/Roaming", "AppData/Local", "AppData/LocalLow").forEach { path ->
                val directory = File(user, path)
                if (privateDirectory(directory)) result += GameTextFiles.Root("wine/${user.name}/$path", "Wine/$path", directory)
            }
        }
        return result
    }
}
