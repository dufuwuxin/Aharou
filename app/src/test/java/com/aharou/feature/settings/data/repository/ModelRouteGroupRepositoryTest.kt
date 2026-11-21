package com.aharou.feature.settings.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.aharou.feature.settings.domain.model.ModelRouteGroup
import com.aharou.feature.settings.domain.model.ModelRouteGroupCodec
import com.aharou.feature.settings.domain.model.ModelRouteMember
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ModelRouteGroupRepositoryTest {
    private val groupsKey = stringPreferencesKey("groups_json")
    private val membersKey = stringSetPreferencesKey("allocated_member_sessions")
    private val models = listOf(ModelRouteMember("provider", "model"))
    private val existing = ModelRouteGroup("existing", "Original", models)

    @Test fun missingOrEmptyConfigurationSupportsCrudWithoutLosingMemberMarkers() = runTest {
        for (initial in listOf(emptyPreferences(), preferencesOf(groupsKey to "[]"))) {
            val store = MemoryStore(initial)
            val repository = ModelRouteGroupRepository(store)
            assertEquals(emptyList<ModelRouteGroup>(), repository.snapshot())
            repository.markMember("child", true)
            val created = repository.create(" Created ", models)
            assertEquals("Created", created.name)
            assertEquals(created, repository.get(created.id))
            val updated = repository.update(created.id, " Updated ", models)
            assertEquals(listOf(updated), repository.snapshot())
            assertEquals("Updated", updated.name)
            assertTrue(repository.delete(created.id))
            assertFalse(repository.delete(created.id))
            assertEquals(emptyList<ModelRouteGroup>(), repository.snapshot())
            assertTrue(repository.isAllocatedMember("child"))
        }
    }

    @Test fun corruptConfigurationFailsReadsAndEveryMutationWithoutReplacingPreferences() = runTest {
        val invalid = listOf(
            "{broken",
            "{}",
            "null",
            "",
            "  ",
            """[{"id":"existing","name":"Original","models":[]}]""",
            """[{"id":"existing","name":"Original","models":[{"providerId":"provider"}]}]""",
        )
        for (raw in invalid) {
            val initial = preferencesOf(groupsKey to raw, membersKey to setOf("child"))
            val store = MemoryStore(initial)
            val repository = ModelRouteGroupRepository(store)
            val operations: List<suspend () -> Any?> = listOf(
                { repository.readState.first().getOrThrow() },
                { repository.snapshot() },
                { repository.get(existing.id) },
                { repository.create("New", models) },
                { repository.update(existing.id, "Changed", models) },
                { repository.delete(existing.id) },
            )
            for (operation in operations) {
                val failure = runCatching { operation() }.exceptionOrNull()
                assertTrue("Expected an explicit corrupt-data failure", failure is ModelRouteGroupDataException)
                assertEquals("MODEL_ROUTE_GROUP_DATA_INVALID", failure?.message)
                assertNotNull(failure?.cause)
                assertEquals(initial, store.current.value)
                assertEquals(0, store.commits)
            }
            store.current.value = preferencesOf(groupsKey to ModelRouteGroupCodec.encode(listOf(existing)))
            assertEquals(existing, repository.get(existing.id))
        }
    }

    @Test fun listSubscribersDoNotReceiveAnEmptyListForCorruptionAndRecoverAfterRepair() = runTest {
        val store = MemoryStore(preferencesOf(groupsKey to ModelRouteGroupCodec.encode(listOf(existing))))
        val repository = ModelRouteGroupRepository(store)
        val observed = mutableListOf<List<ModelRouteGroup>>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            repository.groups.collect { observed += it }
        }
        assertEquals(listOf(listOf(existing)), observed)
        store.current.value = preferencesOf(groupsKey to "{broken")
        yield()
        assertTrue(repository.readState.first().exceptionOrNull() is ModelRouteGroupDataException)
        assertEquals(listOf(listOf(existing)), observed)
        val repaired = existing.copy(name = "Repaired")
        store.current.value = preferencesOf(groupsKey to ModelRouteGroupCodec.encode(listOf(repaired)))
        yield()
        assertEquals(listOf(listOf(existing), listOf(repaired)), observed)
        assertEquals(listOf(repaired), repository.readState.first().getOrThrow())
    }

    @Test fun mutationsValidateTheCurrentTransactionInsteadOfAnEarlierSnapshot() = runTest {
        val operations: List<suspend (ModelRouteGroupRepository) -> Any?> = listOf(
            { it.create("New", models) },
            { it.update(existing.id, "Changed", models) },
            { it.delete(existing.id) },
        )
        for (operation in operations) {
            val store = MemoryStore(preferencesOf(groupsKey to ModelRouteGroupCodec.encode(listOf(existing))))
            val repository = ModelRouteGroupRepository(store)
            assertEquals(listOf(existing), repository.snapshot())
            val corrupt = preferencesOf(groupsKey to "{broken", membersKey to setOf("child"))
            store.beforeUpdate = { store.current.value = corrupt }
            val failure = runCatching { operation(repository) }.exceptionOrNull()
            assertTrue(failure is ModelRouteGroupDataException)
            assertEquals(corrupt, store.current.value)
            assertEquals(0, store.commits)
        }
    }

    @Test fun repositoriesSharingAStoreDoNotLoseConcurrentCreates() = runTest {
        val store = MemoryStore(emptyPreferences())
        val first = ModelRouteGroupRepository(store)
        val second = ModelRouteGroupRepository(store)
        val created = listOf(
            async { first.create("First", models) },
            async { second.create("Second", models) },
        ).awaitAll()
        assertEquals(created.toSet(), first.snapshot().toSet())
        assertEquals(2, store.commits)
    }

    @Test fun missingIdsLeaveValidConfigurationUnchanged() = runTest {
        val initial = preferencesOf(groupsKey to ModelRouteGroupCodec.encode(listOf(existing)))
        val store = MemoryStore(initial)
        val repository = ModelRouteGroupRepository(store)
        assertTrue(runCatching { repository.update("missing", "Changed", models) }.isFailure)
        assertFalse(repository.delete("missing"))
        assertEquals(initial, store.current.value)
        assertEquals(existing, repository.get(existing.id))
    }

    @Test fun storageFailuresAndCancellationAreNotReportedAsInvalidJson() = runTest {
        val initial = preferencesOf(groupsKey to ModelRouteGroupCodec.encode(listOf(existing)))
        for (failure in listOf(IOException("injected write failure"), CancellationException("cancelled"))) {
            val store = MemoryStore(initial)
            store.beforeUpdate = { throw failure }
            val repository = ModelRouteGroupRepository(store)
            assertSame(failure, runCatching { repository.create("New", models) }.exceptionOrNull())
            assertEquals(initial, store.current.value)
            assertEquals(0, store.commits)
        }
        val failure = IOException("injected read failure")
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = kotlinx.coroutines.flow.flow { throw failure }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = error("unexpected write")
        }
        assertSame(failure, runCatching { ModelRouteGroupRepository(store).snapshot() }.exceptionOrNull())
    }

    private class MemoryStore(initial: Preferences) : DataStore<Preferences> {
        val current = MutableStateFlow(initial)
        override val data: Flow<Preferences> = current
        private val mutex = Mutex()
        var commits = 0
        var beforeUpdate: (() -> Unit)? = null

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            yield()
            return mutex.withLock {
                beforeUpdate?.invoke()
                transform(current.value).also {
                    current.value = it
                    commits++
                }
            }
        }
    }
}
