package com.simplebookkeeper.ui.theme

import androidx.annotation.StringRes
import com.simplebookkeeper.R

enum class LanguageMode(@StringRes val displayNameResId: Int, val locale: java.util.Locale?) {
    FOLLOW_SYSTEM(R.string.lang_follow_system, null),
    SIMPLIFIED_CHINESE(R.string.lang_simplified_chinese, java.util.Locale.SIMPLIFIED_CHINESE),
    TRADITIONAL_CHINESE(R.string.lang_traditional_chinese, java.util.Locale.TRADITIONAL_CHINESE),
    ENGLISH(R.string.lang_english, java.util.Locale.ENGLISH);

    companion object {
        fun fromName(name: String?): LanguageMode {
            return entries.find { it.name == name } ?: FOLLOW_SYSTEM
        }
    }
}
