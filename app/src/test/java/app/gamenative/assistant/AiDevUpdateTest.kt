package app.gamenative.assistant

import app.gamenative.updates.AiDevUpdate
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiDevUpdateTest {
    private val bytes = "example apk bytes".toByteArray()
    private fun manifest() = JSONObject().put("schemaVersion", 1).put("packageName", AiDevUpdate.PACKAGE)
        .put("versionCode", 24).put("versionName", "1.2.1-ai-dev.4")
        .put("apkUrl", "${AiDevUpdate.REPOSITORY}/releases/download/ai-dev-24/GameNative-AI-Dev-1.2.1-ai-dev.4.apk")
        .put("size", bytes.size).put("sha256", AiDevUpdate.hex(MessageDigest.getInstance("SHA-256").digest(bytes)))
        .put("notes", "Test release")
    private fun reject(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }

    @Test fun acceptsForkUpdateAndRetainsReleaseDetails() {
        val info = AiDevUpdate.parse(manifest())
        assertEquals(24L, info.versionCode)
        assertEquals("Test release", info.notes)
    }
    @Test fun rejectsOtherPackageSourceSchemaOrUnsafeVersion() {
        listOf("packageName" to "app.gamenative", "schemaVersion" to 2, "versionCode" to 23, "versionCode" to 24.5,
            "versionName" to "../../other", "apkUrl" to "https://example.com/update.apk").forEach { (key, value) ->
            reject { AiDevUpdate.parse(manifest().put(key, value)) }
        }
    }
    @Test fun rejectsDowngradeHttpAndOtherRepositoryUrls() {
        val original = manifest().getString("apkUrl")
        listOf(original.replace("https:", "http:"), original.replace("stenerstrom", "other"), original + "?other=1",
            original.replace("ai-dev-24/", "ai-dev-23/")).forEach { reject { AiDevUpdate.parse(manifest().put("apkUrl", it)) } }
    }
    @Test fun rejectsInvalidHashAndOversizedFiles() {
        reject { AiDevUpdate.parse(manifest().put("sha256", "wrong")) }
        reject { AiDevUpdate.parse(manifest().put("size", AiDevUpdate.MAX_APK_SIZE + 1)) }
        reject { AiDevUpdate.parse(manifest().put("size", -1)) }
    }
    @Test fun validatesCompleteDownloadAndReportsProgress() {
        val result = ByteArrayOutputStream()
        var progress = 0f
        AiDevUpdate.copyVerified(ByteArrayInputStream(bytes), result, AiDevUpdate.parse(manifest()), { progress = it }, {})
        assertArrayEquals(bytes, result.toByteArray())
        assertEquals(1f, progress)
    }
    @Test fun rejectsTruncatedOversizedAndModifiedDownloads() {
        listOf(bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(0), bytes.copyOf().apply { this[0] = 0 }).forEach {
            reject { AiDevUpdate.copyVerified(ByteArrayInputStream(it), ByteArrayOutputStream(), AiDevUpdate.parse(manifest()), {}, {}) }
        }
    }
    @Test fun cancelledDownloadStopsBeforeWriting() {
        val result = ByteArrayOutputStream()
        reject { AiDevUpdate.copyVerified(ByteArrayInputStream(bytes), result, AiDevUpdate.parse(manifest()), {}, { throw CancellationException() }) }
        assertEquals(0, result.size())
    }
    @Test fun updateRequiresNewerVersionAndExactlyMatchingInstalledSigner() {
        val info = AiDevUpdate.parse(manifest())
        fun validate(installed: Long = 23, pkg: String = AiDevUpdate.PACKAGE, code: Long = 24, name: String = info.versionName,
            signer: Set<String> = setOf("existing"), current: Set<String> = setOf("existing")) =
            AiDevUpdate.verifyIdentity(info, installed, pkg, code, name, current, signer)
        validate()
        reject { validate(installed = 24) }
        reject { validate(installed = 25) }
        reject { validate(pkg = "app.gamenative") }
        reject { validate(code = 25) }
        reject { validate(name = "other") }
        reject { validate(signer = setOf("new-key")) }
        reject { validate(signer = emptySet(), current = emptySet()) }
    }
}
