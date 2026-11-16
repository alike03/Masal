package com.alike.masal.data

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.annotation.StringRes
import java.util.Locale

/**
 * The app's language (spec: docs/specs/language.md). Stored in the app's preferences; "" follows
 * the device and falls back to English for languages Masal has no texts for. Every activity and
 * the application wrap their context with [wrap], so resources come in the chosen language.
 */
object Language {
    /** Stored code to its own name ("" = the device's language). */
    val choices = listOf("" to null, "en" to "English", "de" to "Deutsch", "tr" to "Türkçe")

    private const val Key = "language"

    private fun prefs(context: Context) = context.getSharedPreferences("masal", Context.MODE_PRIVATE)

    fun stored(context: Context): String = prefs(context).getString(Key, "") ?: ""

    /** The context with the chosen language; unchanged when the device's language is used. */
    fun wrap(base: Context): Context {
        val code = stored(base).ifBlank { return base }
        val locale = Locale.forLanguageTag(code)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    /** Stores [code] and redraws [activity] in it (the ViewModel and running data survive). */
    fun set(activity: Activity, code: String) {
        if (code == stored(activity)) return
        prefs(activity).edit().putString(Key, code).commit()
        if (code.isBlank()) Locale.setDefault(activity.applicationContext.resources.configuration.locales[0])
        activity.recreate()
    }

    /** Language for Plex requests (X-Plex-Language), e.g. "de". */
    fun tag(context: Context): String = stored(context).ifBlank { context.resources.configuration.locales[0].language }

    private var app: Context? = null

    fun init(context: Context) { app = context.applicationContext }

    /** Language for Plex requests from code without a context ("en" before [init]). */
    fun tag(): String = app?.let { tag(it) } ?: "en"

    /** A text outside the UI (status and error lines from loading), in the current language. */
    fun text(@StringRes id: Int, vararg args: Any): String {
        val c = app ?: error("Language.init not called")
        return wrap(c).getString(id, *args)
    }
}
