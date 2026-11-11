package com.freedomfighter.readersrecorder.data

import com.freedomfighter.readers.speech.whisper.Language

/**
 * Which language a recording is transcribed in, once the recording has been listened to.
 *
 * Whisper told the wrong language does not write nonsense: it writes a fluent translation, which
 * nobody notices until reading. So nothing is taken on trust. With no language chosen, the one
 * heard is used. A language chosen for this one recording, from its menu, that the recording
 * plainly does not speak stops before the transcription and asks. The setting's language, applied
 * after a recording with nobody there to ask, gives way to the one plainly heard, and the
 * recording says so.
 */
object LanguageChoice {
    /**
     * [lang]: what whisper is told (null = left to it). [heard]: that language came from listening.
     * [overruled]: the setting's language that gave way. [ask]: stop and ask, this language was heard.
     */
    data class Outcome(val lang: String?, val heard: Boolean = false, val overruled: String = "", val ask: String = "")

    /** Whether listening is needed at all: not when the person already confirmed the language chosen. */
    fun listens(chosen: String, sure: Boolean): Boolean = chosen.isBlank() || !sure

    fun decide(chosen: String, fromSetting: Boolean, sure: Boolean, heard: Language.Heard?): Outcome {
        if (chosen.isBlank()) return Outcome(heard?.language, heard = heard != null)
        if (sure) return Outcome(chosen)
        val other = Language.suggest(chosen, heard)
            ?: return Outcome(chosen, heard = heard?.language == chosen)
        return if (fromSetting) Outcome(other, heard = true, overruled = chosen) else Outcome(chosen, ask = other)
    }
}
