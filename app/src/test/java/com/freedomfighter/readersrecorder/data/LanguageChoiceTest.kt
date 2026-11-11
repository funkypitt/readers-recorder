package com.freedomfighter.readersrecorder.data

import com.freedomfighter.readers.speech.whisper.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageChoiceTest {
    private val english = Language.Heard("en", 0.92f, mapOf("en" to 0.92f, "fr" to 0.03f), 3)
    private val unsure = Language.Heard("en", 0.55f, mapOf("en" to 0.55f, "fr" to 0.35f), 3)

    @Test fun nothingStoredMeansListening() {
        assertEquals("", Prefs.storedLanguage(null))
        assertEquals("fr", Prefs.storedLanguage("fr"))
        assertEquals("", Prefs.storedLanguage(""))
    }

    @Test fun listeningFirstAmongTheChoices() { assertEquals("", Prefs.languages().first()) }

    @Test fun recordingChoiceBeforeTheSetting() {
        val r = Recording("1", "t", 0, 1000, "memo", false, false, false)
        assertEquals("fr" to true, r.chosenLanguage("fr"))
        assertEquals("" to false, r.chosenLanguage(""))
        assertEquals("" to false, r.copy(language = "auto").chosenLanguage("fr"))
        assertEquals("de" to false, r.copy(language = "de").chosenLanguage("fr"))
    }

    @Test fun noChoiceTakesWhatIsHeard() {
        assertEquals(LanguageChoice.Outcome("en", heard = true), LanguageChoice.decide("", false, false, english))
        assertEquals(LanguageChoice.Outcome(null), LanguageChoice.decide("", false, false, null))
    }

    @Test fun theSettingGivesWayAndSaysSo() {
        assertEquals(LanguageChoice.Outcome("en", heard = true, overruled = "fr"), LanguageChoice.decide("fr", true, false, english))
    }

    @Test fun aChoiceByHandAsks() {
        assertEquals(LanguageChoice.Outcome("fr", ask = "en"), LanguageChoice.decide("fr", false, false, english))
    }

    @Test fun anAnswerIsNotAskedAgain() {
        assertFalse(LanguageChoice.listens("fr", true))
        assertTrue(LanguageChoice.listens("", true))
        assertEquals(LanguageChoice.Outcome("fr"), LanguageChoice.decide("fr", false, true, english))
    }

    @Test fun doubtLeavesTheChoiceAlone() {
        assertEquals(LanguageChoice.Outcome("fr"), LanguageChoice.decide("fr", true, false, unsure))
        assertEquals(LanguageChoice.Outcome("en", heard = true), LanguageChoice.decide("en", false, false, english))
    }
}
