package app.gamenative.assistant

import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Listener exists only during sign-in, on IPv4 loopback with an OS-selected port. */
class LoopbackLogin : AutoCloseable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1000 }
    val redirectUri = "http://127.0.0.1:${server.localPort}/auth/callback"
    val state = random()
    val nonce = random()
    val verifier = random()
    val challenge: String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

    fun receive(checkCancelled: () -> Unit): Map<String, String> {
        val deadline = System.nanoTime() + 300_000_000_000L
        while (System.nanoTime() < deadline) {
            checkCancelled()
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
            socket.use {
                it.soTimeout = 1500
                // Bound the request line; no request bodies, cookie handling or request logging.
                val line = StringBuilder()
                while (line.length < 16_384) {
                    val c = try { it.getInputStream().read() } catch (_: SocketTimeoutException) { break }
                    if (c < 0 || c == 10) break
                    line.append(c.toChar())
                }
                val target = line.toString().trim().split(' ')
                val uri = if (target.size == 3 && target[0] == "GET" && target[1].startsWith("/auth/callback?")) {
                    runCatching { ("http://127.0.0.1" + target[1]).toHttpUrl() }.getOrNull()
                } else null
                val valid = uri != null && uri.encodedPath == "/auth/callback" &&
                    uri.queryParameterNames.all { key -> uri.queryParameterValues(key).size == 1 } &&
                    MessageDigest.isEqual(state.toByteArray(), uri.queryParameter("state").orEmpty().toByteArray())
                val message = if (valid) "Return to GameNative AI Dev to finish sign-in." else "Invalid callback. Continue sign-in from the app."
                val body = message.toByteArray()
                runCatching {
                    it.getOutputStream().write(("HTTP/1.1 ${if (valid) "200 OK" else "400 Bad Request"}\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray() + body)
                }
                if (valid) return requireNotNull(uri).queryParameterNames.associateWith { key -> uri.queryParameter(key).orEmpty() }
            }
        }
        error("Sign-in timed out. Return to the app and try again.")
    }
    override fun close() = server.close()
    companion object {
        private fun random(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    }
}
