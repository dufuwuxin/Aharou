package com.aharou.feature.vault

import android.app.Application
import com.aharou.core.privacy.MaskingRules
import com.aharou.core.privacy.OutboundMasker
import com.aharou.core.security.KeystoreCipher
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class VaultKeywordSelectionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var previousDelegate: ((String) -> String)? = null
    private var previousSnapshotDelegate: ((String, Boolean) -> String)? = null
    private fun repository() = VaultRepository(context, ContainerSettingsRepository(context))

    @Before fun setup() {
        previousDelegate = VaultRedactor.delegate
        previousSnapshotDelegate = VaultRedactor.snapshotDelegate
        context.getSharedPreferences("aharou_vault", 0).edit().clear().commit()
        mockkObject(KeystoreCipher)
        every { KeystoreCipher.encryptString(any()) } answers { firstArg() }
        every { KeystoreCipher.decryptString(any()) } answers { firstArg() }
    }

    @After fun cleanup() {
        VaultRedactor.delegate = previousDelegate
        VaultRedactor.snapshotDelegate = previousSnapshotDelegate
        MaskingRules.literalValues = { emptyList() }
        unmockkObject(KeystoreCipher)
        context.getSharedPreferences("aharou_vault", 0).edit().clear().commit()
    }

    @Test fun selectionIsPersistentAndUsesTheActualOutboundFactory() {
        val repository = repository()
        repository.set("project", "内部甲")
        assertTrue(repository.setKeywordNames(setOf("project")))
        val reloaded = repository()
        assertEquals(setOf("project"), reloaded.keywordNames.value)
        val masker = OutboundMasker.create()
        val result = masker.mask("讨论内部甲")
        assertFalse(result.contains("内部甲"))
        assertEquals("讨论内部甲", masker.restoreComplete(result))
        assertTrue(reloaded.setKeywordNames(emptySet()))
        assertEquals("讨论内部甲", OutboundMasker.create().mask("讨论内部甲"))
    }

    @Test fun deletingAndResettingDoNotLeaveReplacementSelections() {
        val repository = repository()
        repository.set("one", "内部甲")
        repository.set("two", "内部乙")
        assertTrue(repository.setKeywordNames(setOf("one", "two")))
        assertTrue(repository.delete("one"))
        assertEquals(setOf("two"), repository.keywordNames.value)
        repository.reset()
        assertTrue(repository.keywordNames.value.isEmpty())
        assertTrue(repository().keywordNames.value.isEmpty())
    }

    @Test fun failedKeywordCommitPreventsDeletionAndPreservesExistingSelection() {
        val delegate = context.getSharedPreferences("aharou_vault", 0)
        var failCommits = false
        val preferences = object : android.content.SharedPreferences by delegate {
            override fun edit(): android.content.SharedPreferences.Editor {
                val editor = delegate.edit()
                return object : android.content.SharedPreferences.Editor by editor {
                    override fun putStringSet(key: String?, values: MutableSet<String>?): android.content.SharedPreferences.Editor {
                        editor.putStringSet(key, values)
                        return this
                    }
                    override fun commit(): Boolean = if (failCommits) false else editor.commit()
                }
            }
        }
        val application = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences = preferences
        }
        val repository = VaultRepository(application, ContainerSettingsRepository(application))
        repository.set("one", "内部甲")
        assertTrue(repository.setKeywordNames(setOf("one")))
        failCommits = true
        assertFalse(repository.delete("one"))
        assertEquals("内部甲", repository.get("one"))
        assertEquals(setOf("one"), repository.keywordNames.value)
    }

    @Test fun savingMultipleSeparatedRulesIsAtomicAndPreservesOtherCredentials() {
        val repository = repository()
        repository.set("credential", "Abc123keepSecretValue")
        assertTrue(repository.saveKeywordEntries(listOf("first" to "一,二", "second" to "第三条\n整段")))
        val reloaded = repository()
        assertEquals(mapOf("first" to "一,二", "second" to "第三条\n整段"), reloaded.keywordEntries().toMap())
        assertEquals("Abc123keepSecretValue", reloaded.get("credential"))
        val masker = OutboundMasker.create()
        val input = "一,二 / 第三条\n整段"
        assertEquals(input, masker.restoreComplete(masker.mask(input)))
        assertEquals(2, masker.maskedCount())
        assertTrue(reloaded.saveKeywordEntries(emptyList()))
        assertEquals("一,二", reloaded.get("first"))
        assertTrue(reloaded.keywordNames.value.isEmpty())
    }

    @Test fun coldUnreadableVaultCannotBeOverwrittenByKeywordSave() {
        val preferences = context.getSharedPreferences("aharou_vault", 0)
        preferences.edit().putString("blob", "unreadable-original-blob").commit()
        every { KeystoreCipher.decryptString("unreadable-original-blob") } throws IllegalStateException("fixture_decryption_failed")
        val repository = repository()
        try {
            repository.saveKeywordEntries(listOf("new" to "new text"))
            fail("Unreadable vault must remain read-only")
        } catch (_: VaultReadOnlyException) { }
        assertEquals("unreadable-original-blob", preferences.getString("blob", null))
        assertTrue(repository.keywordNames.value.isEmpty())
    }

    @Test fun concurrentIndividualSelectionsDoNotLoseAnUpdate() = kotlinx.coroutines.runBlocking {
        val repository = repository()
        repository.set("one", "原文一")
        repository.set("two", "原文二")
        kotlinx.coroutines.coroutineScope {
            val first = async(kotlinx.coroutines.Dispatchers.Default) { repository.setKeywordSelected("one", true) }
            val second = async(kotlinx.coroutines.Dispatchers.Default) { repository.setKeywordSelected("two", true) }
            assertTrue(first.await())
            assertTrue(second.await())
        }
        assertEquals(setOf("one", "two"), repository.keywordNames.value)
        repository.delete("one")
        try { repository.setKeywordSelected("one", true); fail("Deleted names cannot be selected") }
        catch (_: IllegalArgumentException) { }
        assertEquals(setOf("two"), repository.keywordNames.value)
    }

    @Test fun missingNamesAreRejectedWithoutChangingSelection() {
        val repository = repository()
        repository.set("one", "内部甲")
        assertTrue(repository.setKeywordNames(setOf("one")))
        try { repository.setKeywordNames(setOf("missing")); fail("Unknown name must not be saved") }
        catch (_: IllegalArgumentException) { }
        assertEquals(setOf("one"), repository.keywordNames.value)
    }
}
