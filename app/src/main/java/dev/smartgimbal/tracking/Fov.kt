package dev.smartgimbal.tracking

import kotlin.math.atan
import kotlin.math.tan

/** Field of view in degrees, along the sensor's long side ([longDeg]) and short side ([shortDeg]). */
data class Fov(val longDeg: Double, val shortDeg: Double) {

    /**
     * Horizontal and vertical field of view of a centred crop of the sensor with the frame's
     * aspect ratio and orientation (e.g. the 16:9 preview from a 4:3 sensor). A frame wider than
     * the sensor keeps the full long side and loses some of the short side, and vice versa.
     */
    fun forFrame(width: Int, height: Int): Pair<Double, Double> {
        val longPx = maxOf(width, height).toDouble()
        val shortPx = minOf(width, height).toDouble()
        val tanLong = tan(Math.toRadians(longDeg / 2))
        val tanShort = tan(Math.toRadians(shortDeg / 2))
        val frameAspect = longPx / shortPx
        val (long, short) = if (frameAspect >= tanLong / tanShort) {
            longDeg to Math.toDegrees(2 * atan(tanLong / frameAspect))
        } else {
            Math.toDegrees(2 * atan(tanShort * frameAspect)) to shortDeg
        }
        return if (width >= height) long to short else short to long
    }

    companion object {
        /** Rough Pixel 7 main camera values, used until the real characteristics are read. */
        val DEFAULT = Fov(longDeg = 70.0, shortDeg = 55.0)

        /** Pinhole model from the physical sensor size and focal length (all in mm). */
        fun fromSensor(sensorWidthMm: Float, sensorHeightMm: Float, focalLengthMm: Float): Fov {
            val long = maxOf(sensorWidthMm, sensorHeightMm)
            val short = minOf(sensorWidthMm, sensorHeightMm)
            return Fov(fullAngleDeg(long, focalLengthMm), fullAngleDeg(short, focalLengthMm))
        }

        private fun fullAngleDeg(size: Float, focal: Float) =
            Math.toDegrees(2.0 * atan(size / (2.0 * focal)))

        /**
         * Angle between the optical axis and a point at [offset] from the image centre, where
         * offset is -1 at one edge and +1 at the other, for a full field of view of [fovDeg].
         */
        fun angleFromCenterDeg(offset: Double, fovDeg: Double): Double =
            Math.toDegrees(atan(offset * tan(Math.toRadians(fovDeg / 2.0))))

        /** Inverse of [angleFromCenterDeg]; used by the simulation tests. */
        fun offsetForAngle(angleDeg: Double, fovDeg: Double): Double =
            tan(Math.toRadians(angleDeg)) / tan(Math.toRadians(fovDeg / 2.0))
    }
}
