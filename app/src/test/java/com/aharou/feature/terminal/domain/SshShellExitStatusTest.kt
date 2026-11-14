package com.aharou.feature.terminal.domain

import io.mockk.mockk
import net.schmizz.sshj.connection.channel.direct.Session
import org.junit.Assert.assertEquals
import org.junit.Test

class SshShellExitStatusTest {
    @Test fun shellEofWithoutCommandReceiptIsUnknownNotSuccess() {
        val shell = mockk<Session.Shell>(relaxed = true)
        assertEquals(-1, SshShellBackend(shell).waitForExit())
    }
}
