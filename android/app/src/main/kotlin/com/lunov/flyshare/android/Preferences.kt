package com.lunov.flyshare.android

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Follow the system, or override it. */
enum class ThemeChoice { System, Light, Dark }

/**
 * The four languages the desktop already speaks, plus following the phone.
 *
 * `tag` is what Android knows the language as; `label` is written in that
 * language, because someone looking for their own language will not read the
 * list in a language they do not have.
 */
enum class Language(val tag: String?, val label: String) {
    System(null, "System"),
    English("en", "English"),
    German("de", "Deutsch"),
    Ukrainian("uk", "Українська"),
    Polish("pl", "Polski");

    companion object {
        fun ofTag(tag: String?): Language = entries.firstOrNull { it.tag == tag } ?: System
    }
}

/** What the person chose, kept across restarts. */
class Preferences(context: Context) {

    private val store = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(
        runCatching { ThemeChoice.valueOf(store.getString(THEME, null) ?: "") }
            .getOrDefault(ThemeChoice.System),
    )
    val theme: StateFlow<ThemeChoice> = _theme.asStateFlow()

    private val _language = MutableStateFlow(Language.ofTag(store.getString(LANGUAGE, null)))
    val language: StateFlow<Language> = _language.asStateFlow()

    fun setTheme(choice: ThemeChoice) {
        _theme.value = choice
        store.edit().putString(THEME, choice.name).apply()
    }

    fun setLanguage(choice: Language) {
        _language.value = choice
        store.edit().putString(LANGUAGE, choice.tag).apply()
    }

    /** The locale to render in, or null to leave the system's alone. */
    fun locale(): Locale? = _language.value.tag?.let(Locale::forLanguageTag)

    private companion object {
        const val FILE = "flyshare"
        const val THEME = "theme"
        const val LANGUAGE = "language"
    }
}
