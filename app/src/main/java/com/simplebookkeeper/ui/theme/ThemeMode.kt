package com.simplebookkeeper.ui.theme

import androidx.annotation.StringRes
import com.simplebookkeeper.R

enum class ThemeMode(@StringRes val displayNameResId: Int) {
    LIGHT(R.string.light_theme),
    DARK(R.string.dark_theme),
    SYSTEM(R.string.follow_system);

    companion object {
        fun fromName(name: String?): ThemeMode {
            return entries.find { it.name == name } ?: SYSTEM
        }
    }
}
