package com.aharou.feature.agent.domain.subagent

import com.aharou.feature.agent.domain.context.ContextCategory
import com.aharou.feature.agent.domain.context.ContextLibraryRepository
import com.aharou.feature.agent.domain.context.ContextLoad
import com.aharou.feature.agent.domain.context.ContextOrigin
import com.aharou.feature.agent.domain.context.WorldbookAudience
import com.aharou.feature.agent.domain.context.WorldbookRules
import com.aharou.feature.agent.domain.memory.Memory
import com.aharou.feature.agent.domain.memory.MemoryRepository
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.workspace.domain.FileAccessProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test

class AgentRoleTagsTest {
    private val files = mockk<FileAccessProvider>(relaxed = true)
    private val member = AgentDefinition(
        name = "stable-member", description = "review", allowedTools = listOf("readFile", "Bash"),
        disallowedTools = listOf("Bash"), skills = listOf("existing"), prompt = "instructions",
        filePath = "/global/stable.md", tags = listOf("backend", "audit")
    )

    private fun parse(text: String): AgentDefinition {
        every { files.readFile(any()) } returns text
        return requireNotNull(AgentDefinitionParser.parse(files, "/global/stable.md"))
    }

    private fun serialize(tags: List<String>) = AgentDefinitionParser.serialize(
        name = member.name, description = member.description, providerId = null, model = null,
        reasoningEffort = null, allowedTools = member.allowedTools, disallowedTools = member.disallowedTools,
        skills = member.skills, inject = member.inject, prompt = member.prompt, tags = tags
    )

    @Test fun oldYamlDefaultsToEmptyTagsWithoutChangingIdentity() {
        val parsed = parse("---\nname: stable-member\ntools: [readFile]\ndisallowedTools: [Bash]\n---\ninstructions")
        assertEquals(emptyList<String>(), parsed.tags)
        assertEquals(member.name, parsed.name)
        assertEquals(listOf("readFile"), parsed.allowedTools)
        assertEquals(listOf("Bash"), parsed.disallowedTools)
    }

    @Test fun yamlRoundTripQuotesSpecialCharactersAndKeepsToolPermissions() {
        val tags = listOf("backend", "quote\"slash\\", "colon: #hash [x]", "true")
        val parsed = parse(serialize(tags))
        assertEquals(tags, parsed.tags)
        assertEquals(member.name, parsed.name)
        assertEquals(member.skills, parsed.skills)
        assertEquals(member.allowedTools, parsed.allowedTools)
        assertEquals(member.disallowedTools, parsed.disallowedTools)
        assertEquals(listOf("readFile"), parsed.filterToolNames(listOf("readFile", "Bash", "writeFile")))
    }

    @Test fun normalizationIsStableAndLimitsRejectRatherThanTruncate() {
        assertEquals(listOf("backend", "audit"), parse(serialize(listOf(" backend ", "", "backend", "audit"))).tags)
        assertFalse(AgentDefinition.validTags(List(25) { "tag$it" }))
        assertFalse(AgentDefinition.validTags(listOf("x".repeat(65))))
        assertFalse(AgentDefinition.validTags(listOf("a,b")))
        assertTrue(AgentDefinition.validTags(List(24) { "tag$it" }))
        every { files.readFile(any()) } returns "---\ntags: [${"x".repeat(65)}]\n---\ninstructions"
        assertNull(AgentDefinitionParser.parse(files, "/global/stable.md"))
    }

    @Test fun invalidYamlTagTypesAreRejectedWithoutStringifyingThem() {
        listOf("[true]", "[42]", "[null]", "[{label: audit}]", "{label: audit}", "true", "12").forEach { raw ->
            every { files.readFile(any()) } returns "---\nname: stable\ntags: $raw\n---\ninstructions"
            assertNull("Must reject raw YAML type $raw", AgentDefinitionParser.parse(files, "/global/stable.md"))
        }
    }

    @Test fun skillBindingRewritesPreserveTagsAndOriginalNameAndTools() {
        val global = mockk<GlobalDirectoryAgentSource>()
        val project = mockk<ProjectDirectoryAgentSource>()
        val config = mockk<AgentDefinitionConfigRepository>()
        every { global.listDefinitions() } returns listOf(member)
        every { project.listDefinitions() } returns emptyList()
        every { global.agentsRoot } returns "/global"
        every { project.agentsRoot } returns "/project"
        every { files.isFile(any()) } returns true
        var written = ""
        every { files.writeFile(any(), any(), any(), any()) } answers { written = secondArg() }
        val repository = AgentDefinitionRepository(global, project, config, files)
        assertNull(repository.setSkillBinding(member.name, AgentDefinitionScope.GLOBAL, "shared", true))
        assertEquals(member.copy(skills = listOf("existing", "shared")), parse(written))
        assertNull(repository.setSkillBinding(member.name, AgentDefinitionScope.GLOBAL, "existing", false))
        assertEquals(member.copy(skills = emptyList()), parse(written))
        assertEquals(AgentSaveError.INVALID_TAGS, repository.save(
            AgentDefinitionForm(member.name, member.description, prompt = member.prompt, tags = List(25) { "tag$it" }),
            AgentDefinitionScope.GLOBAL
        ))
        verify(exactly = 2) { files.writeFile("/global/stable.md", any(), any(), any()) }
    }

    @Test fun tagsUpdateRereadsLatestPermissionsAndRejectsAnOverridingProjectIdentity() {
        val global = mockk<GlobalDirectoryAgentSource>()
        val project = mockk<ProjectDirectoryAgentSource>()
        val config = mockk<AgentDefinitionConfigRepository>()
        var latest = member.copy(allowedTools = listOf("readFile"), prompt = "new instructions")
        var override = emptyList<AgentDefinition>()
        every { global.listDefinitions() } answers { listOf(latest) }
        every { project.listDefinitions() } answers { override }
        every { global.agentsRoot } returns "/global"
        every { project.agentsRoot } returns "/project"
        every { files.isFile(any()) } returns true
        var written = ""
        every { files.writeFile(any(), any(), any(), any()) } answers { written = secondArg() }
        val repository = AgentDefinitionRepository(global, project, config, files)
        assertNull(repository.setTags(member.name, listOf("new-tag"), AgentDefinitionScope.GLOBAL, member.filePath))
        val saved = parse(written)
        assertEquals(listOf("readFile"), saved.allowedTools)
        assertEquals("new instructions", saved.prompt)
        assertEquals(listOf("new-tag"), saved.tags)
        override = listOf(member.copy(filePath = "/project/stable.md"))
        assertEquals(AgentSaveError.IO_FAILED,
            repository.setTags(member.name, listOf("do-not-write"), AgentDefinitionScope.GLOBAL, member.filePath))
        verify(exactly = 1) { files.writeFile(any(), any(), any(), any()) }
    }

    private fun library(rules: WorldbookRules): ContextLibraryRepository {
        val memories = mockk<MemoryRepository>()
        val entries = listOf(
            Memory("tagged-memory", "memory", MemoryScope.GLOBAL, content = "role-memory-payload",
                contextLoad = ContextLoad.FULL, worldbook = rules),
            Memory("tagged-rule", "rule", MemoryScope.GLOBAL, content = "role-rule-payload",
                contextLoad = ContextLoad.FULL, contextCategory = ContextCategory.RULE,
                contextRequest = "tagged-rule", worldbook = rules)
        )
        every { memories.listMemoriesInScope(any(), any()) } answers {
            if (firstArg<MemoryScope>() == MemoryScope.GLOBAL) entries else emptyList()
        }
        return ContextLibraryRepository(memories)
    }

    private val rules = WorldbookRules(audiences = listOf(WorldbookAudience.ROLE),
        roles = listOf("stable-member"), roleTags = listOf("backend", "frontend"))
    private fun ctx(definition: AgentDefinition? = member) = AgentContext(
        null, null, "/project", null, currentUserText = "backend stable-member", sessionId = "s1",
        agentDefinition = definition
    )

    @Test fun actualDefinitionTagsActivateBothPlannerAndResolvePromptAndPreview() {
        val library = library(rules)
        library.withPromptSnapshot(ctx()) {
            assertTrue(library.render("/project", "s1", ContextOrigin.MEMORY).contains("role-memory-payload"))
            assertEquals("role-rule-payload", library.resolvePrompt("tagged-rule"))
            assertTrue(library.render("/project", "s1", ContextOrigin.MEMORY, expand = { it })
                .contains("role-memory-payload"))
            ""
        }
        assertTrue(library.preview("/project", "", "s1", member.name, roleTags = member.tags)
            .rendered.contains("role-memory-payload"))
    }

    @Test fun mainMissingDefinitionWrongTagAndWrongNameCannotActivate() {
        val library = library(rules)
        listOf(ctx(null), ctx(member.copy(tags = emptyList())), ctx(member.copy(tags = listOf("Backend"))),
            ctx(member.copy(name = "different"))).forEach { context ->
            library.withPromptSnapshot(context) {
                assertFalse(library.render("/project", "s1", ContextOrigin.MEMORY).contains("role-memory-payload"))
                assertEquals("", library.resolvePrompt("tagged-rule"))
                ""
            }
        }
        library.withPromptSnapshot(ctx(null), roleSession = true) {
            assertEquals("", library.resolvePrompt("tagged-rule"))
            assertFalse(library.render("/project", "s1", ContextOrigin.MEMORY).contains("role-memory-payload"))
            ""
        }
        assertNotNull(rules.omission("", WorldbookAudience.MAIN, member.name, member.tags))
        assertNotNull(rules.omission("", WorldbookAudience.ROLE, null, member.tags))
        assertNotNull(library.preview("/project", "backend stable-member", "s1", member.name)
            .omitted.firstOrNull { it.first.title == "tagged-memory" })
    }

    @Test fun oldRoleNameOnlyAndWorldbookRetrievalTagsKeepTheirMeaning() {
        val old = WorldbookRules(roles = listOf(member.name), tags = listOf("retrieval-only"))
        assertNull(old.omission("", WorldbookAudience.ROLE, member.name))
        assertNotNull(old.omission("", WorldbookAudience.ROLE, "other"))
        val decoded = WorldbookRules.fromJson(rules.copy(tags = listOf("retrieval-only"), keywordWeight = 2.0,
            tagWeight = 3.0).toJson())
        assertEquals(rules.roleTags, decoded.roleTags)
        assertEquals(listOf("retrieval-only"), decoded.tags)
        assertEquals(2.0, decoded.keywordWeight, 0.0)
        assertEquals(3.0, decoded.tagWeight, 0.0)
        assertEquals(emptyList<String>(), WorldbookRules.fromJson("{}").roleTags)
    }

    @Test fun configTagWritePreservesToolsSkillsAndStableDottedRoleName() {
        val global = mockk<GlobalDirectoryAgentSource>()
        val project = mockk<ProjectDirectoryAgentSource>()
        val config = mockk<AgentDefinitionConfigRepository>()
        val original = member.copy(name = "stable.member")
        every { global.listDefinitions() } returns listOf(original)
        every { project.listDefinitions() } returns emptyList()
        every { global.agentsRoot } returns "/global"
        every { project.agentsRoot } returns "/project"
        every { files.isFile(any()) } returns true
        var written = ""
        every { files.writeFile(any(), any(), any(), any()) } answers { written = secondArg() }
        val repository = AgentDefinitionRepository(global, project, config, files)
        val constructor = com.aharou.core.config.ConfigRegistry::class.java.getDeclaredConstructor()
        constructor.isAccessible = true
        val registry = constructor.newInstance()
        com.aharou.feature.agent.data.ConfigSubAgentFields(mockk(relaxed = true), repository).registerInto(registry)
        val path = "subagent.roles.stable%2Emember.tags"
        val field = requireNotNull(registry.resolveField(path))
        assertEquals(com.aharou.core.config.ConfigValue.Arr(member.tags.map { com.aharou.core.config.ConfigValue.Str(it) }), field.read())
        field.write(com.aharou.core.config.ConfigValue.Arr(listOf(com.aharou.core.config.ConfigValue.Str("new"))))
        assertEquals(original.copy(tags = listOf("new")), parse(written))
        assertFalse(requireNotNull(registry.collection("subagent.roles")).addable)
        assertFalse(requireNotNull(registry.collection("subagent.roles")).removable)
    }

}
