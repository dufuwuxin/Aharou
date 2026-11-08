"""Source contracts for removing peer collaboration without removing SSH or MCP."""
import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/aharou"


class NoRemoteDeviceTest(unittest.TestCase):
    def test_dedicated_implementation_and_tests_are_removed(self):
        for base in ("app/src/main/java", "app/src/test/java"):
            self.assertEqual(list((ROOT / base / "com/aharou/feature/remotedevice").rglob("*.kt")), [])

    def test_no_production_reference_or_runtime_bypass_remains(self):
        pattern = re.compile(r"\bremotedevice\b|\bRemoteDevice\w*\b|\bremoteDevice\w*\b|\bcollaborationActive\b|\ballowEscape\b")
        remaining = [str(p.relative_to(ROOT)) for p in SRC.rglob("*.kt") if pattern.search(p.read_text())]
        self.assertEqual(remaining, [])

    def test_manifest_does_not_start_peer_service(self):
        names = [node.attrib.get("{http://schemas.android.com/apk/res/android}name", "")
                 for node in ET.parse(ROOT / "app/src/main/AndroidManifest.xml").iter()]
        self.assertFalse(any("remotedevice" in name for name in names))
        self.assertIn(".feature.terminal.domain.TerminalKeepaliveService", names)

    def test_settings_resources_and_navigation_are_removed(self):
        resources = ET.parse(ROOT / "app/src/main/res/values/strings.xml")
        self.assertEqual([node.attrib["name"] for node in resources.getroot()
                          if re.match(r"(?:settings_)?remote_device", node.attrib.get("name", ""))], [])
        self.assertNotIn("/guide/cross-device", (ROOT / "docs-site/.vitepress/config.ts").read_text())
        self.assertFalse((ROOT / "docs-site/docs/guide/cross-device.md").exists())

    def test_ssh_mcp_and_memory_sync_remain(self):
        for path in ("feature/agent/domain/mcp/McpManager.kt",
                     "feature/agent/domain/container/RemoteSshConnection.kt",
                     "feature/agent/domain/memory/MemorySyncManager.kt",
                     "feature/workspace/domain/RemoteSftpFileAccess.kt"):
            self.assertTrue((SRC / path).is_file(), path)
        app = (SRC / "AIEditorApp.kt").read_text()
        self.assertIn("mcpManager.start()", app)
        self.assertIn("configMemorySyncFields.registerInto(configRegistry)", app)
        self.assertIn("remoteSshConnection.superviseByExecutionMode", app)


if __name__ == "__main__":
    unittest.main()
