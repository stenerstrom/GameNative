package app.gamenative.assistant

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import app.gamenative.utils.CustomGameScanner
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OfflineGameImportTest {
    private lateinit var app: Application
    private lateinit var source: File
    private lateinit var provider: OfflineDocumentsProvider
    private val uri = DocumentsContract.buildTreeDocumentUri("offline.test", "root")
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext(); PrefManager.init(app)
        SteamService.keepAlive = false; LiveGameSession.end()
        DownloadService::class.java.getDeclaredField("baseDataDirPath").apply { isAccessible = true; set(null, app.filesDir.canonicalPath) }
        DownloadService::class.java.getDeclaredField("baseExternalAppDirPath").apply { isAccessible = true; set(null, "") }
        PrefManager.useExternalStorage = false; PrefManager.externalStoragePath = ""; PrefManager.customGameManualFolders = emptySet()
        PrefManager.importCustomGameAsSteamGame = true
        CustomGameScanner.invalidateCache()
        source = File(app.cacheDir, "Offline Game").apply { mkdirs() }
        testWindowsExe(File(source, "setup.exe"))
        File(source, "setup-1.bin").writeBytes(ByteArray(200_000) { (it % 251).toByte() })
        File(source, "data").mkdir(); File(source, "data/language.bin").writeText("same relative folder")
        File(source, ".gamenative").writeText("{\"appId\":42}")
        provider = OfflineDocumentsProvider(source)
        provider.attachInfo(app, ProviderInfo().apply { authority = "offline.test" })
        ShadowContentResolver.registerProviderInternal("offline.test", provider)
    }
    @Test fun copiesInstallerAndSidecarsThenRegistersSeparateGameWithoutDeletingSource() = runBlocking {
        val existing = File(CustomGameScanner.importRootPath, "Existing").apply { mkdirs() }
        File(existing, ".gamenative").writeText("{\"appId\":42}")
        File(existing, "settings.ini").writeText("untouched")
        PrefManager.customGameManualFolders = setOf(existing.path)
        var progressCalls = 0
        val item = OfflineGameImport.copy(app, uri) {
            progressCalls++
            assertEquals(listOf("Existing"), File(CustomGameScanner.importRootPath).listFiles()!!.map { it.name })
        }
        assertTrue(progressCalls > 0); assertTrue(item.appId.startsWith("CUSTOM_GAME_")); assertNotEquals("CUSTOM_GAME_42", item.appId)
        val imported = File(CustomGameScanner.getFolderPathFromAppId(item.appId)!!)
        assertArrayEquals(File(source, "setup-1.bin").readBytes(), File(imported, "setup-1.bin").readBytes())
        assertArrayEquals(File(source, "setup.exe").readBytes(), File(imported, "setup.exe").readBytes())
        assertEquals("same relative folder", File(imported, "data/language.bin").readText())
        assertTrue(File(imported, ".gamenative-offline").isFile)
        assertEquals("untouched", File(existing, "settings.ini").readText())
        assertTrue(File(source, "setup.exe").isFile); assertEquals(0, provider.deletions)
        assertFalse(app.filesDir.listFiles()!!.any { it.name.startsWith(".offline-import-") })
    }
    @Test fun failedOrCancelledCopyNeverPublishesPartialGameAndKeepsSource() {
        provider.collision = true
        assertTrue(runCatching { runBlocking { OfflineGameImport.copy(app, uri) {} } }.isFailure)
        assertEquals(0, File(CustomGameScanner.importRootPath).listFiles()!!.size)
        provider.collision = false
        assertThrows(CancellationException::class.java) { runBlocking { OfflineGameImport.copy(app, uri) { throw CancellationException("cancel") } } }
        assertEquals(0, File(CustomGameScanner.importRootPath).listFiles()!!.size)
        assertTrue(File(source, "setup-1.bin").isFile); assertEquals(0, provider.deletions)
        assertFalse(app.filesDir.listFiles()!!.any { it.name.startsWith(".offline-import-") })
    }
    @Test fun unsupportedFolderOrRunningGameDoesNotCreateLibraryEntry() {
        File(source, "setup.exe").writeText("Not a Windows program")
        assertTrue(runCatching { runBlocking { OfflineGameImport.copy(app, uri) {} } }.isFailure)
        assertTrue(PrefManager.customGameManualFolders.isEmpty())
        testWindowsExe(File(source, "setup.exe")); SteamService.keepAlive = true
        try { assertTrue(runCatching { runBlocking { OfflineGameImport.copy(app, uri) {} } }.isFailure) }
        finally { SteamService.keepAlive = false }
        assertEquals(0, File(CustomGameScanner.importRootPath).listFiles()!!.size)
        assertEquals(0, provider.deletions)
    }

    private fun zip(name: String, extra: String? = null): Pair<Uri, ByteArray> {
        val file = File(source, name)
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            listOf("Game/", "Game/setup.exe", "Game/setup-1.bin").plus(listOfNotNull(extra)).forEach { path ->
                zip.putNextEntry(java.util.zip.ZipEntry(path))
                if (!path.endsWith('/')) zip.write(if (path.endsWith(".exe", true)) File(source, "setup.exe").readBytes() else byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }
        return DocumentsContract.buildDocumentUri("offline.test", "root/$name") to file.readBytes()
    }
    @Test fun zipPreservesDirectoryTreeAndSourceEvenForSpecialArchiveNames() = runBlocking {
        for (name in listOf("...zip", "source.zip.zip", "Normal.zip")) {
            val (uri, bytes) = zip(name)
            val item = OfflineGameImport.archive(app, uri) {}
            val imported = File(CustomGameScanner.getFolderPathFromAppId(item.appId)!!)
            assertTrue(File(imported, "Game/setup.exe").isFile)
            assertArrayEquals(byteArrayOf(1, 2, 3), File(imported, "Game/setup-1.bin").readBytes())
            assertArrayEquals(bytes, File(source, name).readBytes())
        }
        assertEquals(0, provider.deletions)
        assertFalse(File(CustomGameScanner.importRootPath).parentFile!!.listFiles()!!.any { it.name.startsWith(".offline-archive-") })
    }
    @Test fun rejectedOrCancelledArchiveNeverPublishesAndKeepsOriginal() = runBlocking {
        for (bad in listOf("../../escaped.exe", "Game/SETUP.EXE", "Game/settings.ini:secret")) {
            val (uri, bytes) = zip("Bad.zip", bad)
            assertTrue(runCatching { OfflineGameImport.archive(app, uri) {} }.isFailure)
            assertArrayEquals(bytes, File(source, "Bad.zip").readBytes())
            assertTrue(PrefManager.customGameManualFolders.isEmpty())
        }
        val (uri, bytes) = zip("Cancel.zip")
        assertTrue(runCatching { OfflineGameImport.archive(app, uri) { if (it.currentFile.startsWith("Packar upp")) throw CancellationException("cancel") } }.isFailure)
        assertArrayEquals(bytes, File(source, "Cancel.zip").readBytes())
        assertTrue(PrefManager.customGameManualFolders.isEmpty())
        assertEquals(0, File(CustomGameScanner.importRootPath).listFiles()!!.size)
        assertEquals(0, provider.deletions)
    }
}

private class OfflineDocumentsProvider(private val source: File) : ContentProvider() {
    var collision = false
    var deletions = 0
    override fun onCreate() = true
    private fun file(id: String) = if (id == "root") source else File(source, id.removePrefix("root/"))
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_FLAGS)
        val cursor = MatrixCursor(columns)
        val id = DocumentsContract.getDocumentId(uri)
        val f = file(id)
        fun row(child: File, name: String = child.name, docId: String = if (child == source) "root" else "root/" + child.relativeTo(source).invariantSeparatorsPath) {
            cursor.addRow(columns.map { key -> when (key) {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> docId
                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> name
                DocumentsContract.Document.COLUMN_MIME_TYPE -> if (child.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                DocumentsContract.Document.COLUMN_SIZE -> child.length()
                DocumentsContract.Document.COLUMN_FLAGS -> 0
                else -> null
            } }.toTypedArray())
        }
        if (uri.pathSegments.last() == "children") {
            f.listFiles().orEmpty().sortedBy { it.name }.forEach { row(it) }
            if (collision && f == source) row(File(source, "setup.exe"), "SETUP.EXE", "root/collision")
        } else row(f)
        return cursor
    }
    override fun getType(uri: Uri) = if (file(DocumentsContract.getDocumentId(uri)).isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
    override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(file(DocumentsContract.getDocumentId(uri)), ParcelFileDescriptor.MODE_READ_ONLY)
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { deletions++; error("Never delete originals") }
}
