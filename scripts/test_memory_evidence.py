"""Source contracts complement, but do not replace, the Kotlin behavior tests."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MEMORY = ROOT / "app/src/main/java/com/aharou/feature/agent/domain/memory"


class MemoryEvidenceTest(unittest.TestCase):
    def test_relevance_precedes_read_count(self):
        source = (MEMORY / "MemoryRepository.kt").read_text()
        self.assertIn("compareByDescending<PromptCandidate> { it.relevance }", source)
        self.assertNotIn("compareByVerifiedUsage", source)

    def test_identity_quota_excludes_unconfirmed_candidates(self):
        source = (MEMORY / "MemoryRepository.kt").read_text()
        self.assertTrue("it.memory.type == MemoryType.IDENTITY && !it.memory.pending" in source)

    def test_distilled_output_is_pending(self):
        source = (MEMORY / "DistilledEntry.kt").read_text()
        self.assertIn("pending = true,", source)

    def test_confirmed_memories_are_not_overwritten_by_distillation(self):
        source = (MEMORY / "DistilledMemoryWriter.kt").read_text()
        guard = "if (target != null && (!target.pending || !target.name.startsWith(DistilledEntryParser.NAME_PREFIX)))"
        self.assertIn(guard, source)
        self.assertLess(source.index(guard), source.index("memoryRepository.writeMemory("))
        self.assertIn("pending = true,", source)
        self.assertIn("expectedVersion = target?.version ?: MemorySource.VERSION_NEW", source)


if __name__ == "__main__":
    unittest.main()
