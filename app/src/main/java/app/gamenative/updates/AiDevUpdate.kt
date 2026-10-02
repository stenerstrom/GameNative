package app.gamenative.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import app.gamenative.BuildConfig
import app.gamenative.service.SteamService
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

data class AiDevUpdate(val versionCode: Long, val versionName: String, val apkUrl: String, val size: Long, val sha256: String, val notes: String) {
    companion object {
        const val PACKAGE = "app.gamenative.aidev"
        const val REPOSITORY = "https://github.com/stenerstrom/GameNative"
        const val MANIFEST_URL = "$REPOSITORY/releases/latest/download/ai-dev-update.json"
        const val MAX_APK_SIZE = 512L * 1024 * 1024

        fun parse(json: JSONObject): AiDevUpdate {
            require(json.getInt("schemaVersion") == 1 && json.getString("packageName") == PACKAGE) { "This update is not for GameNative AI Dev" }
            val code = integer(json, "versionCode")
            require(code in 24..2_100_000_000L) { "Invalid update version" }
            val name = json.getString("versionName")
            require(name.matches(Regex("[A-Za-z0-9._-]{1,80}")) && name.contains("-ai-dev.")) { "Invalid update name" }
            val url = json.getString("apkUrl")
            require(url == "$REPOSITORY/releases/download/ai-dev-$code/GameNative-AI-Dev-$name.apk") { "Unexpected update source" }
            val size = integer(json, "size")
            require(size in 1..MAX_APK_SIZE) { "Invalid update size" }
            val hash = json.getString("sha256")
            require(hash.matches(Regex("[a-f0-9]{64}"))) { "Invalid update checksum" }
            return AiDevUpdate(code, name, url, size, hash, json.optString("notes").take(6000))
        }

        private fun integer(json: JSONObject, key: String): Long {
            val value = json.get(key)
            require(value is Number && value.toDouble() == value.toLong().toDouble()) { "Invalid $key" }
            return value.toLong()
        }

        /** Streaming copy has fixed memory usage and rejects truncated, oversized or altered APKs. */
        fun copyVerified(input: InputStream, output: OutputStream, update: AiDevUpdate, progress: (Float) -> Unit, checkCancelled: () -> Unit) {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            var lastPercent = -1
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                check(total <= update.size) { "Update download is larger than expected" }
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                val percent = (total * 100 / update.size).toInt()
                if (percent != lastPercent) { progress(total.toFloat() / update.size); lastPercent = percent }
            }
            check(total == update.size && hex(digest.digest()) == update.sha256) { "Update checksum or size mismatch; installation stopped" }
        }

        fun verifyIdentity(update: AiDevUpdate, installedCode: Long, packageName: String?, versionCode: Long, versionName: String?, installedSigners: Set<String>, apkSigners: Set<String>) {
            check(packageName == PACKAGE && versionCode == update.versionCode && versionName == update.versionName) { "Downloaded APK does not match the update" }
            check(versionCode > installedCode) { "Only a newer version can be installed" }
            check(installedSigners.isNotEmpty() && installedSigners == apkSigners) { "Signing key mismatch. Keep your installed app; do not uninstall it." }
        }

        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}

/** Dedicated fork channel. Never queries or installs the upstream app's releases. */
class AiDevUpdater(private val context: Context) {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.MINUTES).followSslRedirects(false).build()

    suspend fun check(): AiDevUpdate {
        check(BuildConfig.AI_ASSISTANT_ENABLED && context.packageName == AiDevUpdate.PACKAGE)
        return request(Request.Builder().url(AiDevUpdate.MANIFEST_URL).header("Cache-Control", "no-cache").build()) { response, _ ->
            check(response.isSuccessful) { if (response.code == 404) "No AI Dev update has been published yet" else "Update check failed (HTTP ${response.code}). Try again later." }
            val body = requireNotNull(response.body)
            val bytes = body.byteStream().readBytesBounded(64 * 1024)
            AiDevUpdate.parse(JSONObject(bytes.toString(Charsets.UTF_8)))
        }
    }

    suspend fun download(update: AiDevUpdate, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        check(BuildConfig.AI_ASSISTANT_ENABLED && context.packageName == AiDevUpdate.PACKAGE)
        val directory = File(context.cacheDir, "ai-dev-updates").apply { mkdirs() }
        // One updater activity/operation at a time. Cached APKs contain no app data.
        directory.listFiles()?.forEach { it.delete() }
        val file = File.createTempFile("update-${update.versionCode}-", ".apk", directory)
        try {
            request(Request.Builder().url(update.apkUrl).build()) { response, checkCancelled ->
                check(response.isSuccessful) { "Update download failed (HTTP ${response.code})" }
                requireNotNull(response.body).byteStream().use { input ->
                    file.outputStream().use { output -> AiDevUpdate.copyVerified(input, output, update, onProgress, checkCancelled) }
                }
                validateArchive(file, update)
                file
            }
        } catch (e: Exception) { file.delete(); throw e }
    }

    suspend fun installIntent(file: File, update: AiDevUpdate): Intent = withContext(Dispatchers.IO) {
        check(!SteamService.keepAlive) { "Close the running game before updating. Your installation and data will be kept." }
        check(file.canonicalFile.parentFile == File(context.cacheDir, "ai-dev-updates").canonicalFile) { "Invalid update file" }
        // Recheck the file immediately before handing it to Android's package installer.
        file.inputStream().use { AiDevUpdate.copyVerified(it, object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) = Unit
        }, update, {}, {}) }
        validateArchive(file, update)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()
    fun permissionIntent() = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())

    @Suppress("DEPRECATION")
    private fun validateArchive(file: File, update: AiDevUpdate) {
        val manager = context.packageManager
        val current = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val candidate = requireNotNull(manager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)) { "Downloaded file is not an Android APK" }
        fun signers(info: PackageInfo) = info.signingInfo?.apkContentsSigners.orEmpty().map {
            AiDevUpdate.hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray()))
        }.toSet()
        AiDevUpdate.verifyIdentity(update, current.longVersionCode, candidate.packageName, candidate.longVersionCode, candidate.versionName,
            signers(current), signers(candidate))
    }

    private suspend fun <T> request(request: Request, consume: (Response, () -> Unit) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(IOException("Update connection failed. Check the network and try again.", e)))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use { consume(it) { if (!continuation.isActive) throw CancellationException() } } }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        })
    }

    private fun InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) return output.toByteArray()
            check(output.size() + count <= limit) { "Update metadata too large" }
            output.write(buffer, 0, count)
        }
    }
}
