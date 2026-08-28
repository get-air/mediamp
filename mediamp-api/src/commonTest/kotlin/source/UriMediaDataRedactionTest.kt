package org.openani.mediamp.source

import org.openani.mediamp.ExperimentalMediampApi
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UriMediaDataRedactionTest {
    @OptIn(ExperimentalMediampApi::class)
    @Test
    fun sourceHeadersAndOptionsNeverRender() {
        val media = UriMediaData(
            uri = "https://stream.invalid/private-token/video.mkv",
            headers = mapOf("Authorization" to "Bearer private-header"),
            options = listOf("http-header-fields=Cookie: private-cookie"),
        )

        val rendered = media.toString()

        assertFalse("private-token" in rendered)
        assertFalse("private-header" in rendered)
        assertFalse("private-cookie" in rendered)
        assertTrue("uri=<redacted>" in rendered)
        assertTrue("headers=<redacted>" in rendered)
        assertTrue("options=1" in rendered)
    }
}
