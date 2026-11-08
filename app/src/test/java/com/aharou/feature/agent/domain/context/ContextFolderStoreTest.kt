package com.aharou.feature.agent.domain.context

import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ContextFolderStoreTest {
    private fun store(root: File): ContextFolderStore = ContextFolderStore(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir(): File = root
    })

    @Test fun emptyFoldersPersistAndCategoriesStayIndependent() {
        val root = Files.createTempDirectory("context-folders-").toFile()
        try {
            assertTrue(store(root).create(ContextCategory.RULE, "构建", "/workspace/a"))
            assertTrue(store(root).create(ContextCategory.LONG_TERM, "资料", "/workspace/a"))
            assertEquals(listOf("构建"), store(root).list("/workspace/a")[ContextCategory.RULE])
            assertEquals(listOf("资料"), store(root).list("/workspace/a")[ContextCategory.LONG_TERM])
            assertEquals(emptyList<String>(), store(root).list("/workspace/b")[ContextCategory.RULE])
        } finally { root.deleteRecursively() }
    }

    @Test fun globalFoldersAreVisibleWithoutLeakingOtherProjectFolders() {
        val root = Files.createTempDirectory("context-folders-").toFile()
        try {
            val store = store(root)
            val builtin = store.list(null)[ContextCategory.MAIN_INJECTED].orEmpty()
            assertTrue(store.create(ContextCategory.MAIN_INJECTED, "通用", null))
            assertTrue(store.create(ContextCategory.MAIN_INJECTED, "项目A", "/workspace/a"))
            assertEquals((builtin + "通用").sorted(), store.list("/workspace/b")[ContextCategory.MAIN_INJECTED])
            assertEquals((builtin + listOf("通用", "项目A")).toSet(), store.list("/workspace/a")[ContextCategory.MAIN_INJECTED]?.toSet())
            assertFalse(store.create(ContextCategory.RULE, "../escape", null))
            assertFalse(store.create(ContextCategory.RULE, "   ", null))
        } finally { root.deleteRecursively() }
    }
    @Test fun nestedPathsPersistParentsAndStayInCategoryAndWorkspace() {
        val root = Files.createTempDirectory("context-folders-").toFile()
        try {
            assertTrue(store(root).create(ContextCategory.RULE, "一级/二级/三级", "/workspace/a"))
            assertEquals(listOf("一级", "一级/二级", "一级/二级/三级"), store(root).list("/workspace/a")[ContextCategory.RULE])
            assertEquals(emptyList<String>(), store(root).list("/workspace/a")[ContextCategory.LONG_TERM])
            assertEquals(emptyList<String>(), store(root).list("/workspace/b")[ContextCategory.RULE])
            assertTrue(store(root).create(ContextCategory.LONG_TERM, "全局/资料", null))
            assertEquals(listOf("全局", "全局/资料"), store(root).list("/workspace/b")[ContextCategory.LONG_TERM])
        } finally { root.deleteRecursively() }
    }

    @Test fun invalidSegmentsNeverCreatePartialParents() {
        val root = Files.createTempDirectory("context-folders-").toFile()
        try {
            val store = store(root)
            listOf("", "/a", "a/", "a//b", "a/./b", "a/../b", "a/\\b", "a/\u0001", "a/" + "长".repeat(81), "a/  ")
                .forEach { assertFalse(it, store.create(ContextCategory.RULE, it, "/workspace/a")) }
            assertTrue(store.list("/workspace/a")[ContextCategory.RULE].orEmpty().isEmpty())
            assertTrue(store.create(ContextCategory.RULE, "长".repeat(80) + "/" + "名".repeat(80), "/workspace/a"))
        } finally { root.deleteRecursively() }
    }


    @Test fun builtinInjectionFoldersAreIndependentAndLegacyFoldersBelongOnlyToMain() {
        val root = Files.createTempDirectory("context-legacy-folders-").toFile()
        try {
            val file = File(root, "aharou-global/memory/.context-folders.json")
            file.parentFile!!.mkdirs()
            val legacy = """{"__global__":{"INJECTED":["legacy-folder"]}}"""
            file.writeText(legacy)
            val store = store(root)
            val main = store.list("/workspace/a").getValue(ContextCategory.MAIN_INJECTED)
            val role = store.list("/workspace/a").getValue(ContextCategory.ROLE_INJECTED)
            assertTrue(main.contains("legacy-folder"))
            assertFalse(role.contains("legacy-folder"))
            assertTrue(main.size >= 2)
            assertEquals(1, role.size)
            assertEquals(legacy, file.readText())
            assertTrue(store.create(ContextCategory.ROLE_INJECTED, "role-only", "/workspace/a"))
            assertFalse(store.list("/workspace/a").getValue(ContextCategory.MAIN_INJECTED).contains("role-only"))
            assertFalse(store.list("/workspace/b").getValue(ContextCategory.ROLE_INJECTED).contains("role-only"))
            assertTrue(store.list(null).getValue(ContextCategory.MAIN_INJECTED).contains("legacy-folder"))
        } finally { root.deleteRecursively() }
    }

}
