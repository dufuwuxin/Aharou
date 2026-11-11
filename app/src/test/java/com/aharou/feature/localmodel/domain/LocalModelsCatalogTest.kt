package com.aharou.feature.localmodel.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class LocalModelsCatalogTest {

    @Test
    fun catalog_keeps_existing_models_first_and_appends_new_models() {
        assertEquals(
            listOf(LocalModels.TINY, LocalModels.SMALL, LocalModels.MEDIUM, LocalModels.LARGE),
            LocalModels.ALL,
        )
        assertEquals("qwen2.5-0.5b", LocalModels.ALL[0].id)
        assertEquals("qwen2.5-1.5b", LocalModels.ALL[1].id)
    }

    @Test
    fun every_model_has_valid_integrity_metadata() {
        LocalModels.ALL.forEach { model ->
            assertTrue(model.id, model.id.isNotBlank())
            assertTrue(model.id, model.displayName.isNotBlank())
            assertTrue(model.id, model.sizeBytes > 0L)
            assertTrue(model.id, model.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(model.id, model.fileName.endsWith(".gguf"))
            assertFalse(model.id, model.fileName.contains('/'))
            assertFalse(model.id, Regex("-\\d{5}-of-\\d{5}").containsMatchIn(model.fileName))
            assertTrue(model.id, model.minRamGb > 0)
            assertTrue(model.id, model.contextSize > 0L)
        }
    }

    @Test
    fun ids_and_filenames_are_unique() {
        assertEquals(LocalModels.ALL.size, LocalModels.ALL.map { it.id }.toSet().size)
        assertEquals(LocalModels.ALL.size, LocalModels.ALL.map { it.fileName }.toSet().size)
    }

    @Test
    fun download_urls_are_absolute_https_and_match_the_filename() {
        LocalModels.ALL.forEach { model ->
            assertFalse(model.id, model.urls.isEmpty())
            model.urls.forEach { url ->
                val uri = URI(url)
                assertEquals(model.id, "https", uri.scheme)
                assertFalse(model.id, uri.host.isNullOrBlank())
                assertNull(model.id, uri.userInfo)
                assertTrue(model.id, uri.path.endsWith("/${model.fileName}"))
            }
        }
    }

    @Test
    fun by_id_returns_catalog_instances_and_null_for_unknown_ids() {
        LocalModels.ALL.forEach { model ->
            assertSame(model.id, model, LocalModels.byId(model.id))
        }
        assertNull(LocalModels.byId("unknown-model"))
        assertNull(LocalModels.byId(""))
    }

    @Test
    fun added_models_match_official_metadata_snapshot() {
        assertEquals("qwen2.5-3b", LocalModels.MEDIUM.id)
        assertEquals("qwen2.5-3b-instruct-q4_k_m.gguf", LocalModels.MEDIUM.fileName)
        assertEquals(2_104_932_768L, LocalModels.MEDIUM.sizeBytes)
        assertEquals(
            "626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d",
            LocalModels.MEDIUM.sha256,
        )
        assertEquals("qwen2.5-7b-q3-k-m", LocalModels.LARGE.id)
        assertEquals("qwen2.5-7b-instruct-q3_k_m.gguf", LocalModels.LARGE.fileName)
        assertEquals(3_808_391_072L, LocalModels.LARGE.sizeBytes)
        assertEquals(
            "a96b16179dc6cc9afdf0cf7a96a80c199cbd00b9be207c3465be21cb721cca5e",
            LocalModels.LARGE.sha256,
        )
    }
}
