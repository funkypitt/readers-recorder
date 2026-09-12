package com.freedomfighter.readersrecorder.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { DARK, LIGHT, SYSTEM }
enum class FontChoice { SERIF, SANS, MONO }
enum class TextSize { SMALL, MEDIUM, LARGE }
enum class Align { LEFT, CENTER }

data class Settings(
    val theme: ThemeMode = ThemeMode.DARK,
    val font: FontChoice = FontChoice.SANS,
    val textSize: TextSize = TextSize.MEDIUM,
    val align: Align = Align.LEFT,
    val haptics: Boolean = true,
    /** WebDAV account: server root (kDrive: https://<id>.connect.kdrive.infomaniak.com), folder, credentials. */
    val server: String = "",
    val folder: String = "Recordings",
    val username: String = "",
    val password: String = "",
    /** Language hint for the transcription: the phone's language by default, "" = detect. */
    val language: String = Prefs.deviceLanguage(),
    /** Kind the next recording starts on: memo, lecture, conversation. */
    val kind: String = "memo",
    /** Fetch the cleaned audio back and play it instead of the original. */
    val fetchCleaned: Boolean = true,
    /** Who transcribes: "phone" (whisper.cpp here, the default), "cloud" (the worker behind the WebDAV folder), "off". */
    val processing: String = "phone",
    /** whisper.cpp model on the phone: base, small, medium. */
    val model: String = "normal",
    /** Also make the normalised listening copy on the phone. */
    val cleanOnPhone: Boolean = true,
    /** Also write the main points of the transcript on the phone. Off until the model is fetched. */
    val summaryOnPhone: Boolean = false
) {
    val configured: Boolean get() = server.isNotBlank()
    val folderUrl: String get() = server.trim().trimEnd('/') + "/" + folder.trim().trim('/').split("/").joinToString("/") { encodeSegment(it) } + "/"
}

fun encodeSegment(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%2F", "/")

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> _settings.value = read() }
    init { sp.registerOnSharedPreferenceChangeListener(listener) }

    private fun read() = Settings(
        theme = enumOr(sp.getString("theme", null), ThemeMode.DARK),
        font = enumOr(sp.getString("font", null), FontChoice.SANS),
        textSize = enumOr(sp.getString("text_size", null), TextSize.MEDIUM),
        align = enumOr(sp.getString("align", null), Align.LEFT),
        haptics = sp.getBoolean("haptics", true),
        server = sp.getString("server", "") ?: "",
        folder = sp.getString("folder", "Recordings") ?: "Recordings",
        username = sp.getString("username", "") ?: "",
        password = sp.getString("password", "") ?: "",
        language = sp.getString("language", deviceLanguage()) ?: deviceLanguage(),
        kind = sp.getString("kind", "memo") ?: "memo",
        fetchCleaned = sp.getBoolean("fetch_cleaned", true),
        // "cloud" only exists in the private build; a stored value from elsewhere falls back to the phone.
        processing = (sp.getString("processing", "phone") ?: "phone").let {
            if (it == "cloud" && !com.freedomfighter.readersrecorder.BuildConfig.PRIVATE) "phone" else it
        },
        model = sp.getString("model", "normal") ?: "normal",
        cleanOnPhone = sp.getBoolean("clean_on_phone", true),
        summaryOnPhone = sp.getBoolean("summary_on_phone", false)
    )
    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    fun setTheme(m: ThemeMode) = sp.edit().putString("theme", m.name).apply()
    fun setFont(f: FontChoice) = sp.edit().putString("font", f.name).apply()
    fun setTextSize(t: TextSize) = sp.edit().putString("text_size", t.name).apply()
    fun setHaptics(v: Boolean) = sp.edit().putBoolean("haptics", v).apply()
    fun setAccount(server: String, folder: String, username: String, password: String) =
        sp.edit().putString("server", normalizeServer(server)).putString("folder", folder.trim().ifBlank { "Recordings" }).putString("username", username.trim()).putString("password", password).apply()
    fun setLanguage(v: String) = sp.edit().putString("language", v.trim()).apply()
    fun setKind(v: String) = sp.edit().putString("kind", v).apply()
    fun setFetchCleaned(v: Boolean) = sp.edit().putBoolean("fetch_cleaned", v).apply()
    fun setProcessing(v: String) = sp.edit().putString("processing", v).apply()
    fun setModel(v: String) = sp.edit().putString("model", v).apply()
    fun setCleanOnPhone(v: Boolean) = sp.edit().putBoolean("clean_on_phone", v).apply()
    fun setSummaryOnPhone(v: Boolean) = sp.edit().putBoolean("summary_on_phone", v).apply()
    fun toggleTheme(systemIsDark: Boolean) {
        val dark = when (_settings.value.theme) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> systemIsDark }
        setTheme(if (dark) ThemeMode.LIGHT else ThemeMode.DARK)
    }

    companion object {
        /** "123456" → the kDrive WebDAV root; a bare host gets https://; anything else is kept. */
        fun normalizeServer(raw: String): String {
            val t = raw.trim().trimEnd('/')
            if (t.isEmpty()) return ""
            if (t.all { it.isDigit() }) return "https://$t.connect.kdrive.infomaniak.com"
            if (!t.contains("://")) return "https://$t"
            return t
        }
        val KINDS = listOf("memo", "lecture", "conversation")
        fun deviceLanguage(): String = java.util.Locale.getDefault().language.takeIf { it.isNotBlank() } ?: "en"
        /** What the language row offers: the phone's language, English, detected. */
        fun languages(): List<String> = listOf(deviceLanguage(), "en", "").distinct()
        fun raw(context: Context): SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }
}
