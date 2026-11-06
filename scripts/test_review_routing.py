"""Source contracts only; Android compilation and device checks remain separate."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/aharou'


class ReviewRoutingTest(unittest.TestCase):
    def test_hidden_chat_list_does_not_load_history_or_scroll(self):
        panel = (JAVA / 'feature/agent/presentation/component/AIChatPanel.kt').read_text()
        self.assertIn('if (!messagesReady || bodyOverride != null) return@LaunchedEffect', panel)
        self.assertNotIn('LaunchedEffect(listState, messagesReady) {', panel)
        pending = panel[panel.index('// 切换会话 / 打开搜索命中'):]
        pending = pending[:pending.index('// 展开/收起')]
        self.assertIn('bodyOverride != null', pending)

    def test_remote_probe_cannot_publish_stale_root(self):
        manager = (JAVA / 'feature/editor/lsp/EditorLspManager.kt').read_text()
        start = manager.index('private suspend fun attachChecked(')
        publish = manager.index('synchronized(generation)', start)
        self.assertNotIn('activeRemoteRoot =', manager[start:publish])
        self.assertIn('check(generation.get() == epoch)', manager[publish:])
        self.assertIn('fun closeAll(): Unit = synchronized(generation)', manager)

    def test_lsp_does_not_force_builtin_alpine(self):
        provider = (JAVA / 'feature/editor/lsp/ContainerStdioConnectionProvider.kt').read_text()
        self.assertNotIn('ContainerProfile.BUILTIN_ALPINE', provider)
        definition = (JAVA / 'feature/editor/lsp/ContainerLanguageServerDefinition.kt').read_text()
        self.assertIn('profile: ContainerProfile', definition)
        self.assertIn('runtimeProfile = profile', definition)
        manager = (JAVA / 'feature/editor/lsp/EditorLspManager.kt').read_text()
        self.assertIn('profileResolver.forWorkspace(workspace)', manager)
        self.assertIn('profile.mode == ExecutionMode.REMOTE_SSH', manager)
        self.assertIn('projectPath = workspace', manager)


if __name__ == '__main__':
    unittest.main()
