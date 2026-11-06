"""Source contracts supplement, but do not replace, device interaction tests."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTROLLER = ROOT / "app/src/main/java/com/aharou/feature/agent/domain/vdisplay/VdController.kt"
RUNNER = ROOT / "tools/aharou-vd/VdMain.java"


class VdisplayInputContractTest(unittest.TestCase):
    def test_fallback_shell_failure_is_not_success(self):
        source = CONTROLLER.read_text()
        start = source.index("private suspend fun execInput(")
        end = source.index("\n    }", start)
        self.assertIn("exitCode == 0", source[start:end])
        inputs = source[source.index("suspend fun tap(x:"):source.index("private fun parseNodes(")]
        self.assertNotIn('exec("input ', inputs)
        self.assertIn("execInput(", inputs)

    def test_runner_error_is_not_retried_as_another_action(self):
        source = CONTROLLER.read_text()
        for action in ("tap", "swipe", "key", "settext"):
            with self.subTest(action=action):
                self.assertIn(f'inputCommand("{action}"', source)
        helper = source[source.index("private suspend fun inputCommand("):]
        helper = helper[:helper.index("\n    }")]
        self.assertIn("is CtlResult.Err -> error(", helper)

    def test_ambiguous_timeout_cannot_fall_back_and_duplicate_action(self):
        source = CONTROLLER.read_text()
        start = source.index('File(dir, "cmd-$seq.txt").delete()')
        end = source.index("\n    }", start)
        self.assertIn('return CtlResult.Err(', source[start:end])

    def test_wake_is_display_scoped_and_does_not_unlock_host(self):
        source = RUNNER.read_text()
        self.assertTrue('"wakeUpWithDisplayId"' in source)
        self.assertTrue('if (displayId <= 0) return' in source)
        self.assertFalse('"wakeUp"' in source)
        self.assertFalse('"goToSleep"' in source)

    def test_final_frame_is_not_discarded_by_copy_throttle(self):
        source = RUNNER.read_text()
        self.assertFalse('FRAME_COPY_MIN_INTERVAL_MS' in source)
        self.assertTrue('frameSequence++' in source)
        self.assertTrue(' ageMs=' in source and ' display=' in source)
        self.assertTrue('SystemClock.uptimeMillis() - lastCopyAt' in source)

    def test_node_reads_refresh_the_accessibility_cache(self):
        source = RUNNER.read_text()
        self.assertTrue('getMethod("clearCache")' in source)
        self.assertTrue('root != null && root.refresh()' in source)

    def test_start_does_not_reuse_pre_fix_runner(self):
        source = CONTROLLER.read_text()
        self.assertTrue('"protocol=2" in ping.body' in source)
        self.assertTrue('uiautomation protocol=2' in RUNNER.read_text())
        tool = (ROOT / "app/src/main/java/com/aharou/feature/agent/domain/tool/vdisplay/VdTool.kt").read_text()
        prepare = tool[tool.index("override suspend fun prepare("):]
        prepare = prepare[:prepare.index("\n    }")]
        self.assertTrue("vdController.start()" in prepare)
        self.assertFalse("vdController.refresh()" in prepare)

    def test_modern_input_manager_is_selected_when_available(self):
        source = RUNNER.read_text()
        start = source.index('private static Object inputManager()')
        end = source.index('    // -----', start)
        body = source[start:end]
        self.assertLess(body.index('Class.forName("android.hardware.input.InputManagerGlobal")'),
                        body.index('Class.forName("android.hardware.input.InputManager")'))
        self.assertTrue('catch (ClassNotFoundException e)' in body)


if __name__ == "__main__":
    unittest.main()
