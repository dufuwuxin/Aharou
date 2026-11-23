package com.aharou.feature.agent.domain.container

/** A complete local container invocation shared by process and PTY launchers. */
data class ProotInvocation(
    val argv: List<String>,
    val env: Map<String, String>,
    internal val injectedEnv: Map<String, String> = emptyMap()
) {
    val executable: String get() = argv.first()

    val ptyEnvArray: Array<String>
        get() = TawcrootRuntime.ptyEnvironment(argv, ContainerEnvSanitizer.scrubbedSystemEnv(), env)
}
