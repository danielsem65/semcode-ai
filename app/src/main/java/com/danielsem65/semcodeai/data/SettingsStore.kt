package com.danielsem65.semcodeai.data

import android.content.Context

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("semcode_settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API, value.trim()).apply()

    companion object {
        private const val KEY_API = "gemini_api_key"
    }
}
