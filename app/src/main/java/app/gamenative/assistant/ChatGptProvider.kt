package app.gamenative.assistant

import android.content.Context
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.CacheControl
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

interface GameAiProvider {
    suspend fun accounts(): ChatGptProvider.Accounts
    suspend fun select(id: String)
    suspend fun signIn(existingId: String?, openBrowser: suspend (String) -> Unit)
    suspend fun models(): List<ChatGptProvider.Model>
    suspend fun verify(model: String): String
    suspend fun signOut(): Boolean
    suspend fun chat(model: String, prompt: String, diagnostics: String?, history: List<AssistantProtocol.ChatTurn>): AssistantProtocol.Reply
}

/** Official public OAuth + Responses route. There is deliberately no API-key billing fallback. */
class ChatGptProvider(context: Context) : GameAiProvider {
    private val credentials = ChatGptCredentials(context.applicationContext)
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()

    data class Account(val id: String, val label: String, val connected: Boolean, val planEnabled: Boolean)
    data class Model(val slug: String, val name: String)
    data class Accounts(val accounts: List<Account>, val selected: String?)

    override suspend fun accounts(): Accounts = io {
        val data = credentials.load()
        Accounts(profiles(data).mapIndexed { index, p ->
            Account(p.getString("client_id"), "Connection ${index + 1}: ${p.optString("email", "sign-in incomplete")}",
                p.optString("id_token").isNotBlank(), AssistantProtocol.canInfer(p))
        }, data.optString("selected").takeIf { it.isNotBlank() })
    }

    override suspend fun select(id: String): Unit = io {
        val data = credentials.load()
        require(profiles(data).any { it.getString("client_id") == id })
        credentials.save(data.put("selected", id))
    }

    override suspend fun signIn(existingId: String?, openBrowser: suspend (String) -> Unit): Unit = io {
        val data = credentials.load()
        val previous = existingId?.let { id -> profiles(data).single { it.getString("client_id") == id } }
        LoopbackLogin().use { login ->
            val url = "$ISSUER/api/accounts/authorize".toHttpUrl().newBuilder()
                .addQueryParameter("client_id", existingId ?: "dynamic_agent_client")
                .addQueryParameter("ext_agent_host_id", data.getString("host"))
                .addQueryParameter("response_type", "code").addQueryParameter("redirect_uri", login.redirectUri)
                .addQueryParameter("scope", "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct")
                .addQueryParameter("resource", RESOURCE).addQueryParameter("state", login.state)
                .addQueryParameter("nonce", login.nonce).addQueryParameter("code_challenge_method", "S256")
                .addQueryParameter("code_challenge", login.challenge)
            if (existingId == null) url.addQueryParameter("agent_name_hint", "GameNative AI Dev")
            previous?.optString("id_token")?.takeIf { it.isNotBlank() }?.let { url.addQueryParameter("id_token_hint", it) }
            // Reconsent only when the user deliberately reconnects a signed-in identity-only profile.
            if (previous != null && previous.optString("id_token").isNotBlank() && !AssistantProtocol.canInfer(previous)) {
                url.addQueryParameter("prompt", "consent")
            }
            openBrowser(url.build().toString())
            val coroutine = currentCoroutineContext()
            val callback = login.receive { coroutine.ensureActive() }
            check(callback["error"] == null) { "ChatGPT sign-in was declined or unavailable (${callback["error"]?.take(80)})." }
            val clientId = callback["client_id"] ?: existingId ?: error("Registration did not return a client ID")
            require(clientId.isNotBlank() && clientId != "dynamic_agent_client") { "Registration incomplete" }
            require(existingId == null || existingId == clientId) { "Registration mismatch" }
            val pending = previous ?: JSONObject().put("client_id", clientId).also {
                data.getJSONArray("profiles").put(it)
            }
            // Retain the issued registration even when code exchange fails; do not make it active yet.
            credentials.save(data)
            val token = tokenRequest(mapOf("grant_type" to "authorization_code", "client_id" to clientId,
                "code" to (callback["code"] ?: error("Missing authorization code")), "code_verifier" to login.verifier,
                "redirect_uri" to login.redirectUri, "resource" to RESOURCE))
            val identity = verifyIdentity(token.getString("id_token"), clientId, login.nonce)
            if (pending.has("sub")) require(identity.getString("sub") == pending.getString("sub")) { "Account identity mismatch" }
            coroutine.ensureActive()
            mergeTokens(pending, token, refreshing = false)
            pending.put("sub", identity.getString("sub")).put("email", identity.optString("email", "ChatGPT account"))
            credentials.save(data.put("selected", clientId))
        }
    }

    override suspend fun models(): List<Model> = io {
        val token = accessToken()
        val json = jsonRequest(Request.Builder().url("$RESOURCE/models").cacheControl(CacheControl.FORCE_NETWORK)
            .header("Authorization", "Bearer $token").build())
        val models = json.getJSONArray("models")
        (0 until models.length()).map { models.getJSONObject(it) }.filter { it.optString("visibility") == "list" }
            .map { Model(it.getString("slug"), it.getString("display_name")) }
    }

    override suspend fun verify(model: String): String = io {
        val body = JSONObject().put("model", model).put("store", false).put("stream", true)
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with exactly: ChatGPT plan connection verified.")))
        stream(body).also { check(it.text.isNotBlank()) { "Verification response was empty" } }.text
    }

    override suspend fun chat(model: String, prompt: String, diagnostics: String?, history: List<AssistantProtocol.ChatTurn>): AssistantProtocol.Reply = io {
        stream(AssistantProtocol.request(model, prompt, diagnostics, history)).also {
            check(diagnostics != null || it.proposal == null) { "Unexpected configuration proposal without attached settings" }
        }
    }

    /** Clears local secrets even if offline, but retains the registration and host ID. */
    override suspend fun signOut(): Boolean = io {
        val data = credentials.load()
        val profile = selected(data)
        val refresh = profile.optString("refresh_token")
        val revoked = runCatching {
            if (refresh.isNotBlank()) {
                val discovery = jsonRequest(Request.Builder().url("$ISSUER/.well-known/openid-configuration").build())
                val endpoint = discovery.getString("revocation_endpoint").toHttpUrl()
                require(endpoint.scheme == "https" && endpoint.host == "auth.openai.com" && endpoint.port == 443)
                http.newCall(Request.Builder().url(endpoint).post(form(mapOf("token" to refresh,
                    "token_type_hint" to "refresh_token", "client_id" to profile.getString("client_id")))).build()).execute().use {
                    check(it.code == 200)
                }
            }
        }.isSuccess
        clearTokens(profile)
        credentials.save(data)
        revoked
    }

    private fun accessToken(): String {
        val data = credentials.load()
        val profile = selected(data)
        check(AssistantProtocol.canInfer(profile)) { "Sign in and enable ChatGPT plan usage first. Identity sign-in alone cannot make AI requests." }
        if (profile.optLong("expires_at") <= System.currentTimeMillis() + 60_000) {
            val token = try {
                tokenRequest(mapOf("grant_type" to "refresh_token", "client_id" to profile.getString("client_id"),
                    "refresh_token" to profile.getString("refresh_token"), "resource" to RESOURCE))
            } catch (e: ProviderError) {
                if (e.code in setOf("invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused")) {
                    clearTokens(profile)
                    credentials.save(data)
                }
                throw e
            }
            if (token.has("id_token")) {
                val identity = verifyIdentity(token.getString("id_token"), profile.getString("client_id"), null)
                require(identity.getString("sub") == profile.getString("sub")) { "Refresh identity mismatch" }
            }
            mergeTokens(profile, token, refreshing = true)
            credentials.save(data)
        }
        check(AssistantProtocol.canInfer(profile)) { "ChatGPT plan usage is disabled for this connection" }
        return profile.getString("access_token")
    }

    private suspend fun stream(body: JSONObject): AssistantProtocol.Reply {
        val request = Request.Builder().url("$RESOURCE/responses").header("Authorization", "Bearer ${accessToken()}")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (!it.isSuccessful) throw error(it.code, it.body?.string().orEmpty(), it.header("x-request-id"))
                            val source = it.body?.source() ?: error("Empty response")
                            ResponsesStream.read(source, it.header("x-request-id")) { json -> error(it.code, json.toString(), it.header("x-request-id")) }
                        }
                    }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }
    }

    private fun verifyIdentity(token: String, clientId: String, nonce: String?): JSONObject = OidcValidation.verify(token,
        jsonRequest(Request.Builder().url("$ISSUER/.well-known/jwks.json").build()), clientId, nonce, System.currentTimeMillis() / 1000)

    private fun tokenRequest(fields: Map<String, String>): JSONObject = jsonRequest(Request.Builder()
        .url("$ISSUER/api/accounts/oauth/token").post(form(fields)).build())

    private fun jsonRequest(request: Request): JSONObject = http.newCall(request).execute().use {
        val raw = it.body?.string().orEmpty()
        if (!it.isSuccessful) throw error(it.code, raw, it.header("x-request-id"))
        JSONObject(raw)
    }

    private fun mergeTokens(profile: JSONObject, token: JSONObject, refreshing: Boolean) =
        AssistantProtocol.mergeTokens(profile, token, refreshing, System.currentTimeMillis())

    private fun clearTokens(profile: JSONObject) {
        listOf("access_token", "refresh_token", "id_token", "scope", "expires_at").forEach(profile::remove)
    }
    private fun selected(data: JSONObject): JSONObject = profiles(data).singleOrNull { it.getString("client_id") == data.optString("selected") }
        ?: error("Choose a ChatGPT connection first")
    private fun profiles(data: JSONObject): List<JSONObject> = data.getJSONArray("profiles").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun form(fields: Map<String, String>): FormBody = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { lock.withLock { block() } }

    private fun error(status: Int, raw: String, requestId: String?): ProviderError {
        val json = runCatching { JSONObject(raw) }.getOrNull()
        val detail = json?.optJSONObject("error")
        val code = detail?.optString("code")?.takeIf { it.isNotBlank() }
            ?: (json?.opt("error") as? String).orEmpty()
        val message = detail?.optString("message")?.takeIf { it.isNotBlank() }
            ?: json?.optString("detail")?.takeIf { it.isNotBlank() } ?: "Request failed or response was incomplete"
        return ProviderError(code, DiagnosticRedactor.text("OpenAI HTTP $status; $code; $message; parameter=${detail?.optString("param").orEmpty()}; request=${requestId.orEmpty()}").take(1000))
    }

    class ProviderError(val code: String, message: String) : IllegalStateException(message)
    companion object {
        const val USAGE_URL = "https://chatgpt.com/settings/usage"
        private const val ISSUER = "https://auth.openai.com"
        private const val RESOURCE = "https://api.openai.com/v1"
        private val lock = Mutex()
    }
}
