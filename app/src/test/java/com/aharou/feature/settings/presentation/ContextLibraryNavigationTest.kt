package com.aharou.feature.settings.presentation

import com.aharou.feature.agent.domain.context.ContextCategory
import com.aharou.feature.agent.domain.context.ContextEntry
import com.aharou.feature.agent.domain.context.ContextKind
import com.aharou.feature.agent.domain.context.ContextLoad
import com.aharou.feature.agent.domain.context.ContextOrigin
import com.aharou.feature.agent.domain.memory.MemoryScope
import org.junit.Assert.*
import org.junit.Test

class ContextLibraryNavigationTest {
    @Test fun foldersOnlyExposeDirectChildrenAtEachLevel() {
        val folders = listOf("资料/接口/认证", "资料/接口", "资料/构建", "规则", "资料库/别处")
        assertEquals(listOf("规则", "资料", "资料库"), contextChildFolders(folders, ""))
        assertEquals(listOf("资料/接口", "资料/构建"), contextChildFolders(folders, "资料"))
        assertEquals(listOf("资料/接口/认证"), contextChildFolders(folders, "资料/接口"))
        assertEquals(emptyList<String>(), contextChildFolders(folders, "资料/接口/认证"))
        assertEquals("资料/接口", contextParentFolder("资料/接口/认证"))
        assertEquals("", contextParentFolder("资料"))
    }

    @Test fun directoryViewDoesNotShowDescendantOrOtherCategoryBodies() {
        fun entry(id: String, category: ContextCategory, folder: String) = ContextEntry(id, id, "说明", "正文",
            MemoryScope.PROJECT, ContextKind.REFERENCE, ContextLoad.INDEX, ContextOrigin.MEMORY, id,
            category = category, folder = folder)
        val direct = entry("direct", ContextCategory.LONG_TERM, "资料")
        val nested = entry("nested", ContextCategory.LONG_TERM, "资料/接口")
        val root = entry("root", ContextCategory.LONG_TERM, "")
        val rule = entry("rule", ContextCategory.RULE, "资料")
        assertEquals(listOf(direct), contextFolderEntries(listOf(direct, nested, root, rule), ContextCategory.LONG_TERM, "资料"))
    }

    @Test fun switchingEveryCategoryPreservesChineseDescriptionAndEditableTextExactly() {
        val original = ContextEditorDraft.from(null, ContextCategory.LONG_TERM, "资料/接口", true).copy(
            name = "内置规则也能改名", description = "中文说明：接口、构建与验证；café 日本語 👩🏽‍💻",
            content = "第一行\n第二行：UTF-8 保持原文。", sessionId = "s1")
        var draft = original
        repeat(3) {
            ContextCategory.entries.forEach { category ->
                draft = draft.changeCategory(category)
                assertEquals(original.name, draft.name)
                assertEquals(original.description, draft.description)
                assertEquals(original.content, draft.content)
                assertArrayEquals(original.description.toByteArray(Charsets.UTF_8), draft.description.toByteArray(Charsets.UTF_8))
                draft = ContextEditorDraft.restore(draft.savedFields())
                assertEquals(original.description, draft.description)
            }
        }
    }

    @Test fun draftRestorationKeepsFolderLoadingScopeAndSession() {
        val draft = ContextEditorDraft.from(null, ContextCategory.TEMPORARY, "测试/会话", true)
            .copy(name = "待办", description = "说明", content = "正文", sessionId = "s1", loading = ContextLoad.OFF)
        assertEquals(draft, ContextEditorDraft.restore(draft.savedFields()))
        assertNull(draft.changeCategory(ContextCategory.MAIN_INJECTED).sessionId)
        assertEquals("", draft.changeCategory(ContextCategory.MAIN_INJECTED).folder)
    }

    @Test fun indexDraftKeepsThreeFieldsAndLegacySaverConservativelyRestoresMain() {
        val draft = ContextEditorDraft.from(null, ContextCategory.ROLE_INJECTED, "role-folder", true)
            .copy(name = "标题", description = "摘要", content = "正文", loading = ContextLoad.INDEX)
        assertEquals(draft, ContextEditorDraft.restore(draft.savedFields()))
        assertEquals(ContextLoad.FULL, defaultContextLoad(ContextCategory.ROLE_INJECTED))
        assertEquals(ContextLoad.FULL, defaultContextLoad(ContextCategory.MAIN_INJECTED))
        val legacy = draft.savedFields().toMutableList().also { it[3] = "INJECTED" }
        val restored = ContextEditorDraft.restore(legacy)
        assertEquals(ContextCategory.MAIN_INJECTED, restored.category)
        assertEquals("标题", restored.name)
        assertEquals("摘要", restored.description)
        assertEquals("正文", restored.content)
        assertEquals(ContextLoad.INDEX, restored.loading)
    }


    @Test fun stableRequestSurvivesDraftSaveRestoreAndCategoryChanges() {
        val draft = ContextEditorDraft.from(null, ContextCategory.RULE, "辅助请求", false)
            .copy(requestKey = "agent/custom.md", name = "改名", content = "正文")
        assertEquals(draft, ContextEditorDraft.restore(draft.savedFields()))
        assertEquals(draft.requestKey, draft.changeCategory(ContextCategory.LONG_TERM).requestKey)
        assertNull(ContextEditorDraft.restore(draft.savedFields().take(9)).requestKey)
    }


    @Test fun entryStateSaverRetainsStableRequestAndReadsOlderSavedState() {
        val entry = com.aharou.feature.agent.domain.context.ContextEntry("id", "改名", "摘要", "正文",
            MemoryScope.GLOBAL, com.aharou.feature.agent.domain.context.ContextKind.INSTRUCTION, ContextLoad.OFF,
            com.aharou.feature.agent.domain.context.ContextOrigin.MEMORY, "/native/request.md",
            folder = "任意/目录", requestKey = "agent/custom.md", keywords = listOf("arm64", "构建"))
        val scope = object : androidx.compose.runtime.saveable.SaverScope {
            override fun canBeSaved(value: Any) = true
        }
        val saver = com.aharou.feature.settings.presentation.component.ContextEntryStateSaver
        val fields = with(saver) { scope.save(entry) }!!
        assertEquals(entry, saver.restore(fields))
        val legacy = arrayListOf(entry.id, entry.title, entry.description, entry.content,
            entry.scope.name, entry.kind.name, entry.loading.name, entry.origin.name,
            entry.sourceLabel, entry.version, entry.editable.toString(), entry.pending.toString(),
            entry.enabled.toString(), entry.category.name, entry.folder, "", "")
        assertNull(saver.restore(legacy)!!.requestKey)
        assertEquals(emptyList<String>(), saver.restore(legacy)!!.keywords)
        assertEquals(emptyList<String>(), saver.restore(ArrayList(legacy + "agent/custom.md"))!!.keywords)
        assertEquals("agent/custom.md", saver.restore(ArrayList(legacy + "agent/custom.md"))!!.requestKey)
        assertNull(saver.restore(arrayListOf()))
    }
}
