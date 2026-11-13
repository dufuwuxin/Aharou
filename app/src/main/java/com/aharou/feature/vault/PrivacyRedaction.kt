package com.aharou.feature.vault

internal object PrivacyRedaction {
    private const val FIELD = "(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|passwd|secret|session)"
    private val privateKey = Regex("""-----BEGIN ([A-Z ]*PRIVATE KEY)-----[\s\S]*?-----END \1-----""")
    private val authorization = Regex("""(?i)(\b(?:Bearer|Basic)\s+)[A-Za-z0-9_./+=-]{8,}""")
    private val apiKey = Regex("""\b(?:sk-(?:proj-)?[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,})\b""")
    private val quotedField = Regex("""(?i)(\b$FIELD\b["']?\s*[:=]\s*)(?:"((?:\\.|[^"\\\r\n])*)"|'((?:\\.|[^'\\\r\n])*)')""")
    private val headerField = Regex("""(?im)^(\s*x-[a-z0-9-]*(?:token|api-key)\s*:\s*)([^\s"'{}\[\],;]+)""")
    private val shellField = Regex("""(?i)((?:^|[\s;])(?:export\s+)?$FIELD=)([A-Za-z0-9_./+=:@-]+)""")
    private val email = Regex("""(?<![\w.+-])[A-Za-z0-9.!#%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\.[A-Za-z]{2,}(?![\w.-])""")
    private val phone = Regex("""(?<![\w])(?:\+?86[- ]?)?1[3-9]\d{9}(?!\d)""")

    fun redact(text: String, personalData: Boolean = false): String {
        var result = privateKey.replace(text, "[REDACTED:PRIVATE_KEY]")
        result = authorization.replace(result) { it.groupValues[1] + "[REDACTED:AUTH]" }
        result = apiKey.replace(result, "[REDACTED:API_KEY]")
        result = quotedField.replace(result) { match ->
            val value = match.groups[2]?.value ?: match.groupValues[3]
            if (value.startsWith("[REDACTED:") || value.startsWith("{{")) match.value else {
                val quote = if (match.groups[2] != null) "\"" else "'"
                match.groupValues[1] + quote + "[REDACTED:CREDENTIAL]" + quote
            }
        }
        result = headerField.replace(result) { it.groupValues[1] + "[REDACTED:CREDENTIAL]" }
        result = shellField.replace(result) { match ->
            val prefix = result.substring((match.range.first - 80).coerceAtLeast(0), match.range.first)
            val declaration = Regex("""\b(?:val|var|let|const|String)\s*$""").containsMatchIn(prefix)
            if (declaration) match.value else match.groupValues[1] + "[REDACTED:CREDENTIAL]"
        }
        if (personalData) {
            result = email.replace(result, "[REDACTED:EMAIL]")
            result = phone.replace(result, "[REDACTED:PHONE]")
        }
        return result
    }
}
