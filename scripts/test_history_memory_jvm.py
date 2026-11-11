#!/usr/bin/env python3
"""Compile real history/archive/projection code with cached jars, never Gradle.

Stubs isolate only Android Context/logging and Room database invalidation.
DAO/entity/models and serialization come from existing compiled production classes.
"""
import hashlib
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path.home() / ".gradle/caches/modules-2/files-2.1"
COORDS = {
    "org.jetbrains.kotlin": {"kotlin-compiler-embeddable": "2.2.21", "kotlin-stdlib": "2.2.21",
                             "kotlin-reflect": "2.2.21", "kotlin-script-runtime": "2.2.21"},
    "org.jetbrains.kotlinx": {"kotlinx-coroutines-core-jvm": "1.10.2", "kotlinx-coroutines-test-jvm": "1.10.2",
                              "kotlinx-serialization-core-jvm": "1.8.1", "kotlinx-serialization-json-jvm": "1.8.1"},
    "junit": {"junit": "4.13.2"}, "org.hamcrest": {"hamcrest-core": "1.3"},
    "javax.inject": {"javax.inject": "1"}, "org.jetbrains": {"annotations": "13.0"},
    "com.google.code.gson": {"gson": "2.10.1"},
}
jars = []
for group, artifacts in COORDS.items():
    for artifact, version in artifacts.items():
        found = list((CACHE / group / artifact / version).glob("*/*.jar"))
        if not found:
            raise SystemExit(f"Missing cached dependency: {group}:{artifact}:{version}")
        jars.extend(found)
for group in ("io.mockk", "net.bytebuddy", "org.objenesis"):
    jars.extend((CACHE / group).glob("*/*/*/*.jar"))
compiled = [ROOT / "app/build/tmp/kotlin-classes/armsoloDebug",
            ROOT / "app/build/intermediates/javac/armsoloDebug/compileArmsoloDebugJavaWithJavac/classes",
            ROOT / "core-storage/build/tmp/kotlin-classes/debug", ROOT / "core-agent/build/libs/core-agent.jar"]
cp = ":".join(str(p) for p in [*compiled, *jars])
base = ROOT / "app/src/main/java/com/aharou/feature/agent/domain/session"
tests = ROOT / "app/src/test/java/com/aharou/feature/agent/domain/session"
sources = [base / "MessagePersistenceUseCase.kt", base / "MessageArchiveStore.kt",
           ROOT / "core-agent/src/main/java/com/aharou/feature/agent/domain/tool/ModelProjection.kt",
           tests / "MessagePersistenceArchiveTest.kt", tests / "MessageArchiveStoreTest.kt"]
for source in sources:
    print(f"INPUT {hashlib.sha256(source.read_bytes()).hexdigest()} {source.relative_to(ROOT)}", flush=True)
stubs = {
    "Context.kt": """package android.content
abstract class Context { abstract val filesDir: java.io.File }
""",
    "FileLogger.kt": """package com.aharou.core.util
object FileLogger { fun w(tag: String, message: String, error: Throwable? = null) {} }
""",
    "InvalidationTracker.kt": """package androidx.room
open class InvalidationTracker {
 open fun addObserver(observer: Observer) {}
 abstract class Observer(tables: Array<String>) { abstract fun onInvalidated(tables: Set<String>) }
}
""",
    "AgentDatabase.kt": """package com.aharou.feature.agent.data.local.database
open class AgentDatabase {
 open val invalidationTracker = androidx.room.InvalidationTracker()
 open fun chatSessionDao(): com.aharou.feature.agent.data.local.dao.ChatSessionDao = error("not used")
}
""",
    "ApplicationContext.kt": """package dagger.hilt.android.qualifiers
annotation class ApplicationContext
""",
}
with tempfile.TemporaryDirectory(prefix="aharou-history-jvm-") as directory:
    tmp = Path(directory)
    for name, text in stubs.items():
        path = tmp / name
        path.write_text(text)
        sources.append(path)
    output = tmp / "classes"
    command = ["java", "-Xmx768m", "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
               "-no-stdlib", "-no-reflect", "-classpath", cp, "-d", str(output), *map(str, sources)]
    result = subprocess.run(command, cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    print(result.stdout)
    if result.returncode:
        raise SystemExit("COMPILE_FAILED (not a test red)")
    agents = list((CACHE / "net.bytebuddy/byte-buddy-agent").glob("*/*/*.jar"))
    if not agents:
        raise SystemExit("Missing cached Byte Buddy agent (tests not run)")
    raise SystemExit(subprocess.call([
        "java", "-Xmx256m", f"-javaagent:{agents[0]}", "-cp", str(output) + ":" + cp, "org.junit.runner.JUnitCore",
        "com.aharou.feature.agent.domain.session.MessagePersistenceArchiveTest",
        "com.aharou.feature.agent.domain.session.MessageArchiveStoreTest"], cwd=ROOT))
