package dev.smartgimbal.tracking

import org.junit.Assert.assertEquals
import org.junit.Test

class FovTest {

    @Test
    fun `sensor size and focal length give the expected angles`() {
        // 36 x 24 mm with a 50 mm lens: the classic 39.6 deg x 27.0 deg.
        val fov = Fov.fromSensor(36f, 24f, 50f)
        assertEquals(39.6, fov.longDeg, 0.1)
        assertEquals(27.0, fov.shortDeg, 0.1)
    }

    @Test
    fun `portrait frames swap the axes`() {
        val fov = Fov(70.0, 55.0)
        assertEquals(70.0 to 55.0, fov.forFrame(640, 480))
        assertEquals(55.0 to 70.0, fov.forFrame(480, 640))
    }

    @Test
    fun `edge of the image is half the field of view`() {
        assertEquals(35.0, Fov.angleFromCenterDeg(1.0, 70.0), 1e-9)
        assertEquals(-35.0, Fov.angleFromCenterDeg(-1.0, 70.0), 1e-9)
        assertEquals(0.0, Fov.angleFromCenterDeg(0.0, 70.0), 1e-9)
    }

    @Test
    fun `angle and offset are inverse`() {
        val offset = Fov.offsetForAngle(12.0, 70.0)
        assertEquals(12.0, Fov.angleFromCenterDeg(offset, 70.0), 1e-9)
    }
}
