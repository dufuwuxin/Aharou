import subprocess
import unittest
from pathlib import Path


class BuildIntentTest(unittest.TestCase):
    def test_exact_gate_case_recognizes_build_and_rejects_unrelated_permission(self):
        source = Path(__file__).resolve().parents[1].joinpath('gradlew').read_text()
        case = source[source.index('  case "$_quote" in'):source.index('  # commit 绑定')]
        for quote, allowed in [('完成后先构建 ci ，成功后再发布 release', True), ('编译', True), ('可以', False), ('检查项目', False)]:
            result = subprocess.run(['sh', '-c', '_quote="$1"; ' + case, 'fixture', quote], capture_output=True)
            self.assertEqual(allowed, result.returncode == 0, quote)


if __name__ == '__main__':
    unittest.main()
