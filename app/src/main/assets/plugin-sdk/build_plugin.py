#!/usr/bin/env python3
"""Build trusted SDKv1 host plugins offline. This tool never downloads a compiler.

JS: --source-dir examples/js --manifest examples/js/manifest.json --output demo.txplugin
DEX: add --sdk-src src --javac /jdk/bin/javac --d8 /sdk/build-tools/35.0.0/d8
or --r8-jar /tools/r8.jar --java /jdk/bin/java. JAVA_HOME/JAVAC/D8/R8_JAR are
also supported. Entry and artifact paths are relative to the package's payload/.
Plugins run with the host UID, not in a security sandbox. Packaging does not
validate runtime behavior, grant Manifest permissions, or change native ABIs.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path, PurePosixPath

SDK_PACKAGE = "com/aharou/plugin/api"


class BuildError(Exception):
    """An actionable input or toolchain error."""


def relative_path(value: object, label: str) -> str:
    if not isinstance(value, str) or not value or "\\" in value or "\x00" in value:
        raise BuildError(f"{label} must be a nonempty POSIX relative path")
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in ("", ".", "..") for part in value.split("/")) or ":" in value:
        raise BuildError(f"{label} must stay within payload/: {value!r}")
    return value


def load_manifest(path: Path) -> tuple[bytes, dict]:
    raw = path.read_bytes()
    manifest = json.loads(raw)
    if not isinstance(manifest, dict):
        raise BuildError("manifest must be a JSON object")
    if type(manifest.get("schemaVersion", 1)) is not int or manifest.get("schemaVersion", 1) != 1:
        raise BuildError("only schemaVersion 1 is supported")
    if not isinstance(manifest.get("id"), str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", manifest["id"]):
        raise BuildError("manifest id must contain only letters, digits, dots, underscores or hyphens")
    for key in ("name", "version"):
        if not isinstance(manifest.get(key), str) or not manifest[key].strip():
            raise BuildError(f"manifest requires {key}")
    host = manifest.get("hostRuntime")
    if not isinstance(host, dict) or type(host.get("apiVersion")) is not int or host["apiVersion"] != 1:
        raise BuildError("hostRuntime.apiVersion must be 1")
    if host.get("runtime") == "js":
        relative_path(host.get("entry"), "hostRuntime.entry")
    elif host.get("runtime") == "dex":
        if not isinstance(host.get("entry"), str) or not re.fullmatch(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*", host["entry"], flags=re.ASCII):
            raise BuildError("hostRuntime.entry must be a fully qualified Java class name")
        artifact = relative_path(host.get("artifact"), "hostRuntime.artifact")
        if not artifact.endswith(".dex"):
            raise BuildError("hostRuntime.artifact must end in .dex")
    else:
        raise BuildError("hostRuntime.runtime must be js or dex")
    return raw, host


def executable(explicit: str | None, variable: str, name: str) -> str:
    configured = explicit or os.environ.get(variable)
    if not configured and name in ("javac", "java") and os.environ.get("JAVA_HOME"):
        configured = str(Path(os.environ["JAVA_HOME"]) / "bin" / name)
    candidate = shutil.which(configured or name)
    if not candidate:
        raise BuildError(f"Missing {name}: set --{name} or {variable}" + (" / JAVA_HOME" if name in ("java", "javac") else "") + "; no compiler will be downloaded")
    return str(Path(candidate).resolve())


def run(command: list[str], cwd: Path) -> None:
    # Inherit streams: preserve compiler diagnostics without unbounded buffering.
    subprocess.run(command, cwd=cwd, check=True)


def files_under(root: Path) -> list[Path]:
    files = []
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            raise BuildError(f"Symlinks are not packaged: {path}")
        if path.is_file():
            relative_path(path.relative_to(root).as_posix(), "source path")
            files.append(path)
    return files


def jar_classes(root: Path, target: Path) -> None:
    classes = sorted(root.rglob("*.class"))
    if not classes:
        raise BuildError("javac returned success but produced no .class files")
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED) as jar:
        for path in classes:
            jar.write(path, path.relative_to(root).as_posix())


def compile_dex(args: argparse.Namespace, source_files: list[Path], work: Path) -> Path:
    javac = executable(args.javac, "JAVAC", "javac")
    d8 = args.d8 or os.environ.get("D8")
    r8 = args.r8_jar or os.environ.get("R8_JAR")
    if d8 and r8:
        raise BuildError("Choose --d8/D8 OR --r8-jar/R8_JAR, not both")
    if r8:
        jar = Path(r8).expanduser().resolve()
        if not jar.is_file():
            raise BuildError(f"Missing R8_JAR: {jar}")
        dex_command = [executable(args.java, "JAVA", "java"), "-cp", str(jar), "com.android.tools.r8.D8"]
    else:
        dex_command = [executable(d8, "D8", "d8")]
    if args.sdk_src is None:
        raise BuildError("DEX compilation requires --sdk-src pointing to SDK Java sources")
    sdk_root = args.sdk_src.expanduser().resolve()
    sdk_sources = [sdk_root / SDK_PACKAGE / f"{name}.java" for name in ("AharouPlugin", "PluginHost")]
    for path in sdk_sources:
        if not path.is_file():
            raise BuildError(f"Missing SDK source: {path}; export the SDK from the host App first")
    sources = [str(path) for path in source_files if path.suffix == ".java"]
    if not sources:
        raise BuildError("No Java source files in --source-dir")
    if any(path.relative_to(args.source_dir).as_posix().startswith(SDK_PACKAGE + "/") for path in source_files):
        raise BuildError("Do not include host SDK sources in plugin sources")
    sdk_classes = work / "sdk-classes"
    plugin_classes = work / "plugin-classes"
    sdk_classes.mkdir()
    plugin_classes.mkdir()
    base = [javac, "--release", "8", "-encoding", "UTF-8", "-proc:none"]
    run(base + ["-d", str(sdk_classes)] + [str(p) for p in sdk_sources], work)
    sdk_jar = work / "sdk.jar"
    jar_classes(sdk_classes, sdk_jar)
    classpath = [str(sdk_jar)]
    if args.android_jar:
        android = args.android_jar.expanduser().resolve()
        if not android.is_file():
            raise BuildError(f"Missing --android-jar: {android}")
        classpath.append(str(android))
        dex_command += ["--lib", str(android)]
    run(base + ["-classpath", os.pathsep.join(classpath), "-d", str(plugin_classes)] + sources, work)
    if (plugin_classes / SDK_PACKAGE).exists():
        raise BuildError("Plugin output duplicates host SDK classes; remove com.aharou.plugin.api sources")
    plugin_jar = work / "plugin.jar"
    jar_classes(plugin_classes, plugin_jar)
    dex_dir = work / "dex"
    dex_dir.mkdir()
    # SDK is a reference classpath only, never a D8 program input.
    run(dex_command + ["--min-api", str(args.min_api), "--classpath", str(sdk_jar), "--output", str(dex_dir), str(plugin_jar)], work)
    dex = dex_dir / "classes.dex"
    if not dex.is_file():
        raise BuildError("D8 returned success without classes.dex")
    with dex.open("rb") as stream:
        magic = stream.read(8)
    if dex.stat().st_size < 112 or not re.fullmatch(rb"dex\n0[0-9]{2}\x00", magic):
        raise BuildError("D8 output is empty or has invalid DEX magic/header length")
    if len(list(dex_dir.glob("*.dex"))) != 1:
        raise BuildError("Multiple DEX files are unsupported by SDKv1; reduce plugin size")
    return dex


def package(args: argparse.Namespace) -> None:
    args.source_dir = args.source_dir.expanduser().resolve()
    args.manifest = args.manifest.expanduser().resolve()
    args.output = args.output.expanduser().absolute()
    if args.output.suffix != ".txplugin":
        raise BuildError("--output must end in .txplugin")
    if args.output == args.manifest or args.output.is_symlink():
        raise BuildError("--output cannot replace the manifest or a symlink")
    raw, host = load_manifest(args.manifest)
    if not args.source_dir.is_dir():
        raise BuildError("--source-dir must be an existing directory")
    source_files = [p for p in files_under(args.source_dir) if p not in (args.manifest, args.output)]
    with tempfile.TemporaryDirectory(prefix="aharou-plugin-build-") as temporary:
        work = Path(temporary)
        if host["runtime"] == "dex":
            payload = [(host["artifact"], compile_dex(args, source_files, work))]
        else:
            entry = args.source_dir / host["entry"]
            if entry not in source_files or not entry.stat().st_size:
                raise BuildError(f"JS entry is missing or empty: {host['entry']}")
            payload = [(p.relative_to(args.source_dir).as_posix(), p) for p in source_files]
        args.output.parent.mkdir(parents=True, exist_ok=True)
        # Stage beside destination for atomic replacement; leave old output on any failure.
        fd, staging_name = tempfile.mkstemp(prefix=".txplugin-", suffix=".tmp", dir=args.output.parent)
        os.close(fd)
        staging = Path(staging_name)
        try:
            with zipfile.ZipFile(staging, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("manifest.json", raw)
                for name, path in sorted(payload):
                    archive.write(path, "payload/" + relative_path(name, "payload path"))
            with zipfile.ZipFile(staging) as archive:
                expected = ["manifest.json"] + ["payload/" + name for name, _ in sorted(payload)]
                if archive.namelist() != expected or archive.testzip() is not None:
                    raise BuildError("Package verification failed")
            os.replace(staging, args.output)
        finally:
            staging.unlink(missing_ok=True)
    print(f"Built {args.output} ({args.output.stat().st_size} bytes, runtime={host['runtime']}, apiVersion=1)")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source-dir", type=Path, required=True, help="JS payload directory or Java sources directory")
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="Destination .txplugin (atomically replaced on success)")
    parser.add_argument("--sdk-src", type=Path, help="SDK source root containing com/aharou/plugin/api/*.java; DEX only")
    parser.add_argument("--javac", help="Explicit javac executable; requires JDK 9+ (--release 8)")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--d8", help="Explicit D8 executable (or D8 environment variable)")
    group.add_argument("--r8-jar", help="Explicit local R8 jar (or R8_JAR environment variable)")
    parser.add_argument("--java", help="Java executable used with --r8-jar")
    parser.add_argument("--android-jar", type=Path, help="Optional local Android SDK android.jar for Android API references")
    parser.add_argument("--min-api", type=int, default=26, help="DEX minimum Android API (default: 26)")
    args = parser.parse_args(argv)
    if args.min_api < 26:
        parser.error("--min-api must be >=26 (host minimum)")
    try:
        package(args)
    except subprocess.CalledProcessError as error:
        print(f"Compiler failed (exit {error.returncode}); output package was not replaced", file=sys.stderr)
        return error.returncode if error.returncode > 0 else 128 - error.returncode
    except (BuildError, OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"Build failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
