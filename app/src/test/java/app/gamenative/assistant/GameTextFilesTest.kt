package app.gamenative.assistant

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameTextFilesTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var root: File
    private lateinit var backup: File
    private lateinit var files: GameTextFiles
    @Before fun setup() {
        root = temp.newFolder("game")
        backup = File(temp.newFolder("private"), "undo.json")
        files = service()
    }
    private fun service() = GameTextFiles({ listOf(GameTextFiles.Root("game", "Spelmapp", root)) }, backup)
    private fun file(name: String = "settings.ini", text: String = "[Graphics]\nFPS=60\n") = File(root, name).apply { parentFile!!.mkdirs(); writeText(text) }
    private fun list(query: String = "") = JSONObject(files.list(query)).getJSONArray("files")
    private fun read(name: String = "settings.ini"): String {
        val id = list(name).getJSONObject(0).getString("file_id")
        files.read(id)
        return id
    }
    private fun edit(id: String, old: String = "FPS=60", new: String = "FPS=30") = FileEditProposal(id, listOf(TextReplacement(old, new)), "Test FPS cap")

    @Test fun previewDoesNotWriteThenReopenedServiceRestoresExactOriginalBytes() {
        val source = file(text = "[Graphics]\r\nFPS=60\r\nTitle=Åäö\r\n")
        val original = source.readBytes()
        val preview = files.prepare(edit(read()))
        assertEquals("Spelmapp/settings.ini", preview.path)
        assertEquals("@@ rad 2 @@\n- FPS=60\n+ FPS=30", preview.diff)
        assertArrayEquals(original, source.readBytes())
        assertFalse(files.hasBackup())
        files.apply()
        assertTrue(files.hasBackup())
        assertEquals("[Graphics]\r\nFPS=30\r\nTitle=Åäö\r\n", source.readText())
        service().restore()
        assertArrayEquals(original, source.readBytes())
        assertFalse(files.hasBackup())
    }
    @Test fun preservesEncodingBomAndMixedUnchangedLineEndings() {
        val variants = listOf(Charsets.UTF_8 to byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()),
            Charsets.UTF_16LE to byteArrayOf(0xff.toByte(), 0xfe.toByte()), Charsets.UTF_16BE to byteArrayOf(0xfe.toByte(), 0xff.toByte()))
        variants.forEach { (charset, bom) ->
            val text = "Title=Å🕹\r\nFPS=60\r\nOther=yes\nLast=ok"
            val source = file().apply { writeBytes(bom + text.toByteArray(charset)) }
            val original = source.readBytes()
            files.beginTurn()
            files.prepare(edit(read(), "FPS=60\nOther=yes", "FPS=30\nOther=yes"))
            files.apply()
            assertArrayEquals(bom + text.replace("FPS=60", "FPS=30").toByteArray(charset), source.readBytes())
            service().restore()
            assertArrayEquals(original, source.readBytes())
        }
    }
    @Test fun credentialsAreFilteredAndUnrelatedSecretBytesArePreserved() {
        val source = file(text = "password=private-value\r\nFPS=60\r\n")
        val id = read()
        assertFalse(files.read(id).contains("private-value"))
        assertTrue(runCatching { edit(id, "password=private-value", "password=anything") }.isFailure)
        assertTrue(runCatching { edit(id, "[redacted credential line]", "something") }.isFailure)
        assertTrue(runCatching { files.prepare(edit(id, "private-value", "guessed")) }.isFailure)
        files.prepare(edit(id)); files.apply()
        assertEquals("password=private-value\r\nFPS=30\r\n", source.readText())
        service().restore()
        assertEquals("password=private-value\r\nFPS=60\r\n", source.readText())
    }
    @Test fun requiresLocallyIssuedIdAndFreshReadAndRejectsDuplicateOrOverlappingMatches() {
        file(text = "FPS=60\nFPS=60\n")
        val id = list().getJSONObject(0).getString("file_id")
        assertTrue(runCatching { files.prepare(edit(id)) }.isFailure)
        assertTrue(runCatching { files.read("../settings.ini") }.isFailure)
        files.read(id)
        assertTrue(runCatching { files.prepare(edit(id)) }.isFailure)
        file(text = "FPS=60\nQuality=high\n")
        files.read(id)
        val overlapping = FileEditProposal(id, listOf(TextReplacement("FPS=60", "FPS=30"), TextReplacement("=60", "=40")), "test")
        assertTrue(runCatching { files.prepare(overlapping) }.isFailure)
        files.beginTurn()
        assertTrue(runCatching { files.prepare(edit(id)) }.isFailure)
        assertFalse(files.hasBackup())
    }
    @Test fun changedFileBetweenReadReviewAndApplyIsNeverOverwritten() {
        val source = file()
        val id = read()
        source.appendText("Quality=high\n")
        assertTrue(runCatching { files.prepare(edit(id)) }.isFailure)
        files.read(id); files.prepare(edit(id))
        source.appendText("Resolution=1280x800\n")
        val current = source.readBytes()
        assertTrue(runCatching { files.apply() }.isFailure)
        assertArrayEquals(current, source.readBytes())
        assertFalse(files.hasBackup())
    }
    @Test fun undoConflictRetainsOriginalAndKeepAlsoRefusesUnknownVersion() {
        val source = file()
        val original = source.readBytes()
        files.prepare(edit(read())); files.apply()
        source.appendText("External=change\n")
        val edited = source.readBytes()
        assertTrue(runCatching { service().restore() }.isFailure)
        assertTrue(runCatching { service().keep() }.isFailure)
        assertArrayEquals(edited, source.readBytes())
        assertTrue(files.hasBackup())
        // If a crash occurred after backup but before write, restoring the already-original file is safe.
        source.writeBytes(original)
        service().restore()
        assertFalse(files.hasBackup())
    }
    @Test fun keepRetainsChangedBytesAndAllowsAnotherExperiment() {
        val source = file()
        files.prepare(edit(read())); files.apply()
        assertTrue(runCatching { files.prepare(edit(read(), "FPS=30", "FPS=40")) }.isFailure)
        service().keep()
        assertFalse(files.hasBackup())
        assertTrue(source.readText().contains("FPS=30"))
        files.prepare(edit(read(), "FPS=30", "FPS=40")); files.apply()
        service().restore()
        assertTrue(source.readText().contains("FPS=30"))
    }
    @Test fun backupFailureLeavesGameFileUnchanged() {
        val source = file()
        val original = source.readBytes()
        backup.parentFile!!.delete()
        backup.parentFile!!.writeText("not a directory")
        files.prepare(edit(read()))
        assertTrue(runCatching { files.apply() }.isFailure)
        assertArrayEquals(original, source.readBytes())
    }
    @Test fun listExcludesBinariesScriptsSavesCredentialsAndSymlinks() {
        file()
        listOf("game.exe", "mod.dll", "installer.sh", "config.lua", "saves/slot.json", "savegames/slot.ini", "auth/config.ini", ".private/config.ini", "credentials.json").forEach { file(it) }
        val outside = temp.newFolder("other-game")
        File(outside, "private.ini").writeText("other game data")
        Files.createSymbolicLink(File(root, "linked").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "link.ini").toPath(), File(outside, "private.ini").toPath())
        assertEquals(1, list().length())
        assertFalse(files.list("").contains("other game data"))
    }
    @Test fun symlinkSwapAfterReviewCannotWriteOutsideGameOrThroughALinkInsideGame() {
        val source = file("graphics/settings.ini")
        files.prepare(edit(read("settings.ini")))
        val originalFolder = source.parentFile!!
        val renamed = File(root, "original")
        assertTrue(originalFolder.renameTo(renamed))
        Files.createSymbolicLink(originalFolder.toPath(), renamed.toPath())
        assertTrue(runCatching { files.apply() }.isFailure)
        assertTrue(File(renamed, "settings.ini").readText().contains("FPS=60"))
    }
    @Test fun changingRootOrTamperingWithUndoTraversalNeverTargetsAnotherFolder() {
        file()
        files.prepare(edit(read())); files.apply()
        val other = temp.newFolder("other")
        val wrongRoot = GameTextFiles({ listOf(GameTextFiles.Root("game", "Spelmapp", other)) }, backup)
        assertTrue(runCatching { wrongRoot.restore() }.isFailure)
        val record = JSONObject(backup.readText()).put("relative", "../other/settings.ini")
        backup.writeText(record.toString())
        assertTrue(runCatching { service().restore() }.isFailure)
        assertTrue(files.hasBackup())
    }
    @Test fun limitsRejectLargeMalformedAndBinaryText() {
        file("large.ini", "x".repeat(131_073))
        assertEquals(0, list().length())
        val source = file(text = "x".repeat(48_001))
        assertTrue(runCatching { read() }.isFailure)
        source.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        assertTrue(runCatching { read() }.isFailure)
        source.writeText("hello\u0000binary")
        assertTrue(runCatching { read() }.isFailure)
        assertTrue(runCatching { files.list("x".repeat(101)) }.isFailure)
    }
    @Test fun validatesJsonAndXmlAndRejectsEntityDeclarations() {
        val json = file("settings.json", "{\"fps\":60}")
        val id = read("settings.json")
        assertTrue(runCatching { files.prepare(edit(id, "60", "invalid")) }.isFailure)
        assertTrue(runCatching { files.prepare(edit(id, "\"fps\"", "fps")) }.isFailure)
        files.prepare(edit(id, "60", "30")); files.apply()
        assertEquals(30, JSONObject(json.readText()).getInt("fps"))
        files.restore()
        file("settings.xml", "<settings fps=\"60\"/>")
        val xmlId = read("settings.xml")
        assertTrue(runCatching { files.prepare(edit(xmlId, "60\"/>", "30\"")) }.isFailure)
        assertTrue(runCatching { files.prepare(edit(xmlId, "<settings", "<!DOCTYPE settings SYSTEM \"file:///private\"><settings")) }.isFailure)
        files.prepare(edit(xmlId, "60", "30")); files.apply(); files.restore()
    }
    @Test fun validatesReplacementSchemaAndRejectsControlCharactersAndModelPaths() {
        val id = "5f2dba31-f460-4cb5-9f60-129ab181f26b"
        assertTrue(runCatching { edit(id, "fps", "\u0000") }.isFailure)
        assertTrue(runCatching { FileEditProposal.parse(JSONObject("""{"file_id":"$id","replacements":[{"old_text":"fps","new_text":"FPS"}],"reason":"test","path":"/other"}""")) }.isFailure)
        assertTrue(runCatching { FileEditProposal.parse(JSONObject("""{"file_id":"$id","replacements":[{"old_text":true,"new_text":"FPS"}],"reason":"test"}""")) }.isFailure)
    }
    @Test fun guessedPrivateKeyBodyCannotBeChangedEvenWhenItLooksLikeOrdinaryText() {
        file(text = "FPS=60\n-----BEGIN PRIVATE KEY-----\nunique-key-body\n-----END PRIVATE KEY-----\n")
        val id = read()
        assertFalse(files.read(id).contains("unique-key-body"))
        assertTrue(runCatching { files.prepare(edit(id, "unique-key-body", "guessed")) }.isFailure)
        files.prepare(edit(id)); files.apply(); files.restore()
    }
}
