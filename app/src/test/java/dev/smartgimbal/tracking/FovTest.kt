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
        val fov = Fov.fromSensor(4f, 3f, 3f) // exactly 4:3
        val (h, v) = fov.forFrame(640, 480)
        assertEquals(fov.longDeg, h, 1e-9)
        assertEquals(fov.shortDeg, v, 1e-9)
        val (hp, vp) = fov.forFrame(480, 640)
        assertEquals(fov.shortDeg, hp, 1e-9)
        assertEquals(fov.longDeg, vp, 1e-9)
    }

    @Test
    fun `16 by 9 crop of a 4 by 3 sensor keeps the long side`() {
        // Pixel 7 main camera: 71.4 deg on the long side; the 16:9 preview loses top and bottom.
        val fov = Fov(71.4, 56.9)
        val (h, v) = fov.forFrame(1080, 1920) // portrait preview
        assertEquals(71.4, v, 1e-9)
        assertEquals(44.0, h, 0.2)
    }

    @Test
    fun `square crop keeps the short side`() {
        val fov = Fov.fromSensor(4f, 3f, 3f)
        val (h, v) = fov.forFrame(500, 500)
        assertEquals(fov.shortDeg, h, 1e-9)
        assertEquals(fov.shortDeg, v, 1e-9)
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
