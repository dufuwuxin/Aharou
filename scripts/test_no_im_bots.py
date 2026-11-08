"""Source contracts for removing IM bots; not a build or device-runtime test.

Set AHAROU_TEST_ROOT to run these checks against another implementation tree.
"""
import os
import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(os.environ.get("AHAROU_TEST_ROOT", Path(__file__).resolve().parents[1])).resolve()
SRC = ROOT / "app/src/main/java/com/aharou"
SETTINGS = SRC / "feature/settings/presentation"
BOTS = ("TelegramBot", "WeixinBot", "FeishuBot", "QqBot")
ANDROID_NAME = "{http://schemas.android.com/apk/res/android}name"


def source(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class NoImBotsTest(unittest.TestCase):
    def test_bot_implementation_and_dedicated_tests_are_removed(self):
        for directory in (
            SRC / "feature/im",
            ROOT / "app/src/test/java/com/aharou/feature/im",
        ):
            self.assertEqual(list(directory.rglob("*.kt")), [], str(directory))
        for bot in BOTS:
            for path in (
                SETTINGS / f"{bot}ViewModel.kt",
                SETTINGS / f"component/{bot}Section.kt",
            ):
                self.assertFalse(path.exists(), str(path))
        self.assertFalse((SRC / "feature/agent/domain/tool/SendImFileTool.kt").exists())

    def test_production_sources_do_not_reference_im_package_or_classes(self):
        pattern = re.compile(
            r"\bcom\.aharou\.feature\.im\b|\b(?:ConfigImFields|ImServiceRestore|"
            r"SendImFileTool|ImBotBridge|(?:Telegram|Weixin|Feishu|Qq)Bot(?:Service|ViewModel|Section))\b"
        )
        remaining = [
            str(path.relative_to(ROOT))
            for path in SRC.rglob("*.kt")
            if pattern.search(source(path))
        ]
        self.assertEqual(remaining, [])

    def test_manifest_has_no_im_services_but_keeps_other_services(self):
        manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml")
        services = {node.attrib[ANDROID_NAME] for node in manifest.findall(".//service")}
        self.assertFalse(any(".feature.im." in name for name in services))
        self.assertIn(".feature.terminal.domain.TerminalKeepaliveService", services)
        self.assertFalse(any(".feature.remotedevice." in name for name in services))

    def test_startup_has_no_bot_restore_or_config_registration(self):
        app = source(SRC / "AIEditorApp.kt")
        for removed in ("configImFields", "imServiceRestore", "ConfigImFields", "ImServiceRestore"):
            self.assertNotIn(removed, app)
        for retained in (
            "configMcpFields.registerInto(configRegistry)",
            "mcpManager.start()",
            "remoteSshConnection.superviseByExecutionMode",
        ):
            self.assertIn(retained, app)

    def test_tool_registration_removes_only_im_sender(self):
        module = source(SRC / "di/AgentModule.kt")
        self.assertNotRegex(module, r"SendImFileTool|sendImFileTool|register\(\s*\"sendToChat\"")
        for name, instance in (
            ("sendFile", "sendFileTool"),
            ("task", "taskTool"),
            ("messageParent", "messageParentTool"),
            ("manageMcp", "manageMcpTool"),
            ("device", "deviceTool"),
            ("Bash", "executeCommandTool"),
        ):
            self.assertIn(f'register("{name}", {instance})', module)
        self.assertIn("connection: RemoteSshConnection", module)

    def test_settings_have_no_bot_routes_or_entries(self):
        screen = source(SETTINGS / "component/SettingsScreen.kt")
        groups = source(SETTINGS / "component/SettingsGroupScreens.kt")
        for bot in BOTS:
            self.assertNotRegex(screen + groups, rf"\b{bot}\b")
        for section in ("Connectors", "Teams", "Tools"):
            self.assertIn(f"onOpen(SettingsSection.{section})", groups)
        self.assertNotIn("SettingsSection.RemoteDevice", screen)

    def test_existing_file_to_chat_and_remote_transports_remain(self):
        self.assertIn("onSendToChat =", source(SRC / "MainActivity.kt"))
        self.assertIn("onAnalyzeWithAi = onSendToChat", source(SRC / "WorkbenchPane.kt"))
        for path in (
            "feature/agent/domain/mcp/McpManager.kt",
            "feature/agent/domain/container/RemoteSshConnection.kt",
        ):
            self.assertTrue((SRC / path).is_file(), path)


if __name__ == "__main__":
    unittest.main()
