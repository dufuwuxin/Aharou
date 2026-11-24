package com.aharou.feature.agent.domain.context

import com.aharou.feature.agent.domain.memory.MemoryScope
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextPromptPartitionerTest {
    @Test fun keepsOnlyStableInstructionBlocksInSystemPrefix() {
        fun entry(
            id: String,
            content: String,
            kind: ContextKind = ContextKind.INSTRUCTION,
            category: ContextCategory = ContextCategory.MAIN_INJECTED,
            pending: Boolean = false,
        ) = ContextEntry(
            id = id,
            title = id,
            description = "test",
            content = content,
            scope = MemoryScope.GLOBAL,
            kind = kind,
            loading = ContextLoad.FULL,
            origin = ContextOrigin.MEMORY,
            sourceLabel = id,
            pending = pending,
            category = category,
        )

        val stable = entry("stable", "Fixed instructions")
        val stableTemplate = entry("stable-template", "Workspace=/repo")
        val dynamicTemplate = entry("dynamic-template", "Workspace=/other")
        val reference = entry("reference", "Retrieved fact", kind = ContextKind.REFERENCE)
        val pending = entry("pending", "Unconfirmed instruction", pending = true)
        val temporary = entry("temporary", "Turn-local context", category = ContextCategory.TEMPORARY)
        val entries = listOf(stable, stableTemplate, dynamicTemplate, reference, pending, temporary)
        val blocks = entries.associate { it.id to "block:${it.id}" }
        val preview = ContextPreview(
            full = entries,
            indexed = emptyList(),
            omitted = emptyList(),
            rendered = blocks.values.joinToString("\n\n"),
            renderedEntries = blocks,
        )

        val originals = entries.associateBy { it.id } + mapOf(
            stableTemplate.id to stableTemplate.copy(content = "Workspace={{AICODE_WORKSPACE}}"),
            dynamicTemplate.id to dynamicTemplate.copy(content = "Workspace={{AICODE_SKILLS}}"),
        )
        val parts = ContextPromptPartitioner.partition(preview, originals) { source ->
            source.replace("{{AICODE_WORKSPACE}}", "/repo")
        }

        assertEquals("block:stable\n\nblock:stable-template", parts.system)
        assertEquals("block:dynamic-template\n\nblock:reference\n\nblock:pending\n\nblock:temporary", parts.data)

        val updatedEntries = entries.map { if (it.id == dynamicTemplate.id) it.copy(content = "Workspace=/new") else it }
        val updatedBlocks = blocks + (dynamicTemplate.id to "block:dynamic-template-updated")
        val next = ContextPromptPartitioner.partition(
            preview.copy(full = updatedEntries, renderedEntries = updatedBlocks), originals
        ) { source -> source.replace("{{AICODE_WORKSPACE}}", "/repo") }
        assertEquals(parts.system, next.system)
        assertEquals("block:dynamic-template-updated\n\nblock:reference\n\nblock:pending\n\nblock:temporary", next.data)
    }
}
