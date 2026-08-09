package com.andreassamitsch.ilauncher.data.openwebif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenWebifUrlTest {
    @Test fun `adds http scheme and trailing slash`() {
        assertEquals("http://192.168.1.20/", OpenWebifUrl.normalize("192.168.1.20"))
    }

    @Test fun `keeps explicit port and https`() {
        assertEquals("https://gigablue.local:443/", OpenWebifUrl.normalize("https://gigablue.local:443"))
    }

    @Test fun `rejects credentials embedded in receiver URL`() {
        assertNull(OpenWebifUrl.normalize("http://root:secret@192.168.1.20/"))
    }

    @Test fun `resolves relative picon URL against receiver`() {
        assertEquals(
            "http://192.168.1.20/picon/test.png",
            OpenWebifUrl.resolve("http://192.168.1.20/", "/picon/test.png"),
        )
    }
}
