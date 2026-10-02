package app.gamenative.assistant

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal fun testWindowsExe(file: File, machine: Int = 0x8664): File {
    file.parentFile!!.mkdirs()
    val bytes = ByteArray(128)
    bytes[0] = 0x4d; bytes[1] = 0x5a; bytes[0x3c] = 64
    bytes[64] = 0x50; bytes[65] = 0x45
    bytes[68] = machine.toByte(); bytes[69] = (machine shr 8).toByte(); bytes[86] = 2
    file.writeBytes(bytes)
    return file
}

class OfflineGameToolsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val a by lazy { tmp.newFolder("game").canonicalFile }
    private val c by lazy { tmp.newFolder("wine").canonicalFile }
    private val config by lazy { tmp.newFile("config").apply { writeText("""{"executablePath":"old.exe","execArgs":"--old","inputType":3,"sdlControllerAPI":true,"private":"secret"}""") } }
    private val backup by lazy { File(tmp.root, "undo.json") }
    private var stopped = true
    private fun tools() = OfflineGameTools({ listOf(OfflineGameTools.Root("A", a), OfflineGameTools.Root("C", c)) }, { config }, backup, { stopped })
    private fun proposal(id: String, action: String = "run_installer") = JSONObject().put("action", action).put("file_id", id).put("reason", "User requested this local installer")
    private fun id(tools: OfflineGameTools, path: String = "Spelmapp/setup.exe"): String {
        val files = JSONObject(tools.inspect()).getJSONArray("executables")
        return (0 until files.length()).map { files.getJSONObject(it) }.single { it.getString("path") == path }.getString("file_id")
    }

    @Test fun discoversExecutableIdsInOnlyThisGameWithoutUploadingBinaries() {
        testWindowsExe(File(a, "setup.exe")); File(a, "data.bin").writeText("private payload")
        testWindowsExe(File(c, "Games/Game/Binaries/game.exe"), 0x14c)
        testWindowsExe(File(c, "windows/system32/tool.exe"))
        testWindowsExe(File(a, "unins000.exe")); testWindowsExe(File(a, "evil\"name.exe"))
        testWindowsExe(File(a, "renamed.dll.exe")).apply { val b = readBytes(); b[87] = 0x20; writeBytes(b) }
        testWindowsExe(File(a, "arm.exe"), 0xaa64)
        val text = tools().inspect()
        val files = JSONObject(text).getJSONArray("executables")
        assertEquals(2, files.length())
        assertTrue(text.contains("x86_64")); assertTrue(text.contains("x86"))
        assertFalse(text.contains("private payload")); assertFalse(text.contains("secret")); assertFalse(text.contains(a.path))
    }
    @Test fun stagesBeforeApplyThenSelectsInstalledGameAndRestoresOriginalLaunchOnlyAfterReopen() {
        testWindowsExe(File(a, "setup.exe"))
        val original = config.readBytes()
        val tools = tools()
        val pending = tools.prepare(proposal(id(tools)))
        assertArrayEquals(original, config.readBytes()); assertFalse(backup.exists())
        tools.apply(pending)
        assertEquals("setup.exe", JSONObject(config.readText()).getString("executablePath"))
        assertEquals("", JSONObject(config.readText()).getString("execArgs"))
        testWindowsExe(File(c, "Games/Game/game.exe"))
        val installed = tools.prepare(proposal(id(tools, "Wine/Games/Game/game.exe"), "select_game_exe"))
        tools.apply(installed)
        assertEquals("C:\\Games\\Game\\game.exe", JSONObject(config.readText()).getString("executablePath"))
        config.writeText(JSONObject(config.readText()).put("screenSize", "1280x720").toString())
        tools().restore()
        val restored = JSONObject(config.readText())
        assertEquals("old.exe", restored.getString("executablePath")); assertEquals("--old", restored.getString("execArgs"))
        assertEquals(3, restored.getInt("inputType")); assertTrue(restored.getBoolean("sdlControllerAPI"))
        assertEquals("secret", restored.getString("private")); assertEquals("1280x720", restored.getString("screenSize"))
        assertTrue(File(c, "Games/Game/game.exe").exists()); assertFalse(backup.exists())
    }
    @Test fun requiresFreshLocallyIssuedIdAndRejectsModelPathsAndCommandArguments() {
        testWindowsExe(File(a, "setup.exe"))
        val first = tools(); val fileId = id(first)
        assertThrows(IllegalArgumentException::class.java) { tools().prepare(proposal(fileId)) }
        assertThrows(IllegalArgumentException::class.java) { first.prepare(proposal("../../other/setup.exe")) }
        assertThrows(IllegalArgumentException::class.java) { first.prepare(proposal(fileId).put("args", "/S")) }
        first.beginTurn()
        assertThrows(IllegalArgumentException::class.java) { first.prepare(proposal(fileId)) }
        assertFalse(backup.exists())
    }
    @Test fun refusesExeChangedAfterReviewEvenWithUnchangedLengthAndTimestamp() {
        val exe = testWindowsExe(File(a, "setup.exe")); val tools = tools()
        val pending = tools.prepare(proposal(id(tools)))
        val stamp = exe.lastModified(); val bytes = exe.readBytes(); bytes[127] = 1; exe.writeBytes(bytes); exe.setLastModified(stamp)
        val original = config.readBytes()
        assertThrows(IllegalStateException::class.java) { tools.apply(pending) }
        assertArrayEquals(original, config.readBytes()); assertFalse(backup.exists())
    }
    @Test fun linksCannotEscapeSelectedRootsAtInspectionOrApply() {
        val outside = testWindowsExe(tmp.newFile("other.exe"))
        Files.createSymbolicLink(File(a, "linked.exe").toPath(), outside.toPath())
        val exe = testWindowsExe(File(a, "setup.exe")); val tools = tools()
        assertEquals(1, JSONObject(tools.inspect()).getJSONArray("executables").length())
        val pending = tools.prepare(proposal(id(tools)))
        exe.delete(); Files.createSymbolicLink(exe.toPath(), outside.toPath())
        assertThrows(IllegalStateException::class.java) { tools.apply(pending) }
        assertFalse(backup.exists())
    }
    @Test fun blocksRunningGamesAndStaleConfigAndPreservesConflictingUndo() {
        testWindowsExe(File(a, "setup.exe")); val tools = tools(); val fileId = id(tools)
        stopped = false
        assertThrows(IllegalStateException::class.java) { tools.prepare(proposal(fileId)) }
        stopped = true; val pending = tools.prepare(proposal(fileId))
        config.writeText(JSONObject(config.readText()).put("screenSize", "800x600").toString())
        assertThrows(IllegalStateException::class.java) { tools.apply(pending) }
        val fresh = tools.prepare(proposal(id(tools))); stopped = false
        assertThrows(IllegalStateException::class.java) { tools.apply(fresh) }
        stopped = true; tools.apply(fresh)
        config.writeText(JSONObject(config.readText()).put("executablePath", "manual.exe").toString())
        assertThrows(IllegalStateException::class.java) { tools().restore() }
        assertTrue(backup.exists()); assertEquals("manual.exe", JSONObject(config.readText()).getString("executablePath"))
    }
    @Test fun privateWineFilesCanBeSelectedButNotRunAsInstallers() {
        testWindowsExe(File(c, "Games/Game/game.exe")); val tools = tools()
        assertThrows(IllegalStateException::class.java) { tools.prepare(proposal(id(tools, "Wine/Games/Game/game.exe"))) }
        assertFalse(backup.exists())
    }
}
