package com.aharou.feature.agent.domain.skill.market

import com.aharou.feature.agent.domain.container.ContainerInstaller
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillInstallRepositoryTest {
    @Test
    fun sameSourceUpdatesOnlyWhenInstalledContentIsUnmodified() {
        val installed = SkillInstallRecord("catalog", "1", 5, "fingerprint-a")
        assertTrue(mayOverwriteUnmodifiedMarketSkill("fingerprint-a", installed, "catalog"))
        assertFalse(mayOverwriteUnmodifiedMarketSkill("fingerprint-b", installed, "catalog"))
        assertFalse(mayOverwriteUnmodifiedMarketSkill("fingerprint-a", installed, "other"))
        assertFalse(mayOverwriteUnmodifiedMarketSkill("fingerprint-a", SkillInstallRecord("catalog", "1", 5), "catalog"))
        assertFalse(mayOverwriteUnmodifiedMarketSkill(null, installed, "catalog"))
    }
    @Test
    fun oldInstallJsonDefaultsFingerprintToEmptyAndNewFingerprintRoundTrips() {
        val root = java.nio.file.Files.createTempDirectory("skill-install-record").toFile()
        try {
            val installer = mockk<ContainerInstaller>()
            every { installer.aharouDir } returns root
            val file = File(root, "skill-installs.json")
            file.writeText("""{"installed":{"sample":{"source":"catalog","version":"1","installedAt":5}}}""")
            val repository = SkillInstallRepository(installer)

            assertEquals("", repository.record("sample")?.installedFingerprint)
            repository.save("sample", SkillInstallRecord("catalog", "2", 6, "abc123"))

            assertEquals("abc123", repository.record("sample")?.installedFingerprint)
            assertNotNull(file.readText().takeIf { it.contains("installedFingerprint") })
        } finally {
            root.deleteRecursively()
        }
    }
}
