package com.aharou.feature.settings.data.account

import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigValue
import com.aharou.feature.settings.data.ConfigProviderFields
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountConfigConsumerTest {
    private class Vault : AccountCredentialVault {
        private val values = mutableMapOf<String, String>()
        override fun read(id: String) = values[id]
        override fun write(id: String, value: String?) { if (value == null) values.remove(id) else values[id] = value }
    }

    @Test fun freeConnectionThroughConfigNeedsNoKeyAndIsIdempotent() {
        MockWebServer().use { server ->
            val client = OkHttpClient.Builder().connectionSpecs(listOf(okhttp3.ConnectionSpec.CLEARTEXT))
                .addInterceptor { chain ->
                    val request = chain.request()
                    assertEquals("ai-gateway.kilo.ai", request.url.host)
                    assertEquals(null, request.header("Authorization"))
                    chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build())
                }.build()
            val manager = AccountProviderManager(Vault(), client)
            var provider = AIProviderConfig("provider", "Free", ProviderType.OPENAI, "",
                baseUrl = manager.baseUrl(AccountProviderKind.OUR_FREE_MODEL, "provider"), defaultModel = "")
            val repository = mockk<AIProviderRepository>()
            coEvery { repository.getProviderById("provider") } answers { provider }
            coEvery { repository.saveProvider(any()) } answers { provider = firstArg() }
            every { repository.getAllProviders() } answers { flowOf(listOf(provider)) }
            val registry = ConfigRegistry::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            ConfigProviderFields(mockk(), mockk(), repository, manager).registerInto(registry)
            repeat(4) { server.enqueue(MockResponse().setBody("""{"data":[{"id":"free-model","isFree":true},{"id":"paid-model","isFree":false}]}""")) }
            val collection = registry.collection("provider_accounts")!!
            val input = ConfigValue.Obj(mapOf("provider_id" to ConfigValue.Str("provider")))
            val first = collection.add(input)
            assertEquals(first, collection.add(input))
            assertEquals(1, collection.childIds().size)
            assertEquals(listOf("free-model"), provider.models)
            assertEquals("free-model", provider.defaultModel)
            assertEquals("free-model", provider.selectedModel)
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun bothConfigImportsPersistMergedModelsAndDefaultsWithoutOpeningEditor() {
        for (collectionImport in listOf(false, true)) MockWebServer().use { server ->
            val manager = AccountProviderManager(Vault(), OkHttpClient.Builder().connectionSpecs(listOf(okhttp3.ConnectionSpec.CLEARTEXT)).build(), AccountEndpoints(loomy = server.url("/").toString().trimEnd('/')))
            var provider = AIProviderConfig("provider", "Loomy", ProviderType.OPENAI, "",
                baseUrl = manager.baseUrl(AccountProviderKind.LOOMY, "provider"), defaultModel = "")
            val repository = mockk<AIProviderRepository>()
            coEvery { repository.getProviderById("provider") } answers { provider }
            coEvery { repository.saveProvider(any()) } answers { provider = firstArg() }
            every { repository.getAllProviders() } answers { flowOf(listOf(provider)) }
            val registry = ConfigRegistry::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            val context = mockk<android.content.Context>()
            every { context.getString(any()) } returns "fixture language setting"
            ConfigProviderFields(context, mockk(), repository, manager).registerInto(registry)
            val catalog = """{"code":"000000","data":[{"id":"model-a"},{"id":"model-b"},{"id":"model-a"}]}"""
            server.enqueue(MockResponse().setBody(catalog))
            server.enqueue(MockResponse().setBody(catalog))
            server.enqueue(MockResponse().setBody("""{"code":"000000","data":{"balance":10}}"""))
            if (collectionImport) {
                registry.collection("provider_accounts")!!.add(ConfigValue.Obj(mapOf(
                    "provider_id" to ConfigValue.Str("provider"), "apiKey" to ConfigValue.Str("fixture-session"))))
            } else {
                val field = registry.resolveField("providers.provider.account_session")!!
                field.write(ConfigValue.Str("fixture-session"))
                assertEquals(ConfigValue.Str(""), field.read())
            }
            assertEquals(listOf("model-a", "model-b"), provider.models)
            assertEquals("model-a", provider.defaultModel)
            assertEquals("model-a", provider.selectedModel)
            assertTrue(listOf(provider).filter { it.isEnabled && it.models.isNotEmpty() }.isNotEmpty())
            assertEquals(1, manager.accounts("provider", AccountProviderKind.LOOMY).size)
        }
    }
}
