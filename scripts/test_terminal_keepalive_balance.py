"""Compile actual manager lifecycle methods with a counting service, not Android."""
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path.home() / ".gradle/caches/modules-2/files-2.1"
NAMES = ("kotlin-compiler-embeddable/2.1.0", "kotlin-stdlib/2.1.0", "kotlin-reflect/2.1.0",
         "kotlin-script-runtime/2.1.0", "trove4j/1.0.20200330", "annotations/13.0",
         "kotlinx-coroutines-core-jvm/1.9.0")
CP = ":".join(str(p) for p in CACHE.glob("**/*.jar") if any("/" + n + "/" in str(p) for n in NAMES))


def method(text, name):
    start = text.index("    private fun " + name + "(")
    end = text.index("\n    }", start) + len("\n    }")
    return text[start:end]


with tempfile.TemporaryDirectory(prefix="terminal-keepalive-") as temporary:
    temporary = Path(temporary)
    for manager in ("TerminalSessionManager", "RemoteTerminalSessionManager"):
        text = (ROOT / f"app/src/main/java/com/aharou/feature/terminal/domain/{manager}.kt").read_text()
        methods = "\n".join(method(text, name) for name in
                            ("startKeepaliveService", "stopKeepaliveService", "maybeStopKeepalive"))
        start_argument = "tab" if "startKeepaliveService(tab: TerminalTab)" in methods else "tab.workspacePath"
        if start_argument == "tab":
            assert "keepaliveTabs = mutableSetOf<String>()" in text
            assert "startKeepaliveService(tab)" in text or "let(::startKeepaliveService)" in text
        source = '''
object FileLogger { fun i(tag:String,message:String){} }
sealed class RunState { object Running:RunState(); object Finished:RunState() }
data class TerminalTab(val id:String,val workspacePath:String?,val isBackground:Boolean=true,var runState:RunState=RunState.Running)
class Holder { var value = emptyList<TerminalTab>() }
object TerminalKeepaliveService {
 val counts=mutableMapOf<String?,Int>()
 fun startSession(context:Int,workspace:String?) { counts[workspace]=(counts[workspace] ?: 0)+1 }
 fun stopSession(context:Int,workspace:String?) { counts[workspace]=(counts[workspace] ?: 0)-1 }
}
class Harness {
 private val appContext=0
 private val TAG="test"
 private val _tabs=Holder()
 private val keepaliveTabs=mutableSetOf<String>()
 fun start(tab:TerminalTab) { _tabs.value=_tabs.value+tab; startKeepaliveService(START_ARG) }
 fun finish(tab:TerminalTab) { tab.runState=RunState.Finished; maybeStopKeepalive(tab) }
 fun close(tab:TerminalTab) { _tabs.value=_tabs.value.filterNot { it.id==tab.id }; maybeStopKeepalive(tab) }
 METHODS
}
fun main() {
 val a=TerminalTab("a","workspace-a"); val b=TerminalTab("b","workspace-b")
 val local=Harness(); val remote=Harness()
 local.start(a); local.start(b)
 check(TerminalKeepaliveService.counts["workspace-a"]==1)
 local.finish(a)
 check(TerminalKeepaliveService.counts["workspace-a"]==0) { "first finished tab still holds keepalive" }
 check(TerminalKeepaliveService.counts["workspace-b"]==1)
 local.close(a); local.finish(a)
 check(TerminalKeepaliveService.counts["workspace-a"]==0) { "duplicate release" }
 val remoteTab=TerminalTab("a","workspace-b")
 remote.start(remoteTab); local.finish(b); local.close(b)
 check(TerminalKeepaliveService.counts["workspace-b"]==1) { "released another manager's lease" }
 remote.close(remoteTab); remote.finish(remoteTab)
 check(TerminalKeepaliveService.counts.values.all { it==0 })
 println("PASS two tabs, duplicate callbacks, original workspace, mixed managers")
}
'''.replace("START_ARG", start_argument).replace("METHODS", methods)
        path = temporary / f"{manager}Probe.kt"
        path.write_text(source)
        output = temporary / manager
        result = subprocess.run(["java", "-cp", CP, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                                 "-no-stdlib", "-no-reflect", "-classpath", CP, "-d", str(output), str(path)],
                                check=False, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        if result.returncode:
            print(result.stdout)
            raise SystemExit(result.returncode)
        result = subprocess.run(["java", "-cp", str(output) + ":" + CP, manager + "ProbeKt"], check=False)
        print(manager, "exit", result.returncode, flush=True)
        if result.returncode:
            raise SystemExit(result.returncode)
