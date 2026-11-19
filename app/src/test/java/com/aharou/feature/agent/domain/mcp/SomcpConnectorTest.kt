package com.aharou.feature.agent.domain.mcp

import com.aharou.feature.connectors.data.ConnectorInstaller
import com.aharou.feature.connectors.domain.ConnectorCatalog
import com.aharou.feature.connectors.domain.ConnectorAuthKind
import com.aharou.feature.vault.VaultRepository
import io.mockk.*
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SomcpConnectorTest {
    @Test fun builtInSomcpUsesTheRealVaultAndMcpInstaller() = runTest {
        val asset = sequenceOf(File("src/main/assets/connectors.json"), File("app/src/main/assets/connectors.json"))
            .first { it.isFile }
        val connector = Json { ignoreUnknownKeys = true }.decodeFromString<ConnectorCatalog>(asset.readText())
            .connectors.single { it.id == "somcp" }
        assertEquals(ConnectorAuthKind.KEY_HEADER, connector.authKind)
        assertEquals("http://127.0.0.1:8000/mcp", connector.url)
        val configs = mockk<McpConfigRepository>(relaxed = true)
        val manager = mockk<McpManager>(relaxed = true)
        val vault = mockk<VaultRepository>(relaxed = true)
        coEvery { configs.findEntry("somcp") } returns null
        coEvery { configs.getGlobalServers() } returns emptyList()
        val written = slot<List<McpServerConfig>>()
        coEvery { configs.setGlobalServers(capture(written)) } returns true
        ConnectorInstaller(configs, manager, vault).installWithKey(connector, "fixture-token")
        coVerify { vault.set("connector_somcp", "fixture-token") }
        val header = written.captured.single().headers.getValue("Authorization")
        assertTrue(header.startsWith("Bearer "))
        assertTrue(header.contains("vault:" + connector.vaultName))
        assertFalse(written.captured.toString().contains("fixture-token"))
        coVerify { manager.reloadServer("somcp") }
    }
}
