"""Isolated contracts for seed provenance; never writes the application repository."""

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import check_builtin_seed_history as seeds


class BuiltinSeedHistoryTest(unittest.TestCase):
    def test_manifest_rejects_ambiguous_or_unsafe_evidence(self):
        valid = {"version": 1, "sources": ["a" * 40], "files": {"agents/a.md": ["b" * 64]}}
        seeds.validate_format(valid)
        for value in (
            {**valid, "version": True},
            {**valid, "sources": ["short"]},
            {**valid, "files": {"agents/../a.md": ["b" * 64]}},
            {**valid, "files": {"agents/a.md": ["b" * 64, "b" * 64]}},
            {**valid, "files": {"teams/a.md": []}},
        ):
            with self.subTest(value=value), self.assertRaises(ValueError):
                seeds.validate_format(value)
        with self.assertRaises(ValueError):
            json.loads('{"version":1,"version":1}', object_pairs_hook=seeds.unique_object)

    def test_all_seed_blobs_required_but_unrelated_blobs_are_out_of_scope(self):
        with tempfile.TemporaryDirectory(prefix="seed-history-fixture-") as folder:
            repo = Path(folder)
            subprocess.run(["git", "init", "--quiet", str(repo)], check=True)
            env = dict(os.environ, GIT_AUTHOR_NAME="Fixture", GIT_AUTHOR_EMAIL="fixture@example.invalid",
                       GIT_COMMITTER_NAME="Fixture", GIT_COMMITTER_EMAIL="fixture@example.invalid")

            def git(*args, content=None):
                return subprocess.check_output(["git", "-C", str(repo), *args], input=content, env=env).decode().strip()

            sample = repo / "app/src/main/assets/agents/a.md"
            sample.parent.mkdir(parents=True)
            sample.write_text("seed")
            (repo / "unrelated.txt").write_text("unrelated blob")
            git("add", "app", "unrelated.txt")
            tree = git("write-tree")
            commit = git("commit-tree", tree, content=b"isolated fixture\n")
            git("update-ref", "HEAD", commit)
            seed_blob = git("rev-parse", "HEAD:app/src/main/assets/agents/a.md")
            unrelated_blob = git("rev-parse", "HEAD:unrelated.txt")
            with patch.object(seeds, "BASE", commit):
                expected = seeds.history(repo)
                self.assertEqual(set(expected["files"]), {"agents/a.md"})
                (repo / ".git/objects" / unrelated_blob[:2] / unrelated_blob[2:]).unlink()
                self.assertEqual(seeds.history(repo), expected)
                (repo / ".git/objects" / seed_blob[:2] / seed_blob[2:]).unlink()
                with self.assertRaises(subprocess.CalledProcessError):
                    seeds.history(repo)


if __name__ == "__main__":
    unittest.main()
