package com.freedomfighter.readersrecorder.sync

import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class WebDavException(message: String) : IOException(message)

data class RemoteFile(val name: String, val etag: String?, val modified: Long, val isDir: Boolean)

/** The five WebDAV requests a notes folder needs: PROPFIND, GET, PUT, DELETE, MKCOL. */
class WebDav(private val username: String, private val password: String) {
    private val auth = "Basic " + Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)

    private class Resp(val code: Int, val body: String, val headers: Map<String, List<String>>)

    private fun request(method: String, url: String, body: ByteArray? = null, depth: Int? = null, headers: Map<String, String> = emptyMap(), contentType: String = "text/plain; charset=utf-8", allow: Set<Int> = emptySet()): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        try { c.requestMethod = method } catch (e: java.net.ProtocolException) { setMethodByReflection(c, method) }
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("Authorization", auth)
        c.setRequestProperty("User-Agent", "readers-recorder")
        if (depth != null) c.setRequestProperty("Depth", depth.toString())
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", contentType) }
        try {
            if (body != null) c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.readBytes()?.toString(Charsets.UTF_8) ?: ""
            if (code == 401) throw WebDavException("wrong username or password")
            if (code >= 400 && code !in allow) throw WebDavException("$method: HTTP $code")
            return Resp(code, text, c.headerFields)
        } finally { c.disconnect() }
    }

    private fun setMethodByReflection(c: HttpURLConnection, method: String) {
        var target: Any = c
        runCatching { val f = c.javaClass.getDeclaredField("delegate"); f.isAccessible = true; f.get(c)?.let { target = it } }
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            try { val f = cls.getDeclaredField("method"); f.isAccessible = true; f.set(target, method); return } catch (_: NoSuchFieldException) { cls = cls.superclass }
        }
        throw WebDavException("cannot send $method on this device")
    }

    /** The files directly inside [folderUrl] (the folder itself excluded). */
    fun list(folderUrl: String): List<RemoteFile> {
        val r = request("PROPFIND", folderUrl, "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:getetag/><d:getlastmodified/><d:resourcetype/></d:prop></d:propfind>".toByteArray(), depth = 1, contentType = "application/xml; charset=utf-8")
        val folderPath = URL(folderUrl).path.trimEnd('/')
        return parse(r.body).mapNotNull { e ->
            val href = e.href ?: return@mapNotNull null
            val path = runCatching { URL(URL(folderUrl), href).path }.getOrDefault(href).trimEnd('/')
            if (path == folderPath) return@mapNotNull null
            val name = URLDecoder.decode(path.substringAfterLast('/'), "UTF-8")
            RemoteFile(name, e.etag?.trim()?.removeSurrounding("\""), parseDate(e.modified), e.isDir)
        }
    }

    fun get(url: String): String = request("GET", url).body
    fun getOrNull(url: String): String? = request("GET", url, allow = setOf(404)).let { if (it.code == 404) null else it.body }

    /** Returns the new etag when the server says it; null otherwise (ask again with [etagOf]). */
    fun put(url: String, text: String, ifMatch: String? = null): String? {
        val h = HashMap<String, String>()
        if (ifMatch != null) h["If-Match"] = "\"$ifMatch\""
        val r = request("PUT", url, text.toByteArray(Charsets.UTF_8), headers = h)
        return r.headers.entries.firstOrNull { it.key.equals("ETag", ignoreCase = true) }?.value?.firstOrNull()?.trim()?.removeSurrounding("\"")
    }

    /** Upload a file, streamed. */
    fun putFile(url: String, file: java.io.File, contentType: String) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "PUT"
        c.connectTimeout = 15_000; c.readTimeout = 120_000
        c.setRequestProperty("Authorization", auth)
        c.setRequestProperty("User-Agent", "readers-recorder")
        c.setRequestProperty("Content-Type", contentType)
        c.doOutput = true
        c.setFixedLengthStreamingMode(file.length())
        try {
            file.inputStream().use { i -> c.outputStream.use { o -> i.copyTo(o, 64 * 1024) } }
            val code = c.responseCode
            if (code == 401) throw WebDavException("wrong username or password")
            if (code >= 400) throw WebDavException("PUT: HTTP $code")
        } finally { c.disconnect() }
    }

    /** Download to a file; false when the server has no such file. */
    fun download(url: String, file: java.io.File): Boolean {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 15_000; c.readTimeout = 120_000
        c.setRequestProperty("Authorization", auth)
        c.setRequestProperty("User-Agent", "readers-recorder")
        try {
            val code = c.responseCode
            if (code == 404) return false
            if (code == 401) throw WebDavException("wrong username or password")
            if (code >= 400) throw WebDavException("GET: HTTP $code")
            val tmp = java.io.File(file.parentFile, file.name + ".part")
            c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            return true
        } finally { c.disconnect() }
    }

    fun etagOf(url: String): String? {
        val r = request("PROPFIND", url, "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:getetag/></d:prop></d:propfind>".toByteArray(), depth = 0, contentType = "application/xml; charset=utf-8")
        return parse(r.body).firstOrNull()?.etag?.trim()?.removeSurrounding("\"")
    }

    fun delete(url: String) { request("DELETE", url, allow = setOf(404)) }
    fun move(from: String, to: String) { request("MOVE", from, headers = mapOf("Destination" to to, "Overwrite" to "T"), allow = setOf(404)) }
    fun mkcol(url: String) { request("MKCOL", url, allow = setOf(405, 301)) }
    fun exists(url: String): Boolean = request("PROPFIND", url, depth = 0, allow = setOf(404)).code != 404

    private class Entry { var href: String? = null; var etag: String? = null; var modified: String? = null; var isDir = false }

    private fun parse(xml: String): List<Entry> {
        val out = ArrayList<Entry>(); var cur: Entry? = null
        val p = Xml.newPullParser(); p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true); p.setInput(xml.reader())
        val path = ArrayList<String>()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val name = p.name.lowercase(); path += name
                    when (name) {
                        "response" -> cur = Entry().also { out += it }
                        "collection" -> if (path.contains("resourcetype")) cur?.isDir = true
                    }
                }
                XmlPullParser.TEXT -> {
                    val t = p.text
                    when (path.lastOrNull()) {
                        "href" -> if (path.getOrNull(path.size - 2) == "response") cur?.href = t.trim()
                        "getetag" -> cur?.etag = t
                        "getlastmodified" -> cur?.modified = t
                    }
                }
                XmlPullParser.END_TAG -> path.removeLastOrNull()
            }
            ev = p.next()
        }
        return out
    }

    private fun parseDate(s: String?): Long = s?.let { runCatching { ZonedDateTime.parse(it.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() } ?: 0L
}
