package com.aharou.core.privacy

import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.privacy.maskedOutboundRequest
import com.aharou.feature.agent.domain.privacy.outboundMaskerOrNull
import com.aharou.feature.settings.data.repository.GeneralSettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class OutboundMaskingSettingsTest {
    @Test fun persistentOptInControlsTheSameActualRequestTransformer() = runBlocking {
        val repository = GeneralSettingsRepository(RuntimeEnvironment.getApplication())
        assertFalse(repository.privacyMaskingEnabled())
        val raw = "password=AbCdEf987654321"
        val messages = listOf(AgentMessage.UserMessage(content = raw))
        try {
            repository.setPrivacyMaskingEnabled(true)
            val reloaded = GeneralSettingsRepository(RuntimeEnvironment.getApplication())
            assertTrue(reloaded.privacyMaskingEnabled())
            val request = maskedOutboundRequest(raw, messages, outboundMaskerOrNull(reloaded.privacyMaskingEnabled()))
            assertFalse(request.systemPrompt.contains("AbCdEf987654321"))
            repository.setPrivacyMaskingEnabled(false)
            val unmasked = maskedOutboundRequest(raw, messages, outboundMaskerOrNull(repository.privacyMaskingEnabled()))
            assertEquals(raw, unmasked.systemPrompt)
            assertSame(messages, unmasked.messages)
        } finally { repository.setPrivacyMaskingEnabled(false) }
    }
}
