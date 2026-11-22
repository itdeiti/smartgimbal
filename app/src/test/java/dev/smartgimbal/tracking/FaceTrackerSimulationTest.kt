package dev.smartgimbal.tracking

import dev.smartgimbal.gimbal.MoveCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Closed-loop tests against a simulated gimbal, built from measurements on the device:
 * speed = 0.22 deg/s per unit, saturating at 60 deg/s, smoothed by the gimbal itself with
 * tau ~0.25 s (tuning run 2026-11-22); preview-copy analysis every ~45 ms with ~140 ms latency;
 * the 20 Hz send loop plus 30 ms transmission. With this plant the old Kp 2 / Kd 0 overshoots
 * by 30-40 %, exactly as seen live.
 *
 * Camera geometry, confirmed by the first live test: operator terms are seen from the display
 * side, so pan right turns both cameras towards their own left, and tilt up points the front
 * camera up and the rear camera down. The face direction is given relative to the camera's
 * initial axis (positive = camera's right / up).
 */
class FaceTrackerSimulationTest {

    private class Sim(val panError: DoubleArray, val tiltError: DoubleArray, val commands: List<Pair<Int, MoveCommand>>, val finalTilt: Double)

    private val fov = Fov(longDeg = 70.0, shortDeg = 55.0)

    private fun simulate(
        front: Boolean,
        seconds: Double,
        params: TrackingParams = TrackingParams(),
        faceRight: (Double) -> Double,
        faceUp: (Double) -> Double,
    ): Sim {
        val tracker = FaceTracker()
        val totalMs = (seconds * 1000).toInt()
        var pan = 0.0
        var tilt = 0.0
        var panVel = 0.0
        var tiltVel = 0.0
        val smoothing = 1 - kotlin.math.exp(-0.001 / GIMBAL_TAU_SEC)
        var latest = MoveCommand.STOP
        var applied = MoveCommand.STOP
        val frames = ArrayDeque<Pair<Int, NormBox>>()
        val sends = ArrayDeque<Pair<Int, MoveCommand>>()
        val panErr = DoubleArray(totalMs)
        val tiltErr = DoubleArray(totalMs)
        val commands = mutableListOf<Pair<Int, MoveCommand>>()

        for (ms in 0 until totalMs) {
            val t = ms / 1000.0
            // Where the face appears to the camera right now.
            val rightDeg = faceRight(t) + pan
            val upDeg = faceUp(t) - if (front) tilt else -tilt
            panErr[ms] = rightDeg
            tiltErr[ms] = upDeg

            if (ms % FRAME_PERIOD_MS == 0) {
                // Portrait analysis frame 480 x 640: horizontal FOV is the short side.
                val cx = 0.5 + Fov.offsetForAngle(rightDeg, fov.shortDeg) / 2
                val cy = 0.5 - Fov.offsetForAngle(upDeg, fov.longDeg) / 2
                val box = NormBox((cx - 0.1).toFloat(), (cy - 0.1).toFloat(), (cx + 0.1).toFloat(), (cy + 0.1).toFloat())
                frames.addLast(ms + DETECTION_LATENCY_MS to box)
            }
            while (frames.isNotEmpty() && frames.first().first <= ms) {
                val box = frames.removeFirst().second
                latest = tracker.update(box, 480, 640, fov, front, tilt, 1_000L + ms, params).command
                commands += ms to latest
            }
            if (ms % 50 == 0) sends.addLast(ms + 30 to latest)
            while (sends.isNotEmpty() && sends.first().first <= ms) applied = sends.removeFirst().second

            panVel += (gimbalSpeed(applied.panRight) - panVel) * smoothing
            tiltVel += (gimbalSpeed(applied.tiltUp) - tiltVel) * smoothing
            pan += panVel / 1000.0
            tilt += tiltVel / 1000.0
        }
        return Sim(panErr, tiltErr, commands, tilt)
    }

    private fun gimbalSpeed(units: Int) = (units * DEG_PER_SEC_PER_UNIT).coerceIn(-60.0, 60.0)

    @Test
    fun `blocked tilt is learned as end stop`() {
        // Tuning run: tilt stuck at 26.9 deg while the tracker kept commanding up.
        val tracker = FaceTracker()
        val above = NormBox(0.4f, 0.05f, 0.6f, 0.25f) // face well above centre
        var cmd = MoveCommand.STOP
        for (i in 0 until 90) { // ~3 s of frames, telemetry frozen at the stop
            cmd = tracker.update(above, 480, 640, fov, true, 26.9, 1_000L + i * 33, TrackingParams()).command
        }
        assertEquals("tilt stops pushing", 0, cmd.tiltUp)
        assertEquals(26.4, tracker.learnedTiltLimits.first!!, 1e-9)
    }

    private companion object {
        const val GIMBAL_TAU_SEC = 0.25
        const val DETECTION_LATENCY_MS = 140
        const val FRAME_PERIOD_MS = 45
    }

    /** Largest error with the opposite sign of the initial error, i.e. overshoot. */
    private fun overshoot(errors: DoubleArray): Double {
        val sign = if (errors.first() >= 0) 1.0 else -1.0
        return errors.maxOf { (-it * sign).coerceAtLeast(0.0) }
    }

    private fun DoubleArray.tailFrom(seconds: Double) = copyOfRange((seconds * 1000).toInt(), size)

    @Test
    fun `rear camera centres an off-centre face without overshoot`() {
        val sim = simulate(front = false, seconds = 4.0, faceRight = { 20.0 }, faceUp = { 8.0 })
        assertTrue("pan settles", sim.panError.tailFrom(2.0).all { abs(it) < 3.0 })
        assertTrue("tilt settles", sim.tiltError.tailFrom(2.0).all { abs(it) < 3.0 })
        assertTrue("pan overshoot ${overshoot(sim.panError)}", overshoot(sim.panError) < 1.0)
        assertTrue("tilt overshoot ${overshoot(sim.tiltError)}", overshoot(sim.tiltError) < 1.0)
    }

    @Test
    fun `old gains overshoot like in the tuning run`() {
        val old = TrackingParams(kp = 2.0f, kd = 0.0f)
        val sim = simulate(front = false, seconds = 4.0, params = old, faceRight = { 20.0 }, faceUp = { 0.0 })
        assertTrue("overshoot ${overshoot(sim.panError)}", overshoot(sim.panError) > 5.0)
    }

    @Test
    fun `front camera centres the face with the flipped tilt sign`() {
        val sim = simulate(front = true, seconds = 4.0, faceRight = { -18.0 }, faceUp = { 10.0 })
        assertTrue("pan settles", sim.panError.tailFrom(2.0).all { abs(it) < 3.0 })
        assertTrue("tilt settles", sim.tiltError.tailFrom(2.0).all { abs(it) < 3.0 })
    }

    @Test
    fun `front camera with the rear tilt sign runs away`() {
        val wrong = TrackingParams(frontTiltSign = -1)
        val sim = simulate(front = true, seconds = 2.0, params = wrong, faceRight = { 0.0 }, faceUp = { 10.0 })
        assertTrue("error grows", abs(sim.tiltError.last()) > 10.0)
    }

    @Test
    fun `old pan sign from the first live test runs away`() {
        // Live test 2026-11-22: face right of centre, pan right, face drifted further right.
        val old = TrackingParams(rearPanSign = 1)
        val sim = simulate(front = false, seconds = 2.0, params = old, faceRight = { 15.0 }, faceUp = { 0.0 })
        assertTrue("error grows", abs(sim.panError.last()) > 15.0)
    }

    @Test
    fun `short detector dropouts do not stop the gimbal hard`() {
        val tracker = FaceTracker()
        val params = TrackingParams()
        val box = NormBox(0.8f, 0.4f, 0.95f, 0.6f) // well right of centre
        var last = MoveCommand.STOP
        repeat(15) { last = tracker.update(box, 480, 640, fov, false, 0.0, 1_000L + it * 33, params).command }
        val moving = abs(last.panRight)
        assertTrue("moving before the dropout", moving > 100)

        // One missed frame: speed ramps down a little instead of dropping to zero.
        val coast = tracker.update(null, 480, 640, fov, false, 0.0, 1_000L + 15 * 33, params).command
        assertTrue("coasting ${coast.panRight}", abs(coast.panRight) in 1 until moving)

        // Face back: continues without a restart from zero.
        val back = tracker.update(box, 480, 640, fov, false, 0.0, 1_000L + 16 * 33, params).command
        assertTrue("resumes ${back.panRight}", abs(back.panRight) >= abs(coast.panRight))
    }

    @Test
    fun `gimbal stands still once centred`() {
        val sim = simulate(front = false, seconds = 5.0, faceRight = { 15.0 }, faceUp = { 0.0 })
        val late = sim.commands.filter { it.first > 3_000 }
        assertTrue("no jitter after settling", late.all { it.second.isStop })
    }

    @Test
    fun `face inside the deadzone never moves the gimbal`() {
        val sim = simulate(front = false, seconds = 2.0, faceRight = { 2.0 }, faceUp = { -2.0 })
        assertTrue(sim.commands.all { it.second.isStop })
    }

    @Test
    fun `walking face is followed with bounded lag`() {
        // Person walks across at 20 deg/s; the damped controller trails by ~15 deg, well inside
        // the 22 deg half field of view of the portrait preview.
        val sim = simulate(front = false, seconds = 5.0, faceRight = { 20.0 * it }, faceUp = { 0.0 })
        val worst = sim.panError.tailFrom(2.0).maxOf { abs(it) }
        assertTrue("lag $worst deg", worst < 18.0)
    }

    @Test
    fun `tilt does not push past the end stop`() {
        val sim = simulate(front = false, seconds = 4.0, faceRight = { 0.0 }, faceUp = { 45.0 })
        assertTrue("tilt ${sim.finalTilt}", sim.finalTilt < 31.0)
    }

    @Test
    fun `face gone for longer than the grace period stops`() {
        val tracker = FaceTracker()
        val box = NormBox(0.8f, 0.4f, 0.95f, 0.6f)
        repeat(10) { tracker.update(box, 480, 640, fov, false, 0.0, 1_000L + it * 33, TrackingParams()) }
        // Last sighting at 1297 ms; 300 ms later is past the 250 ms grace period.
        val lost = tracker.update(null, 480, 640, fov, false, 0.0, 1_600, TrackingParams())
        assertEquals(MoveCommand.STOP, lost.command)
    }
}
