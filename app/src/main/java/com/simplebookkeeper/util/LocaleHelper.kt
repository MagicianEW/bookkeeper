package com.simplebookkeeper.util

import android.content.Context
import android.content.res.Configuration
import com.simplebookkeeper.ui.theme.LanguageMode
import java.util.Locale

object LocaleHelper {

    fun applyLanguage(context: Context, mode: LanguageMode): Context {
        val locale = mode.locale ?: Locale.getDefault()
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)

        return context.createConfigurationContext(config)
    }

    fun getLocaleFromMode(mode: LanguageMode): Locale {
        return mode.locale ?: Locale.getDefault()
    }
}
