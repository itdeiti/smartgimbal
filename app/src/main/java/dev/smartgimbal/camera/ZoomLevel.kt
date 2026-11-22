package dev.smartgimbal.camera

/**
 * Zoom steps of the camera. [ratio] null means the camera's minimum zoom ratio, which on the
 * Pixel 7 rear camera (0.67) switches to the ultrawide lens. 2x is a sensor crop of the main
 * camera, 4x is digital zoom (the Pixel 7 has no tele lens).
 */
enum class ZoomLevel(val ratio: Float?, val label: String) {
    WIDE(null, "0,7×"),
    NORMAL(1f, "1×"),
    X2(2f, "2×"),
    X4(4f, "4×"),
    ;

    /** Next step for the zoom button; the front camera skips the ultrawide step. */
    fun next(frontCamera: Boolean): ZoomLevel {
        val order = if (frontCamera) listOf(NORMAL, X2, X4) else entries
        val i = order.indexOf(this)
        return order[(i + 1).mod(order.size)]
    }
}
