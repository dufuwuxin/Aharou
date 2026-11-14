#!/usr/bin/env python3
"""Pre-package plugin patrol: consume fresh real-runtime JUnit and build SDK fixtures."""

import argparse
import hashlib
import json
import os
import struct
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONSUMER_CASES = {
    "HostPluginBridgeTest": {"missingContextOrRoleSnapshotCannotInvokeGlobalTools",
                             "allowedSnapshotStillRunsPermissionPolicyAndContextualTool"},
    "HostPluginRuntimeTest": {"failedStartupNeverReportsLoaded", "toolConsumerUsesMetadataAndUnloadRevokesOnlyOwnedTools",
                              "failingReplacementAndHookKeepOldGeneration"},
    "HostPluginHttpInterceptorTest": {"frameReaderPreservesDoneCommentsAndOnlyTransformsConsumedFrames",
                                     "realHttpConsumerChangesModelBodiesButLeavesLoginAlone",
                                     "authenticationRoutesRemainUntouchedEvenWithModelShapedBodies",
                                     "hookErrorsAndMalformedResultsPreservePayload",
                                     "prettyHookToolArgumentsAndUsageRemainValidSseData"},
}
CASES = {
    "realJavascriptReachesToolHttpSseConsumersAndUnloadRevokesOnlyItsTools",
    "malformedJavascriptCannotReportAHealthyLoadedPlugin",
}


def read_runtime_results(directory, not_before):
    reports = list(Path(directory).glob("TEST-*.PluginPatrolTest.xml"))
    if len(reports) != 1:
        raise ValueError("Exactly one PluginPatrolTest report is required")
    path = reports[0]
    if path.stat().st_mtime < not_before:
        raise ValueError("Plugin patrol report predates this packaging attempt")
    suite = ET.parse(path).getroot()
    tests = suite.findall("testcase")
    names = [case.get("name", "").removesuffix("()") for case in tests]
    if len(names) != len(CASES) or set(names) != CASES:
        raise ValueError("Plugin patrol cases are missing, duplicated or replaced")
    if int(suite.get("tests", "0")) != len(CASES):
        raise ValueError("Plugin patrol suite count does not match actual cases")
    if any(int(suite.get(key, "0")) for key in ("failures", "errors", "skipped")):
        raise ValueError("Plugin patrol includes failed, errored or skipped cases")
    if any(case.find(tag) is not None for case in tests for tag in ("failure", "error", "skipped")):
        raise ValueError("Plugin patrol contains an unsuccessful testcase")
    consumers = {}
    for name, required in CONSUMER_CASES.items():
        files = list(Path(directory).glob(f"TEST-*.{name}.xml"))
        if len(files) != 1 or files[0].stat().st_mtime < not_before:
            raise ValueError(f"Fresh {name} patrol results are required")
        result = ET.parse(files[0]).getroot()
        entries = result.findall("testcase")
        actual = {entry.get("name", "").removesuffix("()") for entry in entries}
        if not required <= actual or int(result.get("tests", "0")) != len(entries):
            raise ValueError(f"{name} patrol coverage is incomplete")
        if any(int(result.get(key, "0")) for key in ("failures", "errors", "skipped")) or any(
                entry.find(tag) is not None for entry in entries for tag in ("failure", "error", "skipped")):
            raise ValueError(f"{name} patrol did not fully pass")
        consumers[name] = {"tests": len(entries), "junit_sha256": hashlib.sha256(files[0].read_bytes()).hexdigest()}
    return {"cases": sorted(names), "junit_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "consumers": consumers}


def sdk_tools():
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME", ""))
    java = Path(os.environ.get("JAVA_HOME", "")) / "bin" / "javac"
    tools = [p for p in (sdk / "build-tools").glob("*/d8") if all(v.isdigit() for v in p.parent.name.split("."))]
    platforms = [p for p in (sdk / "platforms").glob("android-*/android.jar") if p.parent.name.removeprefix("android-").isdigit()]
    if not java.is_file() or not tools or not platforms:
        raise ValueError("Plugin patrol requires installed javac, D8 and android.jar; nothing is auto-installed")
    d8 = max(tools, key=lambda p: tuple(int(v) for v in p.parent.name.split(".")))
    android = max(platforms, key=lambda p: int(p.parent.name.removeprefix("android-")))
    return java, d8, android


def dex_defined_classes(data):
    if len(data) < 112 or not data.startswith(b"dex\n"):
        raise ValueError("Invalid DEX header")
    strings_size, strings_offset = struct.unpack_from("<II", data, 56)
    types_size, types_offset = struct.unpack_from("<II", data, 64)
    classes_size, classes_offset = struct.unpack_from("<II", data, 96)
    classes = set()
    for index in range(classes_size):
        type_index = struct.unpack_from("<I", data, classes_offset + index * 32)[0]
        if type_index >= types_size:
            raise ValueError("Invalid DEX class type index")
        string_index = struct.unpack_from("<I", data, types_offset + type_index * 4)[0]
        if string_index >= strings_size:
            raise ValueError("Invalid DEX descriptor index")
        offset = struct.unpack_from("<I", data, strings_offset + string_index * 4)[0]
        for _ in range(5):
            byte = data[offset]
            offset += 1
            if byte < 128:
                break
        else:
            raise ValueError("Invalid DEX string length")
        classes.add(data[offset:data.index(bytes([0]), offset)].decode("ascii"))
    return classes


def compile_fixtures():
    javac, d8, android = sdk_tools()
    cli = ROOT / "app/src/main/assets/plugin-sdk/build_plugin.py"
    examples = cli.parent / "examples"
    records = {}
    with tempfile.TemporaryDirectory(prefix="aharou-plugin-patrol-") as folder:
        for runtime in ("java", "js"):
            output = Path(folder) / (runtime + ".txplugin")
            command = ["python3", str(cli), "--source-dir", str(examples / runtime),
                       "--manifest", str(examples / runtime / "manifest.json"), "--output", str(output)]
            if runtime == "java":
                command += ["--sdk-src", str(ROOT / "app/src/main/java"), "--javac", str(javac),
                            "--d8", str(d8), "--android-jar", str(android)]
            result = subprocess.run(command, capture_output=True, text=True, timeout=120)
            if result.returncode:
                raise RuntimeError(f"{runtime} fixture compilation failed with exit {result.returncode}: {result.stderr[-2000:]}")
            with zipfile.ZipFile(output) as package:
                manifest = json.loads(package.read("manifest.json"))
                spec = manifest["hostRuntime"]
                if runtime == "java":
                    if spec["runtime"] != "dex" or not package.read("payload/classes.dex").startswith(b"dex\n"):
                        raise ValueError("Compiler did not produce a real DEX plugin")
                    classes = dex_defined_classes(package.read("payload/classes.dex"))
                    if "Lexample/ExamplePlugin;" not in classes:
                        raise ValueError("DEX does not define its declared entry")
                    if any(name.startswith("Lcom/aharou/plugin/api/") for name in classes):
                        raise ValueError("Plugin must not bundle host SDK definitions")
                elif spec["runtime"] != "js" or not package.read("payload/plugin.js"):
                    raise ValueError("JavaScript plugin package is empty")
            records[runtime] = {"bytes": output.stat().st_size,
                                "sha256": hashlib.sha256(output.read_bytes()).hexdigest()}
    return records


def run_runtime(java, classpath_file, results, method_filter):
    classpath = classpath_file.read_text().strip()
    if not classpath:
        raise ValueError("Plugin patrol runtime classpath is empty")
    entries = classpath.split(os.pathsep)
    agent = next((entry for entry in entries if Path(entry).name.startswith("byte-buddy-agent")
                  and entry.endswith(".jar")), None)
    if agent is None:
        raise ValueError("Plugin patrol requires its existing Byte Buddy test agent")
    command = [str(java), "-Xshare:off", f"-javaagent:{agent}", "-Djdk.attach.allowAttachSelf=true",
               "-cp", classpath, "com.aharou.feature.plugin.PluginPatrolRunner", str(results)]
    if method_filter:
        command.append(method_filter)
    result = subprocess.run(command, capture_output=True, text=True, timeout=120)
    if result.stdout:
        print(result.stdout, end="")
    if result.stderr:
        print(result.stderr[-4000:], end="")
    return result.returncode


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results", required=True, type=Path)
    freshness = parser.add_mutually_exclusive_group()
    freshness.add_argument("--not-before", type=float)
    freshness.add_argument("--started-file", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--classpath-file", type=Path)
    parser.add_argument("--java", type=Path)
    parser.add_argument("--case-filter")
    args = parser.parse_args()
    report = {"schemaVersion": 1, "status": "failed", "createdAtEpoch": time.time(),
              "scope": "controlled repository fixtures, never user-installed plugins",
              "runtimePlatform": "host JVM with native QuickJS; not Android device execution",
              "androidDexLoading": "unverified: requires Android device runtime",
              "nativeViewInteraction": "unverified: requires Android device interaction"}
    try:
        if args.classpath_file:
            if args.java is None:
                raise ValueError("Actual patrol execution requires an explicit Java runtime")
            not_before = time.time()
            report["runtimeExitCode"] = run_runtime(args.java, args.classpath_file, args.results, args.case_filter)
        else:
            not_before = float(args.started_file.read_text()) if args.started_file else args.not_before
            if not_before is None:
                raise ValueError("Report-only verification requires a freshness bound")
        report["runtime"] = read_runtime_results(args.results, not_before)
        if report.get("runtimeExitCode", 0) != 0:
            raise ValueError("Actual JUnit plugin patrol did not exit successfully")
        report["packages"] = compile_fixtures()
        sources = [ROOT / "app/build.gradle.kts", Path(__file__).resolve(),
                   ROOT / "app/src/main/assets/plugin-sdk/build_plugin.py",
                   ROOT / "app/src/test/java/com/aharou/feature/plugin/PluginPatrolTest.kt",
                   ROOT / "app/src/test/java/com/aharou/feature/plugin/PluginPatrolRunner.kt"]
        sources += [ROOT / f"app/src/test/java/com/aharou/feature/plugin/{name}.kt" for name in CONSUMER_CASES]
        sources += sorted((ROOT / "app/src/main/java/com/aharou/feature/plugin/runtime").glob("*.kt"))
        sources += sorted((ROOT / "app/src/main/java/com/aharou/plugin/api").glob("*.java"))
        report["sourceSha256"] = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                                 for path in sources}
        report["status"] = "passed_with_device_checks_unverified"
    except Exception as failure:
        report["failure"] = {"type": type(failure).__name__, "message": str(failure)}
        raise
    finally:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
