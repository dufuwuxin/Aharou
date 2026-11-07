package com.aharou.feature.agent.domain.container

import com.aharou.feature.settings.data.repository.ExecutionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ContainerLogMountTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun profile() = ContainerProfile.BUILTIN_UBUNTU.copy(id = "test-profile")

    @Test
    fun environmentUsesHostWorkspaceIdentity() {
        val first = ContainerLogMount.environment(profile(), "/host/project-a")
        val second = ContainerLogMount.environment(profile(), "/host/project-b")
        assertEquals("/root/log", first["AHAROU_LOG_DIR"])
        assertEquals("test-profile", first["AHAROU_CONTAINER_ID"])
        assertEquals("/host/project-a", first["AHAROU_WORKSPACE_ID"])
        assertEquals("/host/project-b", second["AHAROU_WORKSPACE_ID"])
    }

    @Test
    fun writableSharedDirectoryIsCreatedAndBound() {
        val shared = File(temporary.root, "shared logs")
        val argv = mutableListOf("proot")
        ContainerLogMount.appendDefaultBinding(argv, temporary.root, profile(), shared)
        assertTrue(shared.isDirectory)
        assertEquals(listOf("proot", "-b", "$shared:/root/log"), argv)
        assertTrue(shared.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun sharedFailureUsesPersistentProfileDirectoryAndWarns() {
        val shared = File(temporary.root, "unavailable")
        val visited = mutableListOf<File>()
        val warnings = mutableListOf<String>()
        val argv = mutableListOf<String>()
        ContainerLogMount.appendDefaultBinding(argv, temporary.root, profile(), shared,
            prepare = { visited.add(it); it != shared }, warn = { warnings.add(it) })
        val fallback = File(temporary.root, "logs/build/test-profile")
        assertEquals(listOf(shared, fallback), visited)
        assertEquals(listOf("-b", "$fallback:/root/log"), argv)
        assertEquals(1, warnings.size)
    }

    @Test
    fun explicitBindingSkipsAllDirectoryPreparationAndRemainsLast() {
        for (target in listOf("/root/log", "/root/log/", "/root/./log", "/root/log!")) {
            val custom = "/custom logs:$target"
            val argv = mutableListOf("proot")
            ContainerLogMount.appendDefaultBinding(argv, temporary.root,
                profile().copy(extraBindings = listOf(custom)),
                prepare = { error("Explicit log binding must not touch disk") })
            argv.addAll(listOf("-b", custom))
            assertEquals(listOf("proot", "-b", custom), argv)
        }
    }

    @Test
    fun unrelatedOrInvalidBindingDoesNotSuppressDefault() {
        for (binding in listOf("/custom:/root/logger", "relative:/root/log", "/custom:/root/../root/log")) {
            val argv = mutableListOf<String>()
            val shared = File(temporary.root, "shared")
            ContainerLogMount.appendDefaultBinding(argv, temporary.root,
                profile().copy(extraBindings = listOf(binding)), shared, prepare = { true })
            argv.addAll(listOf("-b", binding))
            assertEquals("$shared:/root/log", argv[1])
            assertEquals(binding, argv.last())
        }
    }

    @Test
    fun bothDirectoriesUnavailableDoesNotBlockInvocation() {
        val argv = mutableListOf("proot")
        val warnings = mutableListOf<String>()
        ContainerLogMount.appendDefaultBinding(argv, temporary.root, profile(),
            prepare = { false }, warn = { warnings.add(it) })
        assertEquals(listOf("proot"), argv)
        assertEquals(2, warnings.size)
    }

    @Test
    fun regularFileCannotBeUsedAsDirectory() {
        val blocked = temporary.newFile("not-a-directory")
        val argv = mutableListOf<String>()
        ContainerLogMount.appendDefaultBinding(argv, temporary.root, profile(), blocked)
        val fallback = File(temporary.root, "logs/build/test-profile")
        assertTrue(fallback.isDirectory)
        assertEquals(listOf("-b", "$fallback:/root/log"), argv)
    }

    @Test
    fun remoteSshDoesNotMountOrTouchHostPaths() {
        val argv = mutableListOf("ssh")
        ContainerLogMount.appendDefaultBinding(argv, temporary.root,
            profile().copy(mode = ExecutionMode.REMOTE_SSH),
            prepare = { error("Remote profiles must not touch host directories") })
        assertEquals(listOf("ssh"), argv)
    }

    @Test
    fun fallbackProfileIdCannotEscapePersistentDirectory() {
        val shared = File(temporary.root, "unavailable")
        val argv = mutableListOf<String>()
        ContainerLogMount.appendDefaultBinding(argv, temporary.root,
            profile().copy(id = "../../outside"), shared, prepare = { it != shared })
        val source = File(argv[1].substringBefore(':')).canonicalFile
        assertEquals(File(temporary.root, "logs/build").canonicalFile, source.parentFile)
        assertFalse(source.name.contains(".."))
    }
}
