package dev.smartgimbal.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TargetSelectorTest {

    private fun box(cx: Float, cy: Float, size: Float) =
        NormBox(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)

    private fun face(id: Int?, cx: Float, cy: Float = 0.5f, size: Float = 0.2f) = DetectedFace(id, box(cx, cy, size))

    private val selector = TargetSelector(lostTimeoutMillis = 1_000, minIou = 0.3f)

    @Test
    fun `first frame picks the largest face`() {
        val small = face(1, 0.2f, size = 0.1f)
        val large = face(2, 0.7f, size = 0.3f)
        assertEquals(large, selector.update(listOf(small, large), 0))
    }

    @Test
    fun `stays on the target by tracking id even when another face is larger`() {
        selector.update(listOf(face(1, 0.3f, size = 0.2f)), 0)
        val target = face(1, 0.35f, size = 0.2f)
        val bigger = face(2, 0.7f, size = 0.4f)
        assertEquals(target, selector.update(listOf(bigger, target), 33))
    }

    @Test
    fun `without ids the target is matched by overlap`() {
        selector.update(listOf(face(null, 0.3f)), 0)
        val moved = face(null, 0.32f)
        val other = face(null, 0.8f, size = 0.3f)
        assertEquals(moved, selector.update(listOf(other, moved), 33))
    }

    @Test
    fun `lost target is awaited before switching`() {
        selector.update(listOf(face(1, 0.3f)), 0)
        val stranger = face(2, 0.8f)
        assertNull(selector.update(listOf(stranger), 500))
        assertNull(selector.update(emptyList(), 900))
        // Target comes back within the timeout: it is still the target.
        val back = face(1, 0.31f)
        assertEquals(back, selector.update(listOf(stranger, back), 950))
    }

    @Test
    fun `after the timeout the largest face takes over`() {
        selector.update(listOf(face(1, 0.3f)), 0)
        val stranger = face(2, 0.8f, size = 0.25f)
        assertNull(selector.update(listOf(stranger), 999))
        assertEquals(stranger, selector.update(listOf(stranger), 1_001))
        // ...and is kept from then on.
        assertEquals(face(2, 0.78f, size = 0.25f), selector.update(listOf(face(2, 0.78f, size = 0.25f)), 1_034))
    }

    @Test
    fun `reset forgets the target`() {
        selector.update(listOf(face(1, 0.3f)), 0)
        selector.reset()
        val other = face(2, 0.8f)
        assertEquals(other, selector.update(listOf(other), 10))
    }

    @Test
    fun `iou of identical, disjoint and half overlapping boxes`() {
        val a = NormBox(0f, 0f, 0.2f, 0.2f)
        assertEquals(1f, a.iou(a), 1e-6f)
        assertEquals(0f, a.iou(NormBox(0.5f, 0.5f, 0.7f, 0.7f)), 1e-6f)
        // Shifted by half its width: intersection 0.02, union 0.06.
        assertEquals(1f / 3f, a.iou(NormBox(0.1f, 0f, 0.3f, 0.2f)), 1e-6f)
    }
}
