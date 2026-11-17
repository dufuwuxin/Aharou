package com.aharou.feature.plugin

import android.content.Context
import com.aharou.feature.agent.data.ConfigTeamControlFields
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.skill.*
import kotlinx.coroutines.flow.first
import com.aharou.feature.agent.domain.subagent.SubAgentWriteLease
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.domain.tool.config.ConfigTool
import com.aharou.feature.agent.domain.tool.custom.ToolSettingsStore
import com.aharou.feature.plugin.data.ConfigPluginFields
import com.aharou.feature.plugin.data.PluginRecordStore
import com.aharou.feature.plugin.domain.PluginManager
import com.aharou.feature.plugin.domain.market.*
import com.aharou.feature.workspace.domain.FileAccessProvider
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class, sdk = [35])
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.LEGACY)
class PluginMarketWorkspaceTest {
    @get:Rule val temp = TemporaryFolder()
    private val context = mockk<Context>(relaxed = true)
    private val access = mockk<FileAccessProvider>(relaxed = true)
    private val files = mockk<FileAccessProvider>()
    private val catalog = mockk<OperitMarketRepository>()
    private val leases = SubAgentWriteLease()
    private fun tool(): ConfigTool {
        every { context.cacheDir } returns temp.root
        every { files.forWorkspace(any()) } returns access
        every { access.checked() } returns access
        every { access.parentPath(any()) } returns "~/workspace"
        every { access.toDisplayPath(any()) } returns "~/workspace/unapproved.zip"
        val skills = mockk<SkillRepository>()
        val manager = mockk<PluginManager>()
        val actions = PluginMarketplaceActions(context, skills, manager, files)
        actions.javaClass.getDeclaredField("operit").apply { isAccessible = true }.set(actions, catalog)
        coEvery { catalog.page("all", 1) } returns OperitPage(items = listOf(OperitEntry("fixture", "skill")))
        coEvery { catalog.resolve(any(), null) } returns OperitDownload("https://example.org/fixture.zip", "fixture.zip", null)
        coEvery { catalog.stage(any(), any()) } answers { temp.newFile().apply { writeText("fixture") } }
        val settings = mockk<ToolSettingsStore>()
        every { settings.matchesCurrentProject(any()) } returns true
        val fields = ConfigPluginFields(context, manager, mockk<PluginRecordStore>(), settings, actions)
        val sessions = mockk<SessionUseCase>()
        coEvery { sessions.getSessionById(any()) } returns null
        return ConfigTool(context, mockk<ConfigTeamControlFields>(), sessions,
            pluginFields = dagger.Lazy { fields }, writeLease = leases)
    }
    private fun ctx(root: String, id: String) = AgentContext(null, null, root, null,
        sessionId = id, mode = AgentMode.AUTO, writePaths = setOf("allowed.kt"))
    private fun args(action: String, path: String, payload: String? = null): Map<String, JsonElement> = buildMap {
        put("action", JsonPrimitive(action)); put("path", JsonPrimitive(path))
        if (payload != null) put("value", JsonPrimitive(payload))
    }
    @Test fun realConfigAddAndGetKeepQueryAndActionResultsInWorkspaceAndSession() = runBlocking {
        val tool = tool()
        val a = ctx("/workspace-A", "A")
        val b = ctx("/workspace-B", "B")
        val added = tool.executeWithContext(args("add", "plugin_market.queries", """{"source":"operit"}"""), a) as ToolResult.Success
        val id = added.data.jsonObject.getValue("id").jsonPrimitive.content
        val path = "plugin_market.queries.$id.result"
        assertTrue(tool.executeWithContext(args("get", path), a) is ToolResult.Success)
        assertTrue(tool.executeWithContext(args("get", path), b) is ToolResult.Error)
        assertTrue(tool.executeWithContext(args("get", path), ctx("/workspace-A", "another-A")) is ToolResult.Error)
        val bAdded = tool.executeWithContext(args("add", "plugin_market.queries", """{"source":"operit"}"""), b) as ToolResult.Success
        val bId = bAdded.data.jsonObject.getValue("id").jsonPrimitive.content
        assertTrue(tool.executeWithContext(args("get", "plugin_market.queries.$bId.result"), a) is ToolResult.Error)
    }
    @Test fun actualDownloadUsesDynamicLeaseAndDoesNotWriteRejectedDestination() = runBlocking {
        val tool = tool()
        leases.register("A", emptySet())
        val call = args("add", "plugin_market.actions", """{"action":"download","source":"operit","id":"fixture","destination":"~/workspace/./unapproved.zip"}""")
        val denied = tool.executeWithContext(call, ctx("/workspace-A", "A"))
        assertEquals("WRITE_LEASE_DENIED", (denied as ToolResult.Error).code)
        verify(exactly = 0) { access.writeStream(any(), any(), any()) }
        leases.grant("A", setOf("unapproved.zip"))
        val added = tool.executeWithContext(call, ctx("/workspace-A", "A")) as ToolResult.Success
        verify(exactly = 1) { access.writeStream("~/workspace/unapproved.zip", any(), false) }
        val id = added.data.jsonObject.getValue("id").jsonPrimitive.content
        assertTrue(tool.executeWithContext(args("get", "plugin_market.actions.$id.result"), ctx("/workspace-A", "A")) is ToolResult.Success)
        assertTrue(tool.executeWithContext(args("get", "plugin_market.actions.$id.result"), ctx("/workspace-B", "B")) is ToolResult.Error)
        verify(exactly = 2) { files.forWorkspace("/workspace-A") }
    }
    @Test fun marketFailureCategoriesNeverReturnExceptionUrlsOrCredentialText() {
        val synthetic = "https://example.org/private?token=synthetic-test-value"
        assertEquals("DOWNLOAD_FAILED", PluginMarketplaceActions.failureCode(IllegalStateException("market_download_failed", IllegalStateException(synthetic))))
        assertEquals("UNSUPPORTED_FORMAT", PluginMarketplaceActions.failureCode(IllegalStateException("unsupported_format: $synthetic")))
        assertEquals("NAME_CONFLICT", PluginMarketplaceActions.failureCode(IllegalStateException("skill_import_failed:NAME_CONFLICT $synthetic")))
        assertEquals("NATIVE_INSTALL_FAILED", PluginMarketplaceActions.failureCode(IllegalStateException(synthetic)))
    }

    @Test fun explicitSafDocumentDoesNotUseAiDestinationLeaseOrBackend() = runBlocking {
        tool()
        val resolver = mockk<android.content.ContentResolver>()
        val uri = android.net.Uri.parse("content://fixture/chosen-document")
        val output = java.io.ByteArrayOutputStream()
        every { context.contentResolver } returns resolver
        every { resolver.openOutputStream(uri, "wt") } returns output
        val actions = PluginMarketplaceActions(context, mockk(), mockk(), files)
        actions.javaClass.getDeclaredField("operit").apply { isAccessible = true }.set(actions, catalog)
        val result = actions.perform("download", "operit", "fixture", document = uri,
            authorizeWrite = { error("SAF must not check AI write lease") })
        assertEquals("downloaded", result.getValue("status").jsonPrimitive.content)
        assertEquals("fixture", output.toString("UTF-8"))
        verify(exactly = 0) { files.checked() }
        verify(exactly = 0) { files.forWorkspace(any()) }
    }

    @Test fun unreadableResourceDoesNotDropOtherSkillsOrKnownPaths() = kotlinx.coroutines.runBlocking {
        val repo = mockk<SkillRepository>()
        val config = mockk<SkillConfigRepository>()
        every { config.disabledNames() } returns emptySet()
        every { config.changes } returns kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
        val first = Skill("unreadable", "", instructions = "body")
        val second = Skill("readable", "", instructions = "body")
        every { repo.listAllSkills() } returns listOf(SkillEntry(first, SkillScope.GLOBAL), SkillEntry(second, SkillScope.GLOBAL))
        every { repo.resourcePaths(any()) } returns listOf("scripts/run.py")
        every { repo.resourceTexts(first, any()) } throws java.io.IOException("fixture_read_failed")
        every { repo.resourceTexts(second, any()) } returns mapOf("scripts/run.py" to "readable")
        val controller = com.aharou.feature.settings.presentation.SkillSettingsController(
            mockk<android.content.Context>(), repo, config, mockk(), mockk(), mockk())
        controller.refreshSkills()
        val values = kotlinx.coroutines.withTimeout(3000) {
            controller.skills.first { it.size == 2 }
        }
        assertEquals(listOf("scripts/run.py"), values.first().resourcePaths)
        assertTrue(values.first().resourceTexts.isEmpty())
        assertEquals("readable", values.last().resourceTexts["scripts/run.py"])
    }

}
