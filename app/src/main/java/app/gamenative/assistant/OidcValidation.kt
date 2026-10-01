package app.gamenative.assistant

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import org.json.JSONObject

object OidcValidation {
    fun verify(token: String, jwks: JSONObject, clientId: String, nonce: String?, nowSeconds: Long): JSONObject {
        val parts = token.split('.')
        require(parts.size == 3) { "Invalid ID token" }
        val decoder = Base64.getUrlDecoder()
        val header = JSONObject(String(decoder.decode(parts[0]), Charsets.UTF_8))
        require(header.getString("alg") == "RS256") { "Unsupported ID token signature" }
        val keys = jwks.getJSONArray("keys")
        val matches = (0 until keys.length()).map { keys.getJSONObject(it) }.filter {
            it.optString("kid") == header.getString("kid") && it.optString("kty") == "RSA" &&
                it.optString("use", "sig") == "sig" && it.optString("alg", "RS256") == "RS256"
        }
        require(matches.size == 1) { "Signing key unavailable" }
        val jwk = matches.single()
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(
            BigInteger(1, decoder.decode(jwk.getString("n"))), BigInteger(1, decoder.decode(jwk.getString("e"))),
        ))
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        require(verifier.verify(decoder.decode(parts[2]))) { "Invalid ID token signature" }
        val claims = JSONObject(String(decoder.decode(parts[1]), Charsets.UTF_8))
        require(claims.getString("iss") == "https://auth.openai.com") { "Invalid issuer" }
        val audiences = claims.optJSONArray("aud")?.let { a -> (0 until a.length()).map { a.getString(it) } }
            ?: listOf(claims.getString("aud"))
        require(clientId in audiences) { "Invalid audience" }
        if (audiences.size > 1 || claims.has("azp")) require(claims.optString("azp") == clientId) { "Invalid authorized party" }
        require(claims.getLong("exp") > nowSeconds && claims.optLong("iat", nowSeconds) <= nowSeconds + 60 &&
            claims.optLong("nbf", 0) <= nowSeconds + 60) { "Expired or premature ID token" }
        if (nonce != null) require(MessageDigest.isEqual(nonce.toByteArray(), claims.getString("nonce").toByteArray())) { "Invalid nonce" }
        require(claims.getString("sub").isNotBlank()) { "Missing identity" }
        return claims
    }
}
