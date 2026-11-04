package com.freedomfighter.readersrecorder.notes

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.freedomfighter.readersrecorder.data.Recording

/**
 * « make it a note »: a recording's title, its main points and its transcript become a new note
 * in Reader's Notes, ending with a line that opens the recording again from the note's ⋯ menu.
 */
object ToNotes {
    const val PACKAGE = "com.freedomfighter.readersnotes"
    private val NOTES: Uri = Uri.parse("content://$PACKAGE/notes")

    /** Offered only when Reader's Notes is on the phone (visible through the manifest's <queries>). */
    fun installed(context: Context): Boolean = runCatching { context.packageManager.getPackageInfo(PACKAGE, 0); true }.getOrDefault(false)

    /** The link back: Recorder opens content://…/recordings/<id> on that recording's page. */
    fun linkTo(r: Recording): String = "content://com.freedomfighter.readersrecorder/recordings/${r.id}"

    fun text(r: Recording, summary: String, summaryHeading: String, transcript: String): String = buildString {
        append(r.title.trim()).append("\n\n")
        if (summary.isNotBlank()) append(summaryHeading).append('\n').append(summary.trim()).append("\n\n")
        append(transcript.trim()).append("\n\n")
        append(linkTo(r))
    }

    /**
     * Create the note through Notes' provider (same signing key) and open it; when that is refused,
     * hand the text to Notes, which turns shared text into a note. False when neither worked.
     */
    fun make(context: Context, text: String): Boolean {
        val created = runCatching { context.contentResolver.insert(NOTES, ContentValues().apply { put("text", text) }) }.getOrNull()
        if (created != null) {
            val open = Intent(Intent.ACTION_VIEW, created).setClassName(PACKAGE, "$PACKAGE.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(open) }.isSuccess) return true
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(PACKAGE).putExtra(Intent.EXTRA_TEXT, text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // a note was already made by the provider: sharing again would make a second one
        if (created != null) return false
        return runCatching { context.startActivity(send) }.isSuccess
    }
}
