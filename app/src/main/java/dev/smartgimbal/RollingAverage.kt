package dev.smartgimbal

/** Exponential moving average; the first sample seeds it. Not thread-safe. */
class RollingAverage(private val alpha: Float = 0.1f) {

    private var seeded = false

    var value = 0f
        private set

    fun add(sample: Float): Float {
        value = if (seeded) value * (1 - alpha) + sample * alpha else sample
        seeded = true
        return value
    }
}
