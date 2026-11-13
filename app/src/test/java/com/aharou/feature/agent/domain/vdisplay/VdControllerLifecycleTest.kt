package com.aharou.feature.agent.domain.vdisplay

import android.content.Context
import android.content.res.AssetManager
import com.aharou.feature.agent.domain.shell.HostShellManager
import com.aharou.feature.agent.domain.shell.HostShellMode
import com.aharou.feature.agent.domain.shell.ShellCommandResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class, sdk = [35])
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.LEGACY)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VdControllerLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()
    private val commands = mutableListOf<String>()
    private var running = false
    private var cancelDuringResize = false

    private fun controller(): VdController {
        val context = mockk<Context>()
        val assets = mockk<AssetManager>()
        every { context.packageName } returns "com.aharou.agent.fork"
        every { context.getExternalFilesDir(null) } returns temporary.root
        every { context.assets } returns assets
        every { assets.open(VdController.ASSET_PATH) } answers { byteArrayOf(1, 2, 3).inputStream() }
        val host = mockk<HostShellManager>()
        coEvery { host.ensureMode() } returns HostShellMode.SHIZUKU
        coEvery { host.run(any(), any()) } answers {
            val command = firstArg<String>()
            commands += command
            if (cancelDuringResize && command.startsWith("wm size"))
                throw kotlinx.coroutines.CancellationException("resize cancelled")
            val output = when {
                "LAUNCHED" in command -> { running = true; "LAUNCHED" }
                "touch /data/local/tmp/aharou-vd-fork.stop" in command -> { running = false; "OK" }
                "dumpsys display" in command && running -> "type=VIRTUAL displayId=42 uniqueId='virtual:shell,2000,aharou-vd-fork,0' deviceWidth=1080, deviceHeight=1920 densityDpi=440}"
                "cat /data/local/tmp/vd-fork.log" in command -> "VD_READY id=42 size=1080x1920 dpi=440\nVD_CTL ok"
                "DEPLOYED" in command -> "DEPLOYED"
                "am stack list" in command -> "RootTask id=9 bounds=[] displayId=42 userId=0\n"
                else -> ""
            }
            ShellCommandResult(output, 0)
        }
        return VdController(context, host)
    }

    @Test fun cancellationDuringTaskTeardownStillStopsRunnerAndClearsState() = runTest {
        val controller = controller()
        running = true
        controller.refresh()
        val stopping = launch { controller.stop() }
        runCurrent()
        assertTrue(commands.any { "am stack remove 9" in it })
        stopping.cancel()
        advanceUntilIdle()
        assertTrue(commands.any { "touch /data/local/tmp/aharou-vd-fork.stop" in it })
        assertEquals(null, controller.state.value)
    }

    @Test fun cancellationDuringResizeCannotPublishReadyOrLeakTheRunner() = runTest {
        val controller = controller()
        cancelDuringResize = true
        var cancelled = false
        try { controller.start() }
        catch (_: kotlinx.coroutines.CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertTrue(commands.any { "touch /data/local/tmp/aharou-vd-fork.stop" in it })
        assertEquals(null, controller.state.value)
    }

    @Test fun simultaneousStartsShareOneRunnerAndKeepItsCommandDirectory() = runTest {
        val controller = controller()
        backgroundScope.launch {
            while (true) {
                File(temporary.root, "vd/ctl").listFiles().orEmpty()
                    .filter { it.name.startsWith("cmd-") && it.extension == "txt" }
                    .forEach { command ->
                        File(command.parentFile, command.name.replace("cmd-", "res-"))
                            .writeText("OK\nuiautomation protocol=2")
                        command.delete()
                    }
                delay(1)
            }
        }
        val first = async { controller.start() }
        val second = async { controller.start() }
        assertEquals(42, first.await().displayId)
        assertEquals(first.await(), second.await())
        assertEquals(1, commands.count { "LAUNCHED" in it })
        assertTrue(commands.none { "touch /data/local/tmp/aharou-vd-fork.stop" in it })
    }
}
