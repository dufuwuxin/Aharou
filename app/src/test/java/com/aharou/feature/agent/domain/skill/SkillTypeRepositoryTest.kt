package com.aharou.feature.agent.domain.skill

import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import com.aharou.feature.workspace.domain.FileAccessProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillTypeRepositoryTest {
    private class Fixture {
        val files = mutableMapOf<String, String>()
        var workspace = "A"
        private fun key(path: String): String = if (path.startsWith("~/workspace/")) "$workspace:$path" else path
        val provider = mockk<FileAccessProvider>().apply {
            every { checked() } returns this
            every { isFile(any()) } answers { files.containsKey(key(firstArg())) }
            every { readFile(any()) } answers { files.getValue(key(firstArg())) }
            every { delete(any()) } answers { files.remove(key(firstArg())); Unit }
            every { writeFile(any(), any(), any(), any()) } answers {
                files[key(firstArg())] = secondArg()
            }
        }
        val repository = SkillTypeRepository(provider)
    }

    private fun type(name: String) = SkillType("common", name, "通用语义: \"引用\"\n第二行", "Reusable functions", listOf("tag: #one", "slash \\"))

    @Test
    fun save_survivesRepositoryRecreationAndPreservesText() {
        val f = Fixture()
        val original = type("General")
        f.repository.save(original, SkillScope.GLOBAL)
        val reloaded = SkillTypeRepository(f.provider)
        assertEquals(original, reloaded.get("common", SkillScope.GLOBAL))
        assertEquals(listOf(original), reloaded.list(SkillScope.GLOBAL))
    }

    @Test
    fun scope_projectWinsAndNeverLeaksIntoGlobalOrAnotherWorkspace() {
        val f = Fixture()
        f.repository.save(type("Global"), SkillScope.GLOBAL)
        f.repository.save(type("Project A"), SkillScope.PROJECT)
        f.repository.save(SkillType("private", "A only", "private semantics", ""), SkillScope.PROJECT)
        assertEquals("Project A", f.repository.get("common", SkillScope.PROJECT)?.name)
        assertEquals("Global", f.repository.get("common", SkillScope.GLOBAL)?.name)
        assertNull(f.repository.get("private", SkillScope.GLOBAL))
        assertNull(f.repository.get("private", SkillScope.BUILTIN))
        f.workspace = "B"
        assertEquals("Global", f.repository.get("common", SkillScope.PROJECT)?.name)
        assertNull(f.repository.get("private", SkillScope.PROJECT))
        assertTrue(f.repository.listLocal(SkillScope.PROJECT).isEmpty())
        f.workspace = "A"
        assertEquals("Project A", f.repository.get("common", SkillScope.PROJECT)?.name)
    }

    @Test
    fun missingAndInvalidTypesDoNotProduceGuidanceOrOverwriteCorruption() {
        val f = Fixture()
        assertNull(f.repository.get("missing", SkillScope.GLOBAL))
        f.repository.save(type("General"), SkillScope.GLOBAL)
        val path = f.files.keys.single()
        f.files[path] = "not valid JSON"
        assertTrue(f.repository.list(SkillScope.GLOBAL).isEmpty())
        val failed = runCatching { f.repository.save(type("Changed"), SkillScope.GLOBAL) }
        assertTrue(failed.isFailure)
        assertEquals("not valid JSON", f.files[path])
    }

    @Test
    fun blankGuidanceInvalidIdsAndBuiltinMutationAreRejected() {
        val f = Fixture()
        assertTrue(runCatching { f.repository.save(type("name").copy(id = "../escape"), SkillScope.GLOBAL) }.isFailure)
        assertTrue(runCatching { f.repository.save(type("name").copy(semantics = "", functions = ""), SkillScope.GLOBAL) }.isFailure)
        assertTrue(runCatching { f.repository.save(type("name"), SkillScope.BUILTIN) }.isFailure)
        assertTrue(f.files.isEmpty())
    }

    @Test
    fun failedWriteRestoresTheOriginalConfiguration() {
        val f = Fixture()
        f.repository.save(type("Original"), SkillScope.GLOBAL)
        val before = f.files.toMap()
        var fail = true
        every { f.provider.writeFile(any(), any(), any(), any()) } answers {
            if (fail) {
                fail = false
                f.files[firstArg()] = "partial"
                error("write interrupted")
            }
            f.files[firstArg()] = secondArg()
        }
        assertTrue(runCatching { f.repository.save(type("Changed"), SkillScope.GLOBAL) }.isFailure)
        assertEquals(before, f.files)
    }

    @Test
    fun cancellationIsNotDowngradedToAnEmptyCatalog() {
        val f = Fixture()
        every { f.provider.isFile(any()) } throws CancellationException("cancelled")
        val result = runCatching { f.repository.list(SkillScope.GLOBAL) }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertFalse(result.isSuccess)
    }
    @Test
    fun firstWriteFailureRemovesOnlyTheNewPartialCatalog() {
        val f = Fixture()
        every { f.provider.writeFile(any(), any(), any(), any()) } answers {
            f.files[firstArg()] = "partial"
            error("write interrupted")
        }
        assertTrue(runCatching { f.repository.save(type("New"), SkillScope.GLOBAL) }.isFailure)
        assertTrue(f.files.isEmpty())
    }

    @Test
    fun projectSaveRejectsAnEditorFromThePreviousWorkspace() {
        val f = Fixture()
        val workspace = mockk<WorkspaceRepository>()
        every { workspace.currentPath() } returns "B"
        val repo = SkillTypeRepository(f.provider, workspace)
        assertTrue(runCatching { repo.save(type("A draft"), SkillScope.PROJECT, "A") }.isFailure)
        assertTrue(f.files.isEmpty())
    }

}
