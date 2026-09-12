package com.freedomfighter.readersrecorder.summary

import java.io.File

/**
 * The summary of a transcript, written on the phone.
 *
 * Measured on 2026-09-12 before any of this was built: a 3-billion model understands an
 * interview perfectly well, but cannot follow a composed instruction — asked for "a summary
 * then bullet points" it echoed the instruction and dumped its own notes. Asked for ONE thing,
 * "the main points, one per line", it obeyed exactly. So the phone asks for one thing only,
 * and the paragraph of synthesis is left to the workstation, which runs a far bigger model.
 *
 * A long transcript is read in pieces and the notes are merged, because the whole of an hour
 * of speech does not fit a context a phone can afford.
 */
object Summariser {
    /** Words per piece. A piece plus its instruction must sit well inside the context. */
    private const val WORDS_PER_PIECE = 900
    private const val MAX_POINTS = 10
    /** Points kept from each piece. Few enough that the merge sees the whole recording at once. */
    private const val POINTS_PER_PIECE = 8
    /** Below this, a recording is a note to self, not something to summarise. */
    private const val MIN_WORDS = 120

    private val PIECE = mapOf(
        "fr" to "Voici une partie de la transcription d'un enregistrement. Écris les points principaux de cette partie, un par ligne, chaque ligne commençant par « - ». N'écris rien d'autre.\n\nTRANSCRIPTION :\n%s",
        "en" to "Here is part of the transcript of a recording. Write the main points of this part, one per line, each line starting with \"- \". Write nothing else.\n\nTRANSCRIPT:\n%s",
        "de" to "Hier ist ein Teil der Abschrift einer Aufnahme. Schreibe die wichtigsten Punkte dieses Teils, einen pro Zeile, jede Zeile beginnt mit „- \". Schreibe sonst nichts.\n\nABSCHRIFT:\n%s",
        "es" to "Esta es una parte de la transcripción de una grabación. Escribe los puntos principales de esta parte, uno por línea, cada línea empezando por «- ». No escribas nada más.\n\nTRANSCRIPCIÓN:\n%s",
        "pt" to "Esta é uma parte da transcrição de uma gravação. Escreve os pontos principais desta parte, um por linha, cada linha a começar por «- ». Não escrevas mais nada.\n\nTRANSCRIÇÃO:\n%s",
        "ru" to "Вот часть расшифровки записи. Напиши основные мысли этой части, по одной в строке, каждая строка начинается с «- ». Больше ничего не пиши.\n\nРАСШИФРОВКА:\n%s",
    )
    private val MERGE = mapOf(
        "fr" to "Voici des notes prises sur un enregistrement, du début à la fin. Écris les huit points principaux de l'enregistrement entier, dans l'ordre, sans répéter deux fois la même idée, un par ligne, chaque ligne commençant par « - ». N'écris rien d'autre.\n\nNOTES :\n%s",
        "en" to "Here are notes taken from a recording, from beginning to end. Write the eight main points of the whole recording, in order, without saying the same thing twice, one per line, each line starting with \"- \". Write nothing else.\n\nNOTES:\n%s",
        "de" to "Hier sind Notizen zu einer Aufnahme, von Anfang bis Ende. Schreibe die acht wichtigsten Punkte der ganzen Aufnahme, der Reihe nach, ohne dasselbe zweimal zu sagen, einen pro Zeile, jede Zeile beginnt mit „- \". Schreibe sonst nichts.\n\nNOTIZEN:\n%s",
        "es" to "Estas son notas tomadas de una grabación, de principio a fin. Escribe los ocho puntos principales de toda la grabación, en orden, sin repetir la misma idea, uno por línea, cada línea empezando por «- ». No escribas nada más.\n\nNOTAS:\n%s",
        "pt" to "Estas são notas tiradas de uma gravação, do princípio ao fim. Escreve os oito pontos principais de toda a gravação, por ordem, sem repetir a mesma ideia, um por linha, cada linha a começar por «- ». Não escrevas mais nada.\n\nNOTAS:\n%s",
        "ru" to "Вот заметки по записи, от начала до конца. Напиши восемь основных мыслей всей записи, по порядку, не повторяя одно и то же, по одной в строке, каждая строка начинается с «- ». Больше ничего не пиши.\n\nЗАМЕТКИ:\n%s",
    )

    /** The instruction follows the language of the recording, and falls back to English. */
    private fun tongue(language: String): String =
        language.lowercase().take(2).takeIf { it in PIECE } ?: "en"

    /** Whole paragraphs, grouped into pieces of at most [WORDS_PER_PIECE] words. */
    fun pieces(text: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var words = 0
        for (para in text.split("\n\n").filter { it.isNotBlank() }) {
            val n = para.split(Regex("\\s+")).size
            if (words > 0 && words + n > WORDS_PER_PIECE) { out += cur.toString().trim(); cur.clear(); words = 0 }
            cur.append(para).append("\n\n"); words += n
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out
    }

    /**
     * Keeps only what was asked for. A small model sometimes adds a preamble or repeats itself;
     * rather than trusting it, the answer is filtered here — deterministic, and cheap.
     */
    fun keepPoints(raw: String, limit: Int = MAX_POINTS): List<String> =
        raw.lines()
            .map { it.trim() }
            .filter { it.startsWith("-") || it.startsWith("•") || it.startsWith("*") }
            .map { it.removePrefix("-").removePrefix("•").removePrefix("*").trim().trim('*') }
            .filter { it.length > 3 }
            .distinctBy { it.lowercase() }
            .take(limit)

    /**
     * The points of [transcript], or null when cancelled, when the model cannot be loaded, or
     * when nothing usable came back. Never throws: a recording keeps its transcript whatever
     * happens here.
     */
    fun summarise(
        model: File,
        transcript: String,
        language: String,
        onProgress: (Int) -> Unit = {},
        cancelled: () -> Boolean = { false },
    ): String? = runCatching {
        val lang = tongue(language)
        if (transcript.split(Regex("\\s+")).size < MIN_WORDS) return null
        val parts = pieces(transcript)

        LlamaSession(model).use { session ->
            if (!session.loaded) { android.util.Log.e(TAG, "no model: ${session.why()}"); return null }

            // Notes kept piece by piece rather than in one heap: if the merge fails, the fallback
            // can still take a little from every part of the recording instead of everything from
            // its first minutes.
            val byPiece = mutableListOf<List<String>>()
            parts.forEachIndexed { i, part ->
                if (cancelled()) { session.cancel(); return null }
                val answer = session.run(PIECE[lang]!!.format(part), maxTokens = 384) {
                    onProgress((100 * (i + it / 100f) / (parts.size + 1)).toInt())
                }
                if (answer == null) { android.util.Log.e(TAG, "piece ${i + 1}/${parts.size}: ${session.why()}"); return@forEachIndexed }
                byPiece += keepPoints(answer, limit = POINTS_PER_PIECE)
            }
            val notes = byPiece.flatten()
            if (notes.isEmpty()) return null
            if (byPiece.size == 1) return notes.take(MAX_POINTS).joinToString("\n") { "- $it" }

            if (cancelled()) return null
            val merged = session.run(MERGE[lang]!!.format(notes.joinToString("\n") { "- $it" }), maxTokens = 512) {
                onProgress((100 * (parts.size + it / 100f) / (parts.size + 1)).toInt())
            }
            val points = merged?.let { keepPoints(it) }.orEmpty().ifEmpty { spread(byPiece, MAX_POINTS) }
            points.joinToString("\n") { "- $it" }
        }
    }.getOrElse {
        android.util.Log.e(TAG, "summary failed: ${it.message}")
        null
    }

    /** [limit] points taken evenly across the pieces, so the whole recording is represented. */
    private fun spread(byPiece: List<List<String>>, limit: Int): List<String> {
        val out = mutableListOf<String>()
        var rank = 0
        while (out.size < limit && byPiece.any { it.size > rank }) {
            for (piece in byPiece) {
                piece.getOrNull(rank)?.let { if (out.size < limit) out += it }
            }
            rank++
        }
        return out
    }

    private const val TAG = "ReadersLlama"
}
