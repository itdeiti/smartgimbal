package dev.smartgimbal.gimbal

import org.junit.Assert.assertEquals
import org.junit.Test

class CommandArbiterTest {

    private val arbiter = CommandArbiter(trackerTimeoutMillis = 300)

    @Test
    fun `idle arbiter stops`() {
        assertEquals(ArbitratedCommand(MoveCommand.STOP, null), arbiter.current(1_000))
    }

    @Test
    fun `fresh tracker command is used`() {
        arbiter.setTracker(MoveCommand(120, -40), nowMillis = 1_000)
        assertEquals(ArbitratedCommand(MoveCommand(120, -40), CommandSource.TRACKER), arbiter.current(1_300))
    }

    @Test
    fun `stale tracker command stops the gimbal`() {
        arbiter.setTracker(MoveCommand(120, 0), nowMillis = 1_000)
        assertEquals(ArbitratedCommand(MoveCommand.STOP, null), arbiter.current(1_301))
    }

    @Test
    fun `manual wins over tracker and hands back on release`() {
        arbiter.setTracker(MoveCommand(120, 0), nowMillis = 1_000)
        arbiter.setManual(MoveCommand(0, 200))
        assertEquals(ArbitratedCommand(MoveCommand(0, 200), CommandSource.MANUAL), arbiter.current(1_100))

        arbiter.setManual(null)
        assertEquals(CommandSource.TRACKER, arbiter.current(1_100).source)
    }

    @Test
    fun `tracker stop inside the deadzone is still a tracker command`() {
        arbiter.setTracker(MoveCommand.STOP, nowMillis = 1_000)
        assertEquals(ArbitratedCommand(MoveCommand.STOP, CommandSource.TRACKER), arbiter.current(1_050))
    }

    @Test
    fun `withdrawn tracker stops`() {
        arbiter.setTracker(MoveCommand(120, 0), nowMillis = 1_000)
        arbiter.setTracker(null, nowMillis = 1_010)
        assertEquals(ArbitratedCommand(MoveCommand.STOP, null), arbiter.current(1_020))
    }
}
