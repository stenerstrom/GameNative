package app.gamenative.assistant

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import app.gamenative.data.*
import app.gamenative.db.PluviaDatabase
import app.gamenative.db.dao.ModDao
import app.gamenative.mods.*
import app.gamenative.service.SteamService
import dagger.hilt.internal.GeneratedComponent
import dagger.hilt.internal.GeneratedComponentManager
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class ModAssistantTestApplication : Application(), GeneratedComponentManager<Any> {
    lateinit var testDatabase: PluviaDatabase
    override fun generatedComponent(): Any = object : GeneratedComponent, NexusModManager.ModDaoEntryPoint {
        override fun modDao(): ModDao = testDatabase.modDao()
        override fun database() = testDatabase
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ModAssistantTestApplication::class)
class GameModToolsTest {
    private lateinit var app: ModAssistantTestApplication
    private lateinit var root: File
    private lateinit var mod: ModInstall
    private lateinit var original: ByteArray
    private val game = "STEAM_42"
    private val dao get() = app.testDatabase.modDao()
    private fun tools() = GameModTools(app, game, "Test game") { root }
    @Before fun setup() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        app.testDatabase = Room.inMemoryDatabaseBuilder(app, PluviaDatabase::class.java).allowMainThreadQueries().build()
        PrefManager.init(app); SteamService.keepAlive = false
        root = File(app.filesDir, "game").apply { mkdirs() }
        original = "FPS=60\r\n".toByteArray()
        File(root, "settings.ini").writeBytes(original)
        val extracted = File(NexusModManager.cacheRoot(app, game), "extracted/local_test").apply { mkdirs() }
        File(extracted, "settings.ini").writeText("FPS=30\r\n")
        File(extracted, "dinput8.dll").writeBytes(byteArrayOf(0x4d, 0x5a, 0, 1, 2, 3))
        File(extracted, "README.txt").writeText("Copy these files into the game directory. password=do-not-send")
        mod = ModInstall("local_test", game, ModInstallSource.LOCAL_ARCHIVE.name, modName = "Test loader", fileName = "test.zip",
            archivePath = "", extractedPath = extracted.path, metadataJson = "{\"authorization\":\"do-not-send\"}")
        dao.upsertInstall(mod)
    }
    @After fun cleanup() { SteamService.keepAlive = false; app.testDatabase.close() }
    private suspend fun installArgs(tools: GameModTools): JSONObject {
        val data = JSONObject(tools.inspect(mod.installId))
        val plans = data.getJSONArray("installationPlans")
        val choice = (0 until plans.length()).map { plans.getJSONObject(it) }.first { it.getString("label").contains("behåll paketets") }
        return JSONObject().put("mod_id", mod.installId).put("action", "install").put("plan_id", choice.getString("plan_id")).put("reason", "Test this mod")
    }
    private fun disableArgs() = JSONObject().put("mod_id", mod.installId).put("action", "disable").put("plan_id", "").put("reason", "Undo the loader")
    private suspend fun applyMod(): GameModTools {
        val tools = tools(); tools.prepare(installArgs(tools)); tools.apply(true) {}; return tools
    }

    @Test fun previewThenNativeDeploymentAndReopenedUndoRestoreBinaryAndOriginalConfig() = runBlocking {
        val tools = tools()
        val preview = tools.prepare(installArgs(tools))
        assertTrue(preview.needsLoaderApproval)
        assertEquals(3, preview.fileCount)
        assertArrayEquals(original, File(root, "settings.ini").readBytes())
        assertFalse(File(root, "dinput8.dll").exists())
        assertFalse(tools.hasBackup())
        assertTrue(runCatching { tools.apply(false) {} }.isFailure)
        assertFalse(tools.hasBackup())
        tools.apply(true) {}
        assertEquals(ModInstallStatus.APPLIED.name, dao.getInstall(mod.installId)!!.status)
        assertArrayEquals(File(mod.extractedPath, "dinput8.dll").readBytes(), File(root, "dinput8.dll").readBytes())
        assertEquals("FPS=30\r\n", File(root, "settings.ini").readText())
        assertTrue(tools.hasBackup())
        assertTrue(ModDeploymentVerifier.verify(ModOwnershipStore.read(NexusModManager.cacheRoot(app, game), mod.installId)!!).successful)
        tools().restore()
        assertArrayEquals(original, File(root, "settings.ini").readBytes())
        assertFalse(File(root, "dinput8.dll").exists())
        assertEquals(ModInstallStatus.READY.name, dao.getInstall(mod.installId)!!.status)
        assertTrue(dao.getRecipesForInstall(mod.installId).isEmpty())
        assertFalse(tools.hasBackup())
        assertNull(ModOwnershipStore.read(NexusModManager.cacheRoot(app, game), mod.installId))
    }
    @Test fun disableRestoresOriginalAndUndoReenablesTrackedModAfterReopening() = runBlocking {
        applyMod().keep()
        val tools = tools(); tools.inspect(mod.installId)
        val preview = tools.prepare(disableArgs())
        assertFalse(preview.needsLoaderApproval)
        tools.apply(false) {}
        assertEquals(ModInstallStatus.DISABLED.name, dao.getInstall(mod.installId)!!.status)
        assertArrayEquals(original, File(root, "settings.ini").readBytes())
        assertFalse(File(root, "dinput8.dll").exists())
        tools().restore()
        assertEquals(ModInstallStatus.APPLIED.name, dao.getInstall(mod.installId)!!.status)
        assertEquals("FPS=30\r\n", File(root, "settings.ini").readText())
        assertTrue(File(root, "dinput8.dll").exists())
        assertTrue(ModDeploymentVerifier.verify(ModOwnershipStore.read(NexusModManager.cacheRoot(app, game), mod.installId)!!).successful)
    }
    @Test fun inventoryDocumentsAndToolsStayInsideSelectedGameAndFilterCredentials() = runBlocking {
        dao.upsertInstall(mod.copy(installId = "other", appId = "STEAM_99", modName = "other-game-private"))
        val tools = tools()
        assertFalse(tools.inventory().contains("do-not-send"))
        assertFalse(tools.inventory().contains("other-game-private"))
        assertTrue(runCatching { tools.inspect("other") }.isFailure)
        tools.inspect(mod.installId)
        assertFalse(tools.document(mod.installId, "README.txt").contains("do-not-send"))
        assertTrue(runCatching { tools.document(mod.installId, "../outside.txt") }.isFailure)
        assertTrue(runCatching { tools.document(mod.installId, "dinput8.dll") }.isFailure)
        assertTrue(runCatching { tools.prepare(disableArgs().put("action", "delete_everything")) }.isFailure)
    }
    @Test fun sourceTargetProfileChangesAndRunningGameBlockTheWrite() = runBlocking {
        val tools = tools()
        tools.prepare(installArgs(tools))
        SteamService.keepAlive = true
        assertTrue(runCatching { tools.apply(true) {} }.isFailure)
        SteamService.keepAlive = false
        File(mod.extractedPath, "dinput8.dll").appendBytes(byteArrayOf(4))
        assertTrue(runCatching { tools.apply(true) {} }.isFailure)
        tools.beginTurn(); tools.prepare(installArgs(tools))
        File(root, "settings.ini").writeText("changed elsewhere")
        assertTrue(runCatching { tools.apply(true) {} }.isFailure)
        assertFalse(tools.hasBackup())
        assertEquals("changed elsewhere", File(root, "settings.ini").readText())
        assertFalse(File(root, "dinput8.dll").exists())
    }
    @Test fun undoAndKeepRetainRecoveryOnExternalFileChanges() = runBlocking {
        val tools = applyMod()
        File(root, "settings.ini").writeText("game changed the settings")
        assertTrue(runCatching { tools().restore() }.isFailure)
        assertTrue(runCatching { tools.keep() }.isFailure)
        assertTrue(tools.hasBackup())
        assertEquals("game changed the settings", File(root, "settings.ini").readText())
    }
    @Test fun symlinkedPackageAndUnknownPlanCannotBeInstalled() = runBlocking {
        val tools = tools()
        val args = installArgs(tools)
        assertTrue(runCatching { tools.prepare(args.put("plan_id", "invented")) }.isFailure)
        val other = File(app.filesDir, "other.ini").apply { writeText("secret") }
        Files.createSymbolicLink(File(mod.extractedPath, "linked.ini").toPath(), other.toPath())
        assertTrue(runCatching { tools.inspect(mod.installId) }.isFailure)
    }
    @Test fun overlappingTrackedModRequiresNativeProfileOrderInsteadOfBreakingOwnership() = runBlocking {
        applyMod().keep()
        val another = mod.copy(installId = "local_other", modName = "Another mod", status = ModInstallStatus.READY.name)
        dao.upsertInstall(another)
        val tools = tools()
        val inspected = JSONObject(tools.inspect(another.installId)).getJSONArray("installationPlans").getJSONObject(0)
        val args = JSONObject().put("mod_id", another.installId).put("action", "install").put("plan_id", inspected.getString("plan_id")).put("reason", "test conflict")
        assertTrue(runCatching { tools.prepare(args) }.isFailure)
        assertEquals(ModInstallStatus.READY.name, dao.getInstall(another.installId)!!.status)
    }
    @Test fun backupWriteFailurePreventsAnyGameFileChange() = runBlocking {
        val tools = tools(); tools.prepare(installArgs(tools))
        val parent = File(app.noBackupFilesDir, "assistant/mod-undo")
        parent.parentFile!!.mkdirs(); parent.writeText("not a directory")
        assertTrue(runCatching { tools.apply(true) {} }.isFailure)
        assertArrayEquals(original, File(root, "settings.ini").readBytes())
        assertFalse(File(root, "dinput8.dll").exists())
    }

    @Test fun identicalFileInstallationUndoStillRestoresOwnershipAndProfileMetadata() = runBlocking {
        File(mod.extractedPath).listFiles()!!.filter { it.name != "settings.ini" }.forEach { it.delete() }
        File(mod.extractedPath, "settings.ini").writeBytes(original)
        val tools = applyMod()
        assertArrayEquals(original, File(root, "settings.ini").readBytes())
        assertEquals(ModInstallStatus.APPLIED.name, dao.getInstall(mod.installId)!!.status)
        tools().restore()
        assertArrayEquals(original, File(root, "settings.ini").readBytes())
        assertEquals(ModInstallStatus.READY.name, dao.getInstall(mod.installId)!!.status)
        assertNull(ModOwnershipStore.read(NexusModManager.cacheRoot(app, game), mod.installId))
        assertFalse(tools.hasBackup())
    }

    @Test fun changedProfileBetweenPreviewAndApplyAndAfterApplyPreservesUserChanges() = runBlocking {
        val profile = ModProfileManager.ensureActiveProfile(dao, game)
        ModProfileManager.ensureStateForInstall(dao, profile, mod.installId)
        val tools = tools(); tools.prepare(installArgs(tools))
        dao.upsertProfileInstallState(ModProfileInstallState(profile.profileId, mod.installId, game, false, 99))
        assertTrue(runCatching { tools.apply(true) {} }.isFailure)
        assertFalse(tools.hasBackup())
        tools.beginTurn(); tools.prepare(installArgs(tools)); tools.apply(true) {}
        dao.upsertProfileInstallState(ModProfileInstallState(profile.profileId, mod.installId, game, true, 123))
        assertTrue(runCatching { tools().restore() }.isFailure)
        assertTrue(tools.hasBackup())
        assertEquals("FPS=30\r\n", File(root, "settings.ini").readText())
    }

    @Test fun undoDisableCannotInstallChangedCachedPackage() = runBlocking {
        applyMod().keep()
        val tools = tools(); tools.inspect(mod.installId); tools.prepare(disableArgs()); tools.apply(false) {}
        File(mod.extractedPath, "dinput8.dll").appendBytes(byteArrayOf(99))
        assertTrue(runCatching { tools().restore() }.isFailure)
        assertTrue(tools.hasBackup())
        assertFalse(File(root, "dinput8.dll").exists())
        assertArrayEquals(original, File(root, "settings.ini").readBytes())
    }

    @Test fun changedNativeOriginalBackupBlocksUndoBeforeTouchingInstalledFiles() = runBlocking {
        val tools = applyMod()
        val backup = dao.getOverwriteManifests(mod.installId).first { it.backupPath.isNotBlank() }
        File(backup.backupPath).writeText("corrupt backup")
        assertTrue(runCatching { tools().restore() }.isFailure)
        assertTrue(tools.hasBackup())
        assertEquals("FPS=30\r\n", File(root, "settings.ini").readText())
        assertTrue(File(root, "dinput8.dll").isFile)
    }

    @Test fun androidDocumentZipImportStagesThenInstallsThroughNativeManagerAndRestores() = runBlocking {
        val zip = File(app.cacheDir, "import-test.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { output ->
            mapOf("settings.ini" to "FPS=24\r\n".toByteArray(), "dinput8.dll" to byteArrayOf(0x4d, 0x5a, 0, 3)).forEach { (path, bytes) ->
                output.putNextEntry(java.util.zip.ZipEntry(path)); output.write(bytes); output.closeEntry()
            }
        }
        val provider = object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: android.net.Uri) = "application/zip"
            override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): android.database.Cursor {
                val columns = projection ?: arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE)
                return android.database.MatrixCursor(columns).apply { addRow(columns.map {
                    when (it) { android.provider.OpenableColumns.DISPLAY_NAME -> zip.name; android.provider.OpenableColumns.SIZE -> zip.length(); else -> null }
                }) }
            }
            override fun openFile(uri: android.net.Uri, mode: String) = android.os.ParcelFileDescriptor.open(zip, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
            override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, args: Array<out String>?) = 0
            override fun delete(uri: android.net.Uri, selection: String?, args: Array<out String>?) = 0
        }
        val authority = "app.gamenative.test.assistantmodzip"
        provider.attachInfo(app, android.content.pm.ProviderInfo().apply { this.authority = authority })
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(authority, provider)
        val uri = android.net.Uri.parse("content://$authority/import-test.zip")
        val source = LocalModImporter.inspectArchive(app, uri)
        val imported = LocalModImporter.importMod(app, LocalModImportRequest("local_zip", game, LocalModSourceType.ARCHIVE,
            "Zip mod", source.displayName, sizeBytes = source.sizeBytes), listOf(uri))
        assertEquals(ModInstallStatus.READY.name, imported.status)
        assertArrayEquals(original, File(root, "settings.ini").readBytes())
        assertFalse(File(root, "dinput8.dll").exists())
        mod = imported
        val tools = applyMod()
        assertEquals("FPS=24\r\n", File(root, "settings.ini").readText())
        assertArrayEquals(byteArrayOf(0x4d, 0x5a, 0, 3), File(root, "dinput8.dll").readBytes())
        tools().restore()
        assertArrayEquals(original, File(root, "settings.ini").readBytes())
        assertFalse(File(root, "dinput8.dll").exists())
        assertFalse(tools.hasBackup())
    }
}
