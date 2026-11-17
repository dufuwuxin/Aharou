package com.aharou.feature.agent.domain.skill

import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.LocalFileAccess
import com.aharou.feature.workspace.domain.RemoteSftpFileAccess
import com.aharou.feature.workspace.domain.WorkspacePathMapper
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import com.aharou.feature.agent.domain.container.RemoteConnectionConfig
import com.aharou.feature.agent.domain.container.RemoteSshConnection
import io.mockk.*
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.sftp.Response
import com.aharou.testutil.TestFileAccessProvider
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillResourcesTest {
    @get:Rule val temp = TemporaryFolder()
    private fun access(): FileAccessProvider {
        val mapper = mockk<WorkspacePathMapper>()
        every { mapper.toHostFile(any(), any()) } answers { File(firstArg<String>()) }
        val local = LocalFileAccess(mapper)
        return object : FileAccessProvider by TestFileAccessProvider() {
            override fun checked(): FileAccessProvider = this
            override fun requireResourcePath(root: String, relative: String) = local.requireResourcePath(root, relative)
        }
    }

    private class RemoteFixture {
        val connection = mockk<RemoteSshConnection>(relaxed = true)
        val workspace = mockk<WorkspaceRepository>()
        val sftp = mockk<SFTPClient>()
        var link: String? = null
        var missing: String? = null
        var host = "fixture.invalid"
        val access: FileAccessProvider
        init {
            every { connection.config } answers { mockk<RemoteConnectionConfig> {
                every { host } returns this@RemoteFixture.host
                every { port } returns 22
                every { username } returns "fixture"
            } }
            every { connection.remoteHome } returns "/home/fixture"
            every { workspace.currentPath() } returns "/srv/workspace"
            coEvery { connection.sftp() } returns sftp
            every { sftp.lstat(any()) } answers {
                val path = firstArg<String>()
                if (path == missing) throw SFTPException(Response.StatusCode.NO_SUCH_FILE, "fixture missing")
                mockk<FileAttributes> { every { type } returns when (path) {
                    link -> FileMode.Type.SYMLINK
                    "/srv/workspace/skill/scripts/tool.py" -> FileMode.Type.REGULAR
                    else -> FileMode.Type.DIRECTORY
                } }
            }
            access = RemoteSftpFileAccess(connection, workspace).forWorkspace("/srv/workspace")
        }
    }

    @Test fun builtinManifestUsesSelectedRetainedResourcesAndActualDirectory() {
        val language = mockk<AppLanguageProvider> { every { promptCode } returns "zh" }
        val source = spyk(BuiltinSkillSource(mockk(), language))
        every { source.resourceFiles("display-name") } returns mapOf(
            "disk-directory/SKILL.md" to "---\nname: display-name\nrequired_tools: [Bash]\n---\nretained old body".toByteArray(),
            "disk-directory/scripts/old.py" to "old bytes".toByteArray(),
            "disk-directory/templates/nested/example.txt" to "old template".toByteArray())
        val body = source.loadInstructions("display-name")!!
        assertTrue(body.contains("retained old body"))
        assertTrue(body.contains("Directory: ~/.aharou/builtin-skills/disk-directory"))
        assertTrue(body.contains("scripts/old.py"))
        assertTrue(body.contains("templates/nested/example.txt"))
        assertTrue(body.contains("Required tools: Bash"))
        assertFalse(body.contains("new.py"))
        every { source.resourceFiles("display-name") } returns emptyMap()
        assertEquals(null, source.loadInstructions("display-name"))
    }

    @Test fun unimplementedBackendDoesNotSilentlyAuthorizeResources() {
        assertThrows(UnsupportedOperationException::class.java) {
            TestFileAccessProvider().requireResourcePath("/skill", "scripts/tool.py")
        }
    }

    @Test fun localEmptyAndNewDirectoriesAreValidatedWithoutListingOrCopying() {
        val root = Files.createTempDirectory("skill-empty").toFile()
        try {
            access().requireResourcePath(root.path, "scripts/tool.py")
            access().requireResourcePath("${root.path}/new-skill", "scripts/tool.py")
            for (path in listOf("../outside", "/absolute", "scripts/../../outside")) {
                assertThrows(IllegalArgumentException::class.java) { access().requireResourcePath(root.path, path) }
            }
            Files.createSymbolicLink(File(root, "root-link").toPath(), root.toPath())
            assertThrows(IllegalArgumentException::class.java) {
                access().requireResourcePath("${root.path}/root-link", "tool.py")
            }
        } finally {
            Files.deleteIfExists(File(root, "root-link").toPath())
            root.deleteRecursively()
        }
    }

    @Test fun remoteNormalPathsUseLstatForEveryComponent() {
        val f = RemoteFixture()
        f.access.requireResourcePath("~/workspace/skill", "scripts/tool.py")
        for (path in listOf("/srv", "/srv/workspace", "/srv/workspace/skill", "/srv/workspace/skill/scripts",
            "/srv/workspace/skill/scripts/tool.py")) verify(exactly = 1) { f.sftp.lstat(path) }
        f.missing = "/srv/workspace/skill/scripts/tool.py"
        f.access.requireResourcePath("~/workspace/skill", "scripts/tool.py")
    }

    @Test fun remoteAbsoluteTraversalAndEverySymlinkPositionAreRejected() {
        val f = RemoteFixture()
        for (path in listOf("../outside", "/absolute", "scripts/../../outside", "C:/outside")) {
            assertThrows(IllegalArgumentException::class.java) { f.access.requireResourcePath("~/workspace/skill", path) }
        }
        verify(exactly = 0) { f.sftp.lstat(any()) }
        for (link in listOf("/srv/workspace", "/srv/workspace/skill", "/srv/workspace/skill/scripts",
            "/srv/workspace/skill/scripts/tool.py")) {
            f.link = link
            assertThrows(IllegalArgumentException::class.java) {
                f.access.requireResourcePath("~/workspace/skill", "scripts/tool.py")
            }
        }
    }

    @Test fun remoteValidationKeepsConnectionIdentityAndPropagatesPermissionFailures() {
        val f = RemoteFixture()
        every { f.sftp.lstat(any()) } throws SFTPException(Response.StatusCode.PERMISSION_DENIED, "fixture denied")
        assertThrows(SFTPException::class.java) { f.access.requireResourcePath("~/workspace/skill", "scripts/tool.py") }
        f.host = "changed.invalid"
        assertThrows(IllegalStateException::class.java) { f.access.checked() }
        assertThrows(IllegalStateException::class.java) { f.access.requireResourcePath("~/workspace/skill", "scripts/tool.py") }
    }

    @Test fun rejectsAbsoluteTraversalInstructionAndControlPaths() {
        listOf("../x", "/tmp/x", "a/../../x", "a//b", "C:/x", "~/.ssh/key", "a\\b", "a/./b", "a\nfile", "SKILL.md").forEach {
            assertTrue(it, runCatching { SkillResources.relativePath(it) }.isFailure)
        }
        assertEquals("scripts/nested/run.py", SkillResources.relativePath("scripts/nested/run.py"))
    }

    @Test fun nestedFilesAreListedExactlyAndOnlyUtf8TextsAreEditable() {
        val root = temp.newFolder("skill")
        File(root, "SKILL.md").writeText("body")
        File(root, "scripts/nested/run.py").apply { parentFile.mkdirs(); writeText("print('ok')") }
        File(root, "templates/example.txt").apply { parentFile.mkdirs(); writeText("你好") }
        File(root, "code/raw.bin").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(0, 1, 2)) }
        val paths = SkillResources.list(access(), root.path)
        assertEquals(listOf("code/raw.bin", "scripts/nested/run.py", "templates/example.txt"), paths)
        assertEquals(setOf("scripts/nested/run.py", "templates/example.txt"), SkillResources.texts(access(), root.path, paths).keys)
        val manifest = SkillResources.manifest(access(), root.path, listOf("Bash", "readFile"))
        assertTrue(manifest.contains("${root.path}/scripts/nested/run.py"))
        assertTrue(manifest.contains("do not grant permissions"))
    }

    @Test fun symlinkDirectoryEscapesAreRejectedBeforeReadOrWrite() {
        val root = temp.newFolder("root")
        val outside = temp.newFolder("outside")
        File(outside, "secret.txt").writeText("private")
        Files.createSymbolicLink(File(root, "scripts").toPath(), outside.toPath())
        assertTrue(runCatching { SkillResources.path(access(), root.path, "scripts/secret.txt") }.isFailure)
        assertTrue(runCatching { SkillResources.list(access(), root.path) }.isFailure)
        assertEquals("private", File(outside, "secret.txt").readText())
    }

    @Test fun oversizedEditsNeverPassValidation() {
        assertTrue(runCatching { SkillResources.validateEdits(mapOf("scripts/a.py" to "x".repeat(256 * 1024 + 1))) }.isFailure)
        SkillResources.validateEdits(mapOf("scripts/a.py" to "print('ok')"))
    }
}
