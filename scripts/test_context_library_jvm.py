#!/usr/bin/env python3
"""Offline JVM fixtures; only cached jars and temporary test files, never Gradle."""
import argparse
import subprocess
import tempfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--baseline", action="store_true")
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
names = ["kotlin-compiler-embeddable/2.1.0", "kotlin-stdlib/2.1.0", "kotlin-reflect/2.1.0",
         "kotlin-script-runtime/2.1.0", "trove4j/1.0.20200330", "kotlinx-coroutines-core-jvm/1.9.0",
         "annotations/13.0", "junit/4.13.2", "hamcrest-core/1.3", "snakeyaml/2.4", "javax.inject/1"]
jars = [str(p) for p in cache.glob("**/*.jar") if any("/" + n + "/" in str(p) for n in names)]
main = root / "app/src/main/java/com/aharou"
test = root / "app/src/test/java/com/aharou/feature/agent/domain"
files = [main / "feature/agent/domain" / p for p in [
    "context/ContextEntry.kt", "context/ContextLibraryPlanner.kt",
    "memory/Memory.kt", "memory/MemoryParser.kt", "memory/MemoryEntities.kt", "memory/MemoryFileStorage.kt", "memory/MemorySource.kt", "memory/MemoryProposalStore.kt",
    "common/Frontmatter.kt", "memory/MemoryNaming.kt"]]
files += [main / "core/util" / p for p in ["SecretMasker.kt", "TextSanitizer.kt"]]
files += [main / "feature/settings/presentation/ContextLibraryNavigation.kt",
          main / "feature/settings/presentation/SettingsNavigation.kt",
          root / "app/src/test/java/com/aharou/feature/settings/presentation/ContextLibraryNavigationTest.kt",
          root / "app/src/test/java/com/aharou/feature/settings/presentation/SettingsNavigationTest.kt"]
files += [test / "context/ContextLibraryPlannerTest.kt", test / "memory/MemoryParserTest.kt",
          test / "memory/MemoryStorageReliabilityTest.kt", test / "memory/MemoryStorageFolderBoundaryTest.kt",
          test / "memory/MemoryProposalStoreTest.kt"]
with tempfile.TemporaryDirectory(prefix="aharou-context-jvm-") as tmp:
    tmp = Path(tmp)
    stubs = {
        "FileLogger.kt": """package com.aharou.core.util
object FileLogger {
 fun w(tag:String, message:String, error:Throwable? = null) {}
 fun e(tag:String, message:String, error:Throwable? = null) {}
}
""",
        "ContainerInstaller.kt": """package com.aharou.feature.agent.domain.container
class ContainerInstaller(val aharouDir: java.io.File)
""",
    }
    for name, content in stubs.items():
        path = tmp / name
        path.write_text(content)
        files.append(path)
    if args.baseline:
        # Compile the same current assertions against immutable baseline sources.
        base = tmp / "baseline"
        baseline_files = []
        for path in files:
            if path.is_relative_to(main):
                relative = path.relative_to(root)
                result = subprocess.run(["git", "show", "8c48e927:" + str(relative)], cwd=root,
                                        text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                if result.returncode:
                    continue
                target = base / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(result.stdout)
                baseline_files.append(target)
            else:
                baseline_files.append(path)
        files = baseline_files
    cp = ":".join(jars)
    output = tmp / "classes"
    command = ["java", "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
               "-classpath", cp, "-d", str(output)] + [str(p) for p in files]
    result = subprocess.run(command, cwd=root, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    print("\n".join(result.stdout.splitlines()[:45]))
    if args.baseline:
        assert result.returncode != 0 and "unresolved reference" in result.stdout.lower(), "baseline unexpectedly passed"
        print("EXPECTED_RED: new contract absent at 8c48e927")
    else:
        if result.returncode:
            raise SystemExit(result.returncode)
        classes = ["context.ContextLibraryPlannerTest", "memory.MemoryParserTest", "memory.MemoryStorageReliabilityTest", "memory.MemoryStorageFolderBoundaryTest", "memory.MemoryProposalStoreTest"]
        classes = ["com.aharou.feature.agent.domain." + c for c in classes] + [
            "com.aharou.feature.settings.presentation.ContextLibraryNavigationTest",
            "com.aharou.feature.settings.presentation.SettingsNavigationTest"]
        raise SystemExit(subprocess.call(["java", "-cp", str(output) + ":" + cp, "org.junit.runner.JUnitCore"] + classes, cwd=root))
