import errno
import json
import os
import pty
import re
import select
import shutil
import subprocess
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCES = [ROOT / "app/src/main/java/com/aharou/feature/terminal/domain" / filename
           for filename in ("TerminalSessionManager.kt", "RemoteTerminalSessionManager.kt")]
MARKER = re.compile(r"(?:^|[\r\n])\[command exited: (\d+)\](?:\r?\n|$)")


def production_script(source, command, after_command="; exit $ec"):
    match = re.search(r'"\{ \$command\\n\}; ec=.*?\$afterCommand"', source.read_text())
    if match is None:
        raise AssertionError(f"Production command wrapper not found in {source}")
    literal = match.group().replace(r"\$", "$")
    template = json.loads(literal)
    return template.replace("$afterCommand", after_command).replace("$command", command)


def run_pty(script, stdin="", followup=None):
    master, slave = pty.openpty()
    process = subprocess.Popen(["sh"], stdin=slave, stdout=slave, stderr=slave, close_fds=True)
    os.close(slave)
    chunks = []
    try:
        os.write(master, (script + "\n" + stdin).encode())
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if select.select([master], [], [], 0.1)[0]:
                try:
                    chunk = os.read(master, 65536)
                except OSError as error:
                    if error.errno == errno.EIO:
                        break
                    raise
                if not chunk:
                    break
                chunks.append(chunk)
                if followup is not None and MARKER.search(b"".join(chunks).decode(errors="replace")):
                    os.write(master, (followup + "\n").encode())
                    followup = None
            elif process.poll() is not None:
                break
        return process.wait(timeout=1), b"".join(chunks).decode(errors="replace")
    finally:
        os.close(master)
        if process.poll() is None:
            process.kill()
            process.wait()


class BackgroundShellTest(unittest.TestCase):
    def assert_result(self, result_code, output, expected):
        self.assertEqual(result_code, expected, output)
        markers = MARKER.findall(output)
        self.assertTrue(markers, output)
        self.assertEqual(int(markers[-1]), expected, output)

    def test_no_newline_output_keeps_failure_marker_on_its_own_line(self):
        for source in SOURCES:
            with self.subTest(source=source.name):
                result = subprocess.run(["sh", "-c", production_script(source, "printf tail; (exit 7)")],
                                        capture_output=True, text=True, timeout=5)
                self.assert_result(result.returncode, result.stdout, 7)

    def test_heredoc_comment_and_multiline_commands(self):
        python = shutil.which("python3")
        self.assertIsNotNone(python)
        cases = [(f"'{python}' - <<'PY'\nprint('heredoc-ok')\nPY", "heredoc-ok"),
                 ("printf 'comment-ok\\n' # trailing comment", "comment-ok"),
                 ("printf '%s\\n' 'quoted\nsecond'", "quoted\nsecond"),
                 ("printf 'background-ok\\n' &\nwait", "background-ok")]
        for source in SOURCES:
            for command, expected in cases:
                with self.subTest(source=source.name, command=command):
                    result = subprocess.run(["sh", "-c", production_script(source, command)],
                                            capture_output=True, text=True, timeout=5)
                    self.assert_result(result.returncode, result.stdout, 0)
                    self.assertIn(expected, result.stdout)
                    self.assertNotIn("here-document", result.stderr)

    def test_interactive_pty_keeps_heredoc_and_failure_status(self):
        for source in SOURCES:
            for command, expected in (("cat <<'EOF'\npty-heredoc\nEOF", 0),
                                      ("printf pty-tail; (exit 9)", 9)):
                with self.subTest(source=source.name, command=command):
                    code, output = run_pty(production_script(source, command))
                    self.assert_result(code, output, expected)

    def test_interactive_read_receives_data_not_completion_script(self):
        for source in SOURCES:
            command = 'read -r value; [ "$value" = "pty-stdin-ok" ]'
            code, output = run_pty(production_script(source, command), stdin="pty-stdin-ok\n")
            self.assert_result(code, output, 0)

    def test_non_notify_shell_remains_available_after_completion_marker(self):
        for source in SOURCES:
            script = production_script(source, "printf keep-alive", after_command="; exec /bin/sh")
            code, output = run_pty(script, followup="printf 'followup-ok\\n'; exit 11")
            self.assertEqual(code, 11, output)
            self.assertEqual(MARKER.findall(output)[-1], "0", output)
            self.assertIn("followup-ok", output)

    def test_stdin_consumer_does_not_consume_completion_script(self):
        for source in SOURCES:
            command = "read -r value; printf '%s\\n' \"$value\""
            result = subprocess.run(["sh", "-c", production_script(source, command)],
                                    input="stdin-ok\n", capture_output=True, text=True, timeout=5)
            self.assert_result(result.returncode, result.stdout, 0)
            self.assertIn("stdin-ok", result.stdout)


if __name__ == "__main__":
    unittest.main()
