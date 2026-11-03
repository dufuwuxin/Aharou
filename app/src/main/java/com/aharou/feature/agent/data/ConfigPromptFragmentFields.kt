package com.aharou.feature.agent.data

import com.aharou.core.config.ConfigCollection
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.agent.domain.prompt.PromptFragment
import com.aharou.feature.agent.domain.prompt.PromptFragmentCatalog
import com.aharou.feature.agent.domain.prompt.PromptFragmentInjection
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigPromptFragmentFields @Inject constructor(
    private val catalog: PromptFragmentCatalog,
    private val workspace: WorkspaceRepository,
) {
    fun registerInto(registry: ConfigRegistry) { registry.register(Fragments()) }

    private fun current(id: String, root: String): PromptFragment = catalog.list(root)
        .firstOrNull { "%02d".format(it.number) == id }
        ?: throw ConfigError.InvalidValue("Prompt fragment not found")

    private fun update(id: String, transform: (PromptFragmentInjection) -> PromptFragmentInjection) {
        val root = workspace.currentPath()
        val fragment = current(id, root)
        if (!catalog.updateInjection(fragment.number, root, transform(fragment.injection))) {
            throw ConfigError.InvalidValue("Could not save prompt fragment")
        }
    }

    private inner class Fragments : ConfigCollection {
        override val basePath = "prompt_fragments"
        override val displayName = "Prompt fragment injection"
        override val description = "Injection policy of effective numbered fragments. Existing scope is preserved; built-in fragments become global overrides."
        override val addable = false
        override val removable = false
        override fun childIds() = catalog.list(workspace.currentPath()).map { "%02d".format(it.number) }
        override fun add(payload: ConfigValue): String = throw ConfigError.InvalidValue("Use prompt files to create fragments")
        override fun remove(id: String) { throw ConfigError.InvalidValue("Use prompt files to remove fragments") }

        override fun fields(forId: String): List<ConfigField> {
            if (forId !in childIds()) return emptyList()
            fun boolean(name: String, read: (PromptFragmentInjection) -> Boolean, write: (PromptFragmentInjection, Boolean) -> PromptFragmentInjection) = ClosureField(
                path = "$basePath.$forId.$name", displayName = name,
                description = "Fragment injection switch", valueSchema = ConfigSchema.Bool,
                reader = { ConfigValue.Bool(read(current(forId, workspace.currentPath()).injection)) },
                writer = { value ->
                    val enabled = (value as? ConfigValue.Bool)?.value ?: throw ConfigError.TypeMismatch("boolean")
                    update(forId) { write(it, enabled) }
                },
            )
            fun names(name: String, read: (PromptFragmentInjection) -> Collection<String>, write: (PromptFragmentInjection, List<String>) -> PromptFragmentInjection) = ClosureField(
                path = "$basePath.$forId.$name", displayName = name,
                description = "One value per line; changing this list does not enable its switch", valueSchema = ConfigSchema.Str(),
                reader = { ConfigValue.Str(read(current(forId, workspace.currentPath()).injection).joinToString("\n")) },
                writer = { value ->
                    val text = (value as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                    val values = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
                    update(forId) { write(it, values) }
                },
            )
            return listOf(
                boolean("default_injection", { it.defaultInjection }, { p, v -> p.copy(defaultInjection = v) }),
                boolean("keywords_enabled", { it.keywordsEnabled }, { p, v -> p.copy(keywordsEnabled = v) }),
                boolean("members_enabled", { it.membersEnabled }, { p, v -> p.copy(membersEnabled = v) }),
                names("keywords", { it.keywords }, { p, v -> p.copy(keywords = v) }),
                names("members", { it.members }, { p, v -> p.copy(members = v.toSet()) }),
            )
        }
    }
}
