package com.aharou.feature.settings.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRouteGroupTest {
    @Test fun `storage preserves model order and provider identity`() {
        val group = ModelRouteGroup("test-1", "Ordered", listOf(
            ModelRouteMember("z", "same"), ModelRouteMember("a", "same"), ModelRouteMember("z", "other")
        ))
        assertEquals(listOf(group), ModelRouteGroupCodec.decode(ModelRouteGroupCodec.encode(listOf(group))))
        assertEquals("route:test-1", group.selectionId)
        assertEquals("test-1", ModelRouteGroup.idFromSelection(group.selectionId))
        assertNull(ModelRouteGroup.idFromSelection("provider-1"))
    }

    @Test fun `non-string route references are not accepted as public identifiers`() {
        for (value in listOf("true", "42", "null")) {
            assertTrue(runCatching { ModelRouteGroupCodec.parseForm("""{"name":"Test","models":[{"providerId":$value,"model":"A"}]}""") }.isFailure)
            assertTrue(runCatching { ModelRouteGroupCodec.parseForm("""{"name":"Test","models":[{"providerId":"p","model":$value}]}""") }.isFailure)
        }
    }

    @Test fun `config accepts only ordered public model references`() {
        val form = ModelRouteGroupCodec.parseForm("""{"name":"Test","models":[{"providerId":"z","model":"B"},{"providerId":"a","model":"A"}]}""")
        assertEquals(listOf(ModelRouteMember("z", "B"), ModelRouteMember("a", "A")), form.models)
        assertThrows(IllegalArgumentException::class.java) {
            ModelRouteGroupCodec.parseForm("""{"name":"Test","models":[{"providerId":"z","model":"B","apiKey":"private"}]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelRouteGroupCodec.parseForm("""{"name":"Test","models":[]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelRouteGroupCodec.parseForm("""{"name":"Test","models":[{"providerId":"route:nested","model":"B"}]}""")
        }
    }
}
