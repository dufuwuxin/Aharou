"""Offline source-contract gates; JVM fixtures are executed separately by Gradle."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTAINER = ROOT / "app/src/main/java/com/aharou/feature/agent/domain/container"


def source(name):
    path = CONTAINER / name
    return path.read_text() if path.exists() else ""


class NativeRuntimeSourceTest(unittest.TestCase):
    def test_builtin_identity_and_asset(self):
        text = source("ContainerProfile.kt")
        self.assertIn('BUILTIN_ID = "builtin-ubuntu"', text)
        self.assertIn('LEGACY_BUILTIN_ID = "builtin-alpine"', text)
        self.assertIn('RootfsSource.Asset("container/arm/ubuntu-rootfs.bin")', text)
        self.assertNotIn("BUILTIN_ALPINE", text)

    def test_no_old_default_consumers(self):
        paths = list((ROOT / "app/src/main/java").rglob("*.kt"))
        paths += list((ROOT / "app/src/test/java").rglob("*.kt"))
        offenders = [str(p.relative_to(ROOT)) for p in paths if "BUILTIN_ALPINE" in p.read_text()]
        self.assertEqual([], offenders)

    def test_native_checks_are_not_filename_based(self):
        text = source("NativeArm64Rootfs.kt")
        for invariant in ('"/bin/sh"', '"/etc/os-release"', "183", "NOFOLLOW_LINKS", "readSymbolicLink"):
            self.assertIn(invariant, text)
        self.assertRegex(text, r"header\[4\].*2")
        self.assertIn('"alpine"', text)

    def test_every_local_start_uses_native_gate(self):
        text = source("LinuxContainerEngine.kt")
        body = text.split("private fun buildBaseProotArgv(", 1)[1].split("val argv", 1)[0]
        self.assertIn("NativeArm64Rootfs.requireSupported", body)

    def test_staging_does_not_purge_legacy_first(self):
        text = source("ContainerInstaller.kt")
        body = text.split("private suspend fun installBuiltinRootfs(", 1)[1].split("private fun usableSpaceMb", 1)[0]
        self.assertIn("migration.prepareStage()", body)
        self.assertIn("verifyRootfsReady(stage)", body)
        self.assertIn("migration.commit(", body)
        self.assertLess(body.index("verifyRootfsReady(stage)"), body.index("migration.commit("))
        self.assertNotIn("purgeRootfs(rootfsDir", body)

    def test_home_only_migration_and_nofollow_cleanup(self):
        text = source("BuiltinContainerMigration.kt")
        self.assertIn('listOf("root", "home")', text)
        self.assertIn("NOFOLLOW_LINKS", text)
        self.assertIn('".aharou"', text)
        self.assertIn('"shared"', text)
        self.assertIn("Files.walkFileTree", text)

    def test_catalog_sanitizes_all_input_tiers(self):
        text = source("ContainerImageCatalog.kt")
        parser = text.split("internal fun parseContainerImageCatalog", 1)[1]
        self.assertIn("nativeArm64Only()", parser)
        self.assertIn('abiNames.filterKeys { it == ContainerImageCatalog.CURRENT_ABI }', text)
        self.assertIn('sha256ByAbi.filterKeys { it == ContainerImageCatalog.CURRENT_ABI }', text)
        self.assertIn('if (abi != CURRENT_ABI', text)

    def test_preferences_normalize_legacy_ids_and_sources(self):
        text = (ROOT / "app/src/main/java/com/aharou/feature/settings/data/repository/ContainerSettingsRepository.kt").read_text()
        self.assertGreaterEqual(text.count("ContainerProfile.normalizeId("), 6)
        self.assertIn("migrateBuiltinPreferences(prefs)", text)
        self.assertIn("normalizeBuiltin()", text)
        self.assertTrue(re.search(r"migrateBuiltinPreferences\(prefs\).*?INITIALIZED_KEY", text, re.DOTALL))


if __name__ == "__main__":
    unittest.main()
