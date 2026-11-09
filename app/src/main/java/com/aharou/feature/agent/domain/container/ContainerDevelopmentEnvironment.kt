package com.aharou.feature.agent.domain.container

/** Only the installer's environment fragment is safe to source in stdio protocols. */
internal object ContainerDevelopmentEnvironment {
    private const val PROFILE = "/etc/profile.d/aicode-android.sh"

    fun wrap(command: String, overrides: Map<String, String> = emptyMap(), profile: String = PROFILE): String {
        val exports = overrides.entries.filter { it.key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }.joinToString("") { (key, value) ->
            "export $key=${quote(value)}; "
        }
        return "if [ -r ${quote(profile)} ]; then . ${quote(profile)} >/dev/null 2>&1; fi; $exports$command"
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}
