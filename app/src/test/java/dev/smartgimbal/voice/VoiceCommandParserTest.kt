package dev.smartgimbal.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandParserTest {

    private val idle = VoiceContext(recording = false, gimbalReady = true, trackingOn = false)
    private val recording = idle.copy(recording = true)

    /** Same shape as Vosk's result JSON with setWords(true). */
    private fun result(text: String, vararg conf: Double): String {
        val words = text.split(" ").filter { it.isNotEmpty() }
        val entries = words.mapIndexed { i, w ->
            """{ "conf" : ${conf.getOrElse(i) { 1.0 }}, "end" : 1.2, "start" : 0.8, "word" : "$w" }"""
        }
        return """{ "result" : [ ${entries.joinToString(", ")} ], "text" : "$text" }"""
    }

    private fun VoiceDecision.reason() = (this as? VoiceDecision.Ignore)?.reason

    @Test
    fun `whole command with good confidence is executed`() {
        val d = VoiceCommandParser().decide(result("aufnahme starten", 0.95, 0.9), idle, 0.7, 1_000)
        assertEquals(VoiceDecision.Execute(VoiceCommand.START_RECORDING, "aufnahme starten", 0.9), d)
    }

    @Test
    fun `single word from the grammar is not a command`() {
        // Captured on the device: Vosk returned just "starten" with confidence 1.0.
        val json = """{ "result" : [{ "conf" : 1.000000, "end" : 4.41, "start" : 3.93, "word" : "starten" }], "text" : "starten" }"""
        assertEquals("kein ganzer Befehl", VoiceCommandParser().decide(json, idle, 0.7, 1_000).reason())
    }

    @Test
    fun `empty and unknown results are ignored`() {
        val parser = VoiceCommandParser()
        assertEquals("leer", parser.decide("""{ "text" : "" }""", idle, 0.7, 1_000).reason())
        assertEquals("kein ganzer Befehl", parser.decide(result("[unk]"), idle, 0.7, 1_000).reason())
    }

    @Test
    fun `weakest word decides the confidence`() {
        val d = VoiceCommandParser().decide(result("tracking an", 0.95, 0.5), idle, 0.7, 1_000)
        assertEquals("Konfidenz zu niedrig", d.reason())
        assertEquals(0.5, d.confidence, 1e-9)
    }

    @Test
    fun `same command within the debounce window runs once`() {
        val parser = VoiceCommandParser(debounceMillis = 1_500)
        val trackingOff = idle.copy(trackingOn = true)
        assertTrue(parser.decide(result("kamera zurück"), trackingOff, 0.7, 1_000) is VoiceDecision.Execute)
        assertEquals("Wiederholung", parser.decide(result("kamera zurück"), trackingOff, 0.7, 2_000).reason())
        assertTrue(parser.decide(result("kamera zurück"), trackingOff, 0.7, 2_600) is VoiceDecision.Execute)
    }

    @Test
    fun `ignored command does not start the debounce`() {
        val parser = VoiceCommandParser()
        assertEquals("keine Aufnahme", parser.decide(result("stopp"), idle, 0.7, 1_000).reason())
        assertTrue(parser.decide(result("stopp"), recording, 0.7, 1_100) is VoiceDecision.Execute)
    }

    @Test
    fun `commands are checked against the app state`() {
        val parser = VoiceCommandParser()
        assertEquals("Aufnahme läuft schon", parser.decide(result("aufnahme starten"), recording, 0.7, 1_000).reason())
        val noGimbal = idle.copy(gimbalReady = false)
        assertEquals("kein Gimbal", parser.decide(result("tracking an"), noGimbal, 0.7, 1_000).reason())
        assertEquals("kein Gimbal", parser.decide(result("kamera zurück"), noGimbal, 0.7, 1_000).reason())
        assertEquals("Tracking ist schon aus", parser.decide(result("tracking aus"), idle, 0.7, 1_000).reason())
        assertEquals("Tracking ist schon an", parser.decide(result("tracking an"), idle.copy(trackingOn = true), 0.7, 1_000).reason())
    }

    @Test
    fun `grammar contains every phrase`() {
        assertEquals(
            listOf(
                "aufnahme starten", "stopp", "aufnahme stopp", "aufnahme stoppen", "stoppen", "abbrechen",
                "tracking an", "tracking aus",
                "kamera zurück", "käse", "weitwinkel an", "weitwinkel aus",
            ),
            VoiceCommand.GRAMMAR,
        )
    }

    @Test
    fun `photo only without recording and not twice`() {
        val parser = VoiceCommandParser()
        assertEquals("Foto nur ohne Aufnahme", parser.decide(result("käse"), recording, 0.7, 1_000).reason())
        assertEquals("Selbstauslöser läuft schon", parser.decide(result("käse"), idle.copy(photoCountdown = true), 0.7, 1_000).reason())
        assertTrue(parser.decide(result("käse"), idle, 0.7, 1_000) is VoiceDecision.Execute)
    }

    @Test
    fun `stop cancels the self timer`() {
        val d = VoiceCommandParser().decide(result("stopp"), idle.copy(photoCountdown = true), 0.7, 1_000)
        assertTrue(d is VoiceDecision.Execute && d.command == VoiceCommand.STOP)
    }

    @Test
    fun `wide angle depends on camera and state`() {
        val parser = VoiceCommandParser()
        assertEquals("Frontkamera hat kein Weitwinkel", parser.decide(result("weitwinkel an"), idle.copy(frontCamera = true), 0.7, 1_000).reason())
        assertEquals("Weitwinkel ist schon aus", parser.decide(result("weitwinkel aus"), idle, 0.7, 1_000).reason())
        assertEquals("Weitwinkel ist schon an", parser.decide(result("weitwinkel an"), idle.copy(wideAngle = true), 0.7, 1_000).reason())
        assertTrue(parser.decide(result("weitwinkel an"), idle, 0.7, 1_000) is VoiceDecision.Execute)
    }

    @Test
    fun `stop has natural variants`() {
        for (phrase in listOf("stopp", "aufnahme stopp", "aufnahme stoppen", "stoppen", "abbrechen")) {
            val d = VoiceCommandParser().decide(result(phrase), recording, 0.7, 1_000)
            assertTrue(phrase, d is VoiceDecision.Execute && d.command == VoiceCommand.STOP)
        }
    }
}
