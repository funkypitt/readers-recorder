package com.freedomfighter.readersrecorder.whisper

/**
 * Whisper imitates the style of the text it is given as a prompt: a short, well-punctuated
 * sentence in the language spoken nudges it towards full sentences, commas and capitals.
 * The sentences say nothing about any subject, so they bias no vocabulary.
 */
object Prompts {
    private val STYLE = mapOf(
        "en" to "Hello. This is a careful transcript, with full sentences, commas and full stops.",
        "fr" to "Bonjour. Voici une transcription soignée, avec des phrases complètes, des virgules et des points.",
        "de" to "Guten Tag. Dies ist eine sorgfältige Abschrift, mit ganzen Sätzen, Kommas und Punkten.",
        "es" to "Hola. Esta es una transcripción cuidada, con frases completas, comas y puntos.",
        "pt" to "Olá. Esta é uma transcrição cuidada, com frases completas, vírgulas e pontos finais.",
        "ru" to "Здравствуйте. Это аккуратная расшифровка, с полными предложениями, запятыми и точками.",
        "it" to "Buongiorno. Questa è una trascrizione accurata, con frasi complete, virgole e punti."
    )

    /** Languages offered by hand: the phone's, English, detected (""), then the Reader's languages. */
    fun choices(device: String): List<String> = listOf(device, "en", "", "fr", "de", "es", "pt", "ru", "it").distinct()

    /** The style sentence; none when the language is left to detection. */
    fun style(language: String?): String = language?.let { STYLE[it] } ?: ""

    /** For a piece after the first: the style sentence, then the end of what came before, so the context runs on. */
    fun forPiece(language: String?, previous: String): String {
        val tail = if (previous.length <= 240) previous else previous.takeLast(240).substringAfter(' ')
        return listOf(style(language), tail.trim()).filter { it.isNotBlank() }.joinToString(" ")
    }
}

/**
 * Paragraphs from timed segments. A break only where a sentence has ended: after a pause of
 * 1.2 s once the paragraph has some body, or as soon as it passes about 600 characters. A
 * runaway sentence is cut at about 1,200 characters all the same.
 */
object Paragraphs {
    private const val PAUSE_MS = 1_200L

    fun build(segments: List<Segment>, noSpeech: String): String {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var lastEnd = -1L
        for (s in segments) {
            val text = s.text.trim()
            if (text.isEmpty()) continue
            val gap = if (lastEnd < 0) 0L else s.startMs - lastEnd
            val len = cur.length
            val ended = len > 0 && endsSentence(cur)
            if (len > 0 && ((ended && gap >= PAUSE_MS && len >= 250) || (ended && len >= 600) || len >= 1_200)) {
                out.add(cur.toString().trim()); cur.setLength(0)
            }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(text)
            lastEnd = s.endMs
        }
        if (cur.isNotEmpty()) out.add(cur.toString().trim())
        return if (out.isEmpty()) noSpeech + "\n" else out.joinToString("\n\n") + "\n"
    }

    private fun endsSentence(s: CharSequence): Boolean {
        var i = s.length - 1
        while (i >= 0 && (s[i].isWhitespace() || s[i] in "\"'»”’)")) i--
        return i >= 0 && s[i] in ".!?…"
    }
}
