package com.freedomfighter.readersrecorder.data

import org.json.JSONObject

/**
 * The Reader's credentials file, the same on the phone and on the desktop: one JSON file,
 * `{"format": "readers-credentials", "version": 1, "<app>": {…}}`, one section per app. Carrying it
 * to another phone or to the Reader's apps on a computer sets the WebDAV folder up in one step. Only
 * our section and only the keys we know are read; the look is never in it. It holds the password.
 */
object Credentials {
    const val FORMAT = "readers-credentials"
    const val SECTION = "readers-recorder"
    /** Reader's Notes keeps the same kind of account: its server and login do for us too (not its folder). */
    const val FALLBACK = "readers-notes"
    const val FILE_NAME = "readers-credentials-recorder.json"

    /** What a file brings; null = not in the file, keep what is set. */
    data class Account(val server: String?, val folder: String?, val username: String?, val password: String?)
    data class Imported(val account: Account, val fromFallback: Boolean)

    class NotCredentials : Exception("not a Reader's credentials file")
    class NothingForUs : Exception("this file holds nothing for $SECTION")

    fun build(server: String, folder: String, username: String, password: String): String {
        val section = JSONObject()
        if (server.isNotBlank()) section.put("server", server)
        if (folder.isNotBlank()) section.put("folder", folder)
        if (username.isNotBlank()) section.put("username", username)
        if (password.isNotEmpty()) section.put("password", password)
        return JSONObject().put("format", FORMAT).put("version", 1).put(SECTION, section).toString(2)
    }

    fun read(text: String): Imported {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: throw NotCredentials()
        if (root.optString("format") != FORMAT) throw NotCredentials()
        root.optJSONObject(SECTION)?.let { s ->
            val a = Account(s.str("server"), s.str("folder"), s.str("username"), s.str("password"))
            if (a != Account(null, null, null, null)) return Imported(a, false)
        }
        root.optJSONObject(FALLBACK)?.let { s ->
            val a = Account(s.str("server"), null, s.str("username"), s.str("password"))
            if (a.server != null) return Imported(a, true)
        }
        throw NothingForUs()
    }

    private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) (opt(key) as? String) else null
}
