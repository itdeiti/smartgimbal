package dev.smartgimbal.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingParamsTest {

    @Test
    fun `deadzone shrinks when zoomed in and stays for wide angle`() {
        val base = TrackingParams(deadzoneDeg = 3f)
        assertEquals(3f, base.forZoom(1.0).deadzoneDeg, 1e-6f)
        assertEquals(3f, base.forZoom(0.67).deadzoneDeg, 1e-6f)
        assertEquals(1.5f, base.forZoom(2.0).deadzoneDeg, 1e-6f)
        assertEquals(0.75f, base.forZoom(4.0).deadzoneDeg, 1e-6f)
    }
}
