package app.gamenative.assistant

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okio.Buffer

class AssistantSafetyTest {
    @get:Rule val folder = TemporaryFolder()
    private fun fixture(): Pair<File, File> = folder.newFile("config").apply {
        writeText("""{"screenSize":"1920x1080","envVars":"DO_NOT_CHANGE=secret","extraData":{"fpsLimiterEnabled":false,"other":"preserve"}}""")
    } to File(folder.root, "undo/game.json")
    private fun hash(file: File) = AssistantProtocol.sha256(file.readBytes())
    private fun reject(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }

    @Test fun appliesAndRestoresAfterReopeningWithoutLosingUnrelatedChanges() {
        val (config, backup) = fixture()
        ConfigTransaction(config, backup).apply(hash(config), ConfigProposal(30, "1280x720", "test"))
        val applied = JSONObject(config.readText())
        assertEquals(30, applied.getJSONObject("extraData").getInt("fpsLimiterTarget"))
        assertEquals("1280x720", applied.getString("screenSize"))
        assertEquals("DO_NOT_CHANGE=secret", applied.getString("envVars"))
        applied.put("audioDriver", "new setting")
        config.writeText(applied.toString())
        ConfigTransaction(config, backup).restore()
        val restored = JSONObject(config.readText())
        assertEquals("1920x1080", restored.getString("screenSize"))
        assertEquals("new setting", restored.getString("audioDriver"))
        assertFalse(restored.getJSONObject("extraData").getBoolean("fpsLimiterEnabled"))
        assertFalse(restored.getJSONObject("extraData").has("fpsLimiterTarget"))
        assertFalse(backup.exists())
    }

    @Test fun failedBackupCannotChangeConfiguration() {
        val (config, _) = fixture()
        val original = config.readText()
        val blockedParent = folder.newFile("not-a-directory")
        reject { ConfigTransaction(config, File(blockedParent, "undo.json")).apply(hash(config), ConfigProposal(30, null, "test")) }
        assertEquals(original, config.readText())
    }

    @Test fun staleSnapshotCannotOverwriteManualEdit() {
        val (config, backup) = fixture()
        val oldHash = hash(config)
        config.appendText(" ")
        reject { ConfigTransaction(config, backup).apply(oldHash, ConfigProposal(30, null, "test")) }
        assertFalse(backup.exists())
    }

    @Test fun secondApplyCannotDestroyUndoRecord() {
        val (config, backup) = fixture()
        val transaction = ConfigTransaction(config, backup)
        transaction.apply(hash(config), ConfigProposal(30, null, "test"))
        val record = backup.readText()
        reject { transaction.apply(hash(config), ConfigProposal(60, null, "test")) }
        assertEquals(record, backup.readText())
    }

    @Test fun undoConflictPreservesConfigurationAndBackup() {
        val (config, backup) = fixture()
        val transaction = ConfigTransaction(config, backup)
        transaction.apply(hash(config), ConfigProposal(30, null, "test"))
        val edit = JSONObject(config.readText())
        edit.getJSONObject("extraData").put("fpsLimiterTarget", 45)
        config.writeText(edit.toString())
        reject { transaction.restore() }
        assertEquals(45, JSONObject(config.readText()).getJSONObject("extraData").getInt("fpsLimiterTarget"))
        assertTrue(backup.exists())
    }

    @Test fun restoresMissingExtraObjectAndSurvivesInterruptedApply() {
        val (config, backup) = fixture()
        config.writeText("""{"screenSize":"1280x800"}""")
        val original = config.readText()
        ConfigTransaction(config, backup).apply(hash(config), ConfigProposal(30, null, "test"))
        config.writeText(original) // simulate a crash after backup but before config replacement
        ConfigTransaction(config, backup).restore()
        assertFalse(JSONObject(config.readText()).has("extraData"))
        assertFalse(backup.exists())
    }

    @Test fun noOpDoesNotCreateBackup() {
        val (config, backup) = fixture()
        config.writeText("""{"screenSize":"1280x720"}""")
        reject { ConfigTransaction(config, backup).apply(hash(config), ConfigProposal(null, "1280x720", "test")) }
        assertFalse(backup.exists())
    }

    @Test fun rejectsArbitrarySettingsAndInvalidTypes() {
        reject { ConfigProposal.parse(JSONObject("""{"fps":30,"screenSize":null,"reason":"x","envVars":"rm"}""")) }
        reject { ConfigProposal.parse(JSONObject("""{"fps":30.5,"screenSize":null,"reason":"x"}""")) }
        reject { ConfigProposal.parse(JSONObject("""{"fps":"30","screenSize":null,"reason":"x"}""")) }
        reject { ConfigProposal.parse(JSONObject("""{"fps":30,"screenSize":null,"reason":123}""")) }
        reject { ConfigProposal(1000, null, "x") }
        reject { ConfigProposal(null, "../../other", "x") }
        reject { ConfigProposal(null, null, "x") }
    }

    @Test fun identityWithoutPlanPermissionCannotInfer() {
        assertFalse(AssistantProtocol.canInfer(JSONObject().put("access_token", "token").put("scope", "openid email")))
        assertFalse(AssistantProtocol.canInfer(JSONObject().put("scope", AssistantProtocol.DIRECT_SCOPE)))
        assertTrue(AssistantProtocol.canInfer(JSONObject().put("access_token", "token").put("scope", "openid ${AssistantProtocol.DIRECT_SCOPE}")))
    }

    @Test fun identityOnlyTokenResponseIsStoredWithoutEnablingInference() {
        val profile = JSONObject().put("client_id", "oaiapp_test")
        AssistantProtocol.mergeTokens(profile, JSONObject().put("id_token", "validated-before-storing").put("scope", "openid email"), false, 1000)
        assertEquals("validated-before-storing", profile.getString("id_token"))
        assertFalse(AssistantProtocol.canInfer(profile))
    }
    @Test fun refreshRotatesCredentialsAndRetainsScopeOnlyWhenOmitted() {
        val profile = JSONObject().put("scope", AssistantProtocol.DIRECT_SCOPE).put("refresh_token", "old-refresh")
        val grant = JSONObject().put("access_token", "new-access").put("refresh_token", "new-refresh").put("token_type", "Bearer").put("expires_in", 3600)
        AssistantProtocol.mergeTokens(profile, grant, true, 1000)
        assertEquals("new-refresh", profile.getString("refresh_token"))
        assertEquals(3_601_000L, profile.getLong("expires_at"))
        assertTrue(AssistantProtocol.canInfer(profile))
        AssistantProtocol.mergeTokens(profile, grant.put("scope", "openid"), true, 2000)
        assertFalse(AssistantProtocol.canInfer(profile))
    }

    @Test fun requestUsesOnlySupportedPreviewContract() {
        val request = AssistantProtocol.request("from-catalog", "help", "config")
        assertFalse(request.getBoolean("store"))
        assertTrue(request.getBoolean("stream"))
        assertEquals(2, request.getJSONArray("input").length())
        assertEquals("namespace", request.getJSONArray("tools").getJSONObject(0).getString("type"))
        listOf("temperature", "max_output_tokens", "previous_response_id", "background", "conversation").forEach { assertFalse(request.has(it)) }
    }

    private fun completed(): JSONObject = JSONObject().put("status", "completed").put("output", JSONArray().put(
        JSONObject().put("type", "function_call").put("namespace", "game").put("name", "propose_configuration")
            .put("arguments", """{"fps":30,"screenSize":null,"reason":"Test cap"}""")))

    @Test fun acceptsValidatedCompletedToolCall() { assertEquals(30, AssistantProtocol.completedResponse(completed()).proposal?.fps) }
    @Test fun rejectsUnfinishedResponsesAndOtherTools() {
        reject { AssistantProtocol.completedResponse(completed().put("status", "incomplete")) }
        val response = completed()
        response.getJSONArray("output").getJSONObject(0).put("name", "shell")
        reject { AssistantProtocol.completedResponse(response) }
    }
    @Test fun refusesMultipleProposals() {
        val response = completed()
        response.getJSONArray("output").put(response.getJSONArray("output").getJSONObject(0))
        reject { AssistantProtocol.completedResponse(response) }
    }
    @Test fun streamRequiresCompletedEventEvenAfterText() {
        val partial = "data: {\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"item_id\":\"msg_test\",\"delta\":\"Looks good\"}\n\n"
        reject { ResponsesStream.read(Buffer().writeUtf8(partial)) { IllegalStateException("failed") } }
        reject { ResponsesStream.read(Buffer().writeUtf8(partial + "data: [DONE]\n\n")) { IllegalStateException("failed") } }
        val failed = partial + "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n"
        var code = ""
        reject { ResponsesStream.read(Buffer().writeUtf8(failed)) { code = it.getJSONObject("error").getString("code"); IllegalStateException(code) } }
        assertEquals("subscription_sharing_usage_limit_exceeded", code)
    }
    @Test fun streamAcceptsTerminalResponseAcrossSseLines() {
        val raw = "event: response.completed\r\ndata: {\"type\":\"response.completed\",\r\ndata: \"response\":${completed()}}\r\n\r\n"
        assertEquals(30, ResponsesStream.read(Buffer().writeUtf8(raw)) { IllegalStateException("failed") }.proposal?.fps)
    }
    @Test fun stripsRepresentativeSecretsBeforeTransmission() {
        val secret = """
            Authorization: Bearer topsecret
            {"refresh_token":"refresh-secret"}
            password=hunter2
            user=me@example.com
            https://user:pass@example.com/path?code=signed-secret
            eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.signature
            sk-proj-abcdefghijklmnop
            /data/user/0/app.gamenative/files/private
            -----BEGIN PRIVATE KEY-----
            secret-key-data
            -----END PRIVATE KEY-----
            dxvk: shader compilation took 200ms
        """.trimIndent()
        val cleaned = DiagnosticRedactor.text(secret)
        listOf("topsecret", "refresh-secret", "hunter2", "me@example.com", "user:pass", "signed-secret", "eyJ", "sk-proj-", "secret-key-data", "/data/user").forEach { assertFalse(it, cleaned.contains(it)) }
        assertTrue(cleaned.contains("shader compilation took 200ms"))
    }
}
