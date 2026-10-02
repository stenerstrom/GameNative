package app.gamenative.assistant

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ControllerProposalTest {
    @get:Rule val folder = TemporaryFolder()
    private fun arguments() = JSONObject("""{"fps":null,"screenSize":null,"reason":"Enable XInput for testing","inputApi":"XINPUT","directInputMapper":null}""")

    @Test fun completedControllerToolCallOnlyTouchesRequestedSettings() {
        val response = JSONObject().put("status", "completed").put("output", JSONArray().put(
            JSONObject().put("type", "function_call").put("namespace", "game").put("name", "propose_configuration")
                .put("arguments", arguments().toString())))
        val reply = AssistantProtocol.completedResponse(response)
        val proposal = requireNotNull(reply.proposal)
        assertEquals(mapOf("inputType" to 2), proposal.patch())
        assertEquals(listOf("Controller input API → XInput only (DirectInput off)"), proposal.changes())
        val transcript = AssistantProtocol.conversationTurn("Enable controller input", reply).assistant
        assertTrue(transcript.contains("not applied"))
        assertTrue(transcript.contains("XInput only"))
        assertFalse(transcript.contains("FPS="))
    }

    @Test fun toolSchemaAdvertisesOnlySupportedEnumsAndRequiresNullableFields() {
        val request = AssistantProtocol.request("model", "Enable input", "settings")
        val tool = request.getJSONArray("tools").getJSONObject(0).getJSONArray("tools").getJSONObject(0)
        assertTrue(tool.getBoolean("strict"))
        val parameters = tool.getJSONObject("parameters")
        val props = parameters.getJSONObject("properties")
        assertFalse(parameters.getBoolean("additionalProperties"))
        assertEquals(props.keys().asSequence().toSet(), parameters.getJSONArray("required").let { a ->
            (0 until a.length()).map { a.getString(it) }.toSet()
        })
        assertEquals("[\"AUTO\",\"DINPUT\",\"XINPUT\",\"BOTH\",null]", props.getJSONObject("inputApi").getJSONArray("enum").toString())
        assertEquals("[\"STANDARD\",\"XINPUT\",null]", props.getJSONObject("directInputMapper").getJSONArray("enum").toString())
    }

    @Test fun rejectsUnknownEnumsNumericOrdinalsBooleansAndUnlistedSettings() {
        listOf("disabled", "XINPUT;exec", "../../other", 2, true, JSONObject()).forEach { invalid ->
            assertTrue(runCatching { ConfigProposal.parse(arguments().put("inputApi", invalid)) }.isFailure)
            assertTrue(runCatching { ConfigProposal.parse(arguments().put("directInputMapper", invalid)) }.isFailure)
        }
        listOf("playerSlot", "sdlControllerAPI", "useSteamInput", "envVars", "inputType").forEach { key ->
            assertTrue(runCatching { ConfigProposal.parse(arguments().put(key, 1)) }.isFailure)
        }
        assertTrue(runCatching { ConfigProposal.parse(arguments().put("inputApi", JSONObject.NULL)) }.isFailure)
    }

    @Test fun restoresMissingControllerFieldsAndPreservesManualUnrelatedEdits() {
        val config = folder.newFile("config").apply { writeText("""{"screenSize":"1920x1080","envVars":"untouched"}""") }
        val backup = File(folder.root, "undo.json")
        ConfigTransaction(config, backup).apply(AssistantProtocol.sha256(config.readBytes()),
            ConfigProposal(null, null, "Test controller", ControllerInputApi.BOTH, DirectInputMapper.XINPUT))
        val changed = JSONObject(config.readText())
        assertEquals(3, changed.getInt("inputType"))
        assertEquals(2, changed.getInt("dinputMapperType"))
        assertFalse(changed.has("extraData"))
        changed.put("screenSize", "1280x800")
        config.writeText(changed.toString())
        ConfigTransaction(config, backup).restore()
        val restored = JSONObject(config.readText())
        assertFalse(restored.has("inputType"))
        assertFalse(restored.has("dinputMapperType"))
        assertEquals("1280x800", restored.getString("screenSize"))
        assertEquals("untouched", restored.getString("envVars"))
        assertFalse(backup.exists())
    }

    @Test fun controllerUndoConflictRetainsBackupAndDoesNotOverwriteManualChoice() {
        val config = folder.newFile("config").apply { writeText("""{"inputType":1,"dinputMapperType":1}""") }
        val backup = File(folder.root, "undo.json")
        val tx = ConfigTransaction(config, backup)
        tx.apply(AssistantProtocol.sha256(config.readBytes()), ConfigProposal.parse(arguments()))
        config.writeText(JSONObject(config.readText()).put("inputType", 3).toString())
        assertTrue(runCatching { tx.restore() }.isFailure)
        assertEquals(3, JSONObject(config.readText()).getInt("inputType"))
        assertTrue(backup.exists())
    }

    @Test fun identicalControllerChoiceDoesNotCreateUndoRecord() {
        val config = folder.newFile("config").apply { writeText("""{"inputType":2,"dinputMapperType":1}""") }
        val backup = File(folder.root, "undo.json")
        assertTrue(runCatching {
            ConfigTransaction(config, backup).apply(AssistantProtocol.sha256(config.readBytes()), ConfigProposal.parse(arguments()))
        }.isFailure)
        assertFalse(backup.exists())
    }
}
