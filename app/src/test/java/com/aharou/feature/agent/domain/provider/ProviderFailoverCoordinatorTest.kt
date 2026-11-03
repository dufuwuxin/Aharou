package com.aharou.feature.agent.domain.provider

import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.FallbackTarget
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备用模型链的选路语义：第一条失败切第二条、链走完回到主供应商、坏条目跳过、各会话互不干扰。
 *
 * 用例都是纯 JVM（仓库用内存假实现），不需要 Robolectric。
 */
class ProviderFailoverCoordinatorTest {

    private fun provider(
        id: String,
        model: String = "model-$id",
        models: List<String> = listOf(model),
        enabled: Boolean = true,
        chain: List<FallbackTarget> = emptyList(),
        legacyFallbackId: String = "",
        legacyFallbackModel: String = ""
    ) = AIProviderConfig(
        id = id,
        name = "供应商 $id",
        type = ProviderType.OPENAI,
        apiKey = "key-$id",
        baseUrl = "https://$id.example.com",
        defaultModel = model,
        models = models,
        selectedModel = model,
        isEnabled = enabled,
        fallbackChain = chain,
        fallbackProviderId = legacyFallbackId,
        fallbackModel = legacyFallbackModel
    )

    /** 内存仓库：用例中途可以直接改 [byId] 模拟「用户把备用供应商删掉 / 关掉」。 */
    private class FakeRepository(providers: List<AIProviderConfig>) : AIProviderRepository {
        val byId = providers.associateBy { it.id }.toMutableMap()
        override fun getAllProviders(): Flow<List<AIProviderConfig>> = flowOf(byId.values.toList())
        override suspend fun getProviderById(id: String): AIProviderConfig? = byId[id]
        override suspend fun saveProvider(provider: AIProviderConfig) = Unit
        override suspend fun deleteProvider(id: String) = Unit
        override suspend fun setSelectedModel(id: String, model: String) = Unit
        override suspend fun updateModels(id: String, models: List<String>) = Unit
        override suspend fun setProviderEnabled(id: String, isEnabled: Boolean) = Unit
        override suspend fun deleteProviders(ids: List<String>) = Unit
        override suspend fun setProvidersEnabled(ids: List<String>, isEnabled: Boolean) = Unit
        override suspend fun reorderProviders(providers: List<AIProviderConfig>) = Unit
    }

    @Test
    fun first_failure_switches_to_first_entry() = runTest {
        val a = provider("a", chain = listOf(FallbackTarget("b", "backup-b")))
        val coordinator = ProviderFailoverCoordinator(FakeRepository(listOf(a, provider("b"))))

        val target = coordinator.next("s1", a)

        assertEquals("b", target?.id)
        assertEquals("backup-b", target?.selectedModel)
        assertTrue(coordinator.isActive("s1"))
    }

    @Test
    fun second_failure_walks_to_next_entry_in_order() = runTest {
        val a = provider(
            "a",
            chain = listOf(FallbackTarget("b"), FallbackTarget("c", "model-c2"))
        )
        val coordinator = ProviderFailoverCoordinator(FakeRepository(listOf(a, provider("b"), provider("c"))))

        assertEquals("b", coordinator.next("s1", a)?.id)
        // 当前这一跳（b）也失败：继续往下走第二条，而不是回头重试主供应商
        val second = coordinator.next("s1", provider("b"))
        assertEquals("c", second?.id)
        assertEquals("model-c2", second?.selectedModel)
        // 覆盖之后每轮直接取当前这一跳，不再撞一次主供应商
        assertEquals("c", coordinator.overrideConfig("s1")?.id)
    }

    @Test
    fun exhausted_chain_returns_null_and_clears_override() = runTest {
        val a = provider("a", chain = listOf(FallbackTarget("b")))
        val coordinator = ProviderFailoverCoordinator(FakeRepository(listOf(a, provider("b"))))

        assertEquals("b", coordinator.next("s1", a)?.id)
        assertNull(coordinator.next("s1", provider("b")))
        assertFalse(coordinator.isActive("s1"))
        assertNull(coordinator.overrideConfig("s1"))
    }

    @Test
    fun unusable_entries_are_skipped() = runTest {
        val a = provider(
            "a",
            chain = listOf(
                FallbackTarget("gone"),            // 供应商已删除
                FallbackTarget("off"),             // 已禁用
                FallbackTarget("c", "model-c2")    // 唯一可用的那条
            )
        )
        val coordinator = ProviderFailoverCoordinator(
            FakeRepository(listOf(a, provider("off", enabled = false), provider("c")))
        )

        val target = coordinator.next("s1", a)
        assertEquals("c", target?.id)
        assertEquals("model-c2", target?.selectedModel)
    }

    @Test
    fun empty_model_falls_back_to_provider_current_model() = runTest {
        val a = provider("a", chain = listOf(FallbackTarget("b", "")))
        val coordinator = ProviderFailoverCoordinator(FakeRepository(listOf(a, provider("b", model = "b-default"))))

        assertEquals("b-default", coordinator.next("s1", a)?.selectedModel)
    }

    @Test
    fun legacy_single_fallback_still_works() = runTest {
        // 老数据只有 fallbackProviderId / fallbackModel，没有链字段
        val a = provider("a", legacyFallbackId = "b", legacyFallbackModel = "old-model")
        val coordinator = ProviderFailoverCoordinator(FakeRepository(listOf(a, provider("b"))))

        val target = coordinator.next("s1", a)
        assertEquals("b", target?.id)
        assertEquals("old-model", target?.selectedModel)
    }

    @Test
    fun self_reference_is_dropped() = runTest {
        // 自环会让「往下切」原地打转：链上指向自己的那条直接丢掉
        val onlySelf = provider("a", chain = listOf(FallbackTarget("a")))
        assertNull(ProviderFailoverCoordinator(FakeRepository(listOf(onlySelf))).next("s1", onlySelf))

        val a = provider("a", chain = listOf(FallbackTarget("a"), FallbackTarget("b")))
        val mixed = ProviderFailoverCoordinator(FakeRepository(listOf(a, provider("b")))).next("s2", a)
        assertEquals("b", mixed?.id)
    }

    @Test
    fun override_config_clears_when_active_target_becomes_disabled() = runTest {
        val a = provider("a", chain = listOf(FallbackTarget("b")))
        val repository = FakeRepository(listOf(a, provider("b")))
        val coordinator = ProviderFailoverCoordinator(repository)
        assertEquals("b", coordinator.next("s1", a)?.id)

        // 用户中途把备用供应商关掉：这一跳就地撤销，回到主供应商而不是整轮卡死
        repository.byId["b"] = provider("b", enabled = false)
        assertNull(coordinator.overrideConfig("s1"))
        assertFalse(coordinator.isActive("s1"))
    }

    @Test
    fun sessions_do_not_share_cursor() = runTest {
        val a = provider("a", chain = listOf(FallbackTarget("b"), FallbackTarget("c")))
        val coordinator = ProviderFailoverCoordinator(FakeRepository(listOf(a, provider("b"), provider("c"))))

        assertEquals("b", coordinator.next("s1", a)?.id)
        // 另一个会话从链头开始，不受 s1 已切到第 1 跳的影响
        assertEquals("b", coordinator.next("s2", a)?.id)
        assertEquals("c", coordinator.next("s1", provider("b"))?.id)
    }
}
