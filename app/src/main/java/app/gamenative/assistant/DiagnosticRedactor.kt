package app.gamenative.assistant

import app.gamenative.mods.ModDiagnosticSanitizer

object DiagnosticRedactor {
    private val privateKey = Regex("-----BEGIN [^-]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [^-]*PRIVATE KEY-----|$)")
    private val credentialLine = Regex("(?i)(authorization|cookie|password|passwd|secret|(?:access|refresh|id)[_-]?token|api[_-]?key|login_hint|id_token_hint|sessionid|steamLoginSecure|ticket|authorization_code)\\s*[\"']?\\s*[:=].*")
    private val bearer = Regex("(?i)\\bBearer\\s+[^\\s,;]+")
    private val jwt = Regex("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
    private val apiKey = Regex("\\bsk-[A-Za-z0-9_-]{8,}")
    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

    fun text(value: String): String = value.replace(privateKey, "[redacted private key]")
        .lineSequence().joinToString("\n") { line ->
            ModDiagnosticSanitizer.text(line.replace(credentialLine, "[redacted credential line]")
                .replace(bearer, "Bearer [redacted]").replace(jwt, "[redacted token]")
                .replace(apiKey, "[redacted key]")).replace(email, "[redacted email]")
        }
}
