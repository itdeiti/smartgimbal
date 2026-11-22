package dev.smartgimbal.camera

/** Self-timer before a photo; [OFF] shoots at once. */
enum class PhotoTimer(val seconds: Int, val label: String) {
    OFF(0, "aus"),
    S3(3, "3 s"),
    S10(10, "10 s"),
    ;

    /** Next choice for the timer button. */
    fun next(): PhotoTimer = entries[(ordinal + 1) % entries.size]
}
