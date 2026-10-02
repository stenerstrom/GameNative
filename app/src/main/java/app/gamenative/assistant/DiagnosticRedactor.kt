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

    /** A guessed substring must not let a file edit modify a line/block hidden from the model. */
    fun isEditableSpan(value: String, start: Int, end: Int): Boolean {
        if (privateKey.findAll(value).any { start <= it.range.last && end > it.range.first }) return false
        val lineStart = if (start == 0) 0 else value.lastIndexOf('\n', start - 1) + 1
        val lineEnd = value.indexOf('\n', end - 1).let { if (it < 0) value.length else it }
        val affectedLines = value.substring(lineStart, lineEnd)
        return text(affectedLines) == affectedLines
    }
}
