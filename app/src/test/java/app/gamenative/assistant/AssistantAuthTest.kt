package app.gamenative.assistant

import java.net.Socket
import java.net.URI
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AssistantAuthTest {
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val now = 1_800_000_000L
    private val client = "oaiapp_test"
    private val nonce = "nonce-test"
    private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun unsigned(bytes: ByteArray) = if (bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    private fun jwks(): JSONObject {
        val key = pair.public as RSAPublicKey
        return JSONObject().put("keys", JSONArray().put(JSONObject().put("kid", "test").put("kty", "RSA").put("alg", "RS256")
            .put("n", encode(unsigned(key.modulus.toByteArray()))).put("e", encode(unsigned(key.publicExponent.toByteArray())))))
    }
    private fun claims(): JSONObject = JSONObject().put("iss", "https://auth.openai.com").put("aud", client)
        .put("nonce", nonce).put("sub", "user").put("exp", now + 60).put("iat", now)
    private fun token(claims: JSONObject, signingPair: KeyPair = pair, alg: String = "RS256"): String {
        val header = encode(JSONObject().put("alg", alg).put("kid", "test").toString().toByteArray())
        val body = encode(claims.toString().toByteArray())
        val input = "$header.$body"
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(signingPair.private); update(input.toByteArray()) }.sign()
        return "$input.${encode(signature)}"
    }
    private fun reject(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }

    @Test fun acceptsSignedMatchingIdentity() { assertEquals("user", OidcValidation.verify(token(claims()), jwks(), client, nonce, now).getString("sub")) }
    @Test fun rejectsWrongIssuerAudienceNonceAndExpiry() {
        listOf("iss" to "https://attacker.example", "aud" to "other_client", "nonce" to "other_nonce", "exp" to now).forEach { (key, value) ->
            reject { OidcValidation.verify(token(claims().put(key, value)), jwks(), client, nonce, now) }
        }
    }
    @Test fun rejectsForgedAndUnsignedTokens() {
        val other = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        reject { OidcValidation.verify(token(claims(), other), jwks(), client, nonce, now) }
        reject { OidcValidation.verify(token(claims(), alg = "none"), jwks(), client, nonce, now) }
    }
    @Test fun requiresAuthorizedPartyForMultipleAudiences() {
        val c = claims().put("aud", JSONArray().put(client).put("other"))
        reject { OidcValidation.verify(token(c), jwks(), client, nonce, now) }
        assertEquals("user", OidcValidation.verify(token(c.put("azp", client)), jwks(), client, nonce, now).getString("sub"))
    }
    @Test fun loopbackRejectsWrongStateDuplicateParametersAndWrongPath() {
        LoopbackLogin().use { login ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit<Map<String, String>> { login.receive {} }
                fun callback(path: String): String = Socket("127.0.0.1", URI(login.redirectUri).port).use { socket ->
                    socket.soTimeout = 3000
                    socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                    socket.getInputStream().bufferedReader().readLine()
                }
                assertTrue(callback("/auth/callback?state=wrong&code=bad").contains("400"))
                assertTrue(callback("/auth/callback?state=${login.state}&state=${login.state}&code=bad").contains("400"))
                assertTrue(callback("/callback?state=${login.state}&code=bad").contains("400"))
                assertTrue(callback("/auth/callback?state=${login.state}&code=good&client_id=oaiapp_test").contains("200"))
                assertEquals("good", future.get(3, TimeUnit.SECONDS)["code"])
                assertEquals("http", URI(login.redirectUri).scheme)
                assertEquals("127.0.0.1", URI(login.redirectUri).host)
                assertEquals(43, login.challenge.length)
            } finally { executor.shutdownNow() }
        }
    }
}
