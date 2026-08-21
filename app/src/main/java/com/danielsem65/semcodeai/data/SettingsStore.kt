package com.danielsem65.semcodeai.data

import android.content.Context

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("semcode_settings", Context.MODE_PRIVATE)

    // --- AI providers ---
    var activeProviderId: String
        get() = prefs.getString(KEY_ACTIVE_PROVIDER, "gemini") ?: "gemini"
        set(value) = prefs.edit().putString(KEY_ACTIVE_PROVIDER, value).apply()

    var modelOverride: String
        get() = prefs.getString(KEY_MODEL_OVERRIDE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MODEL_OVERRIDE, value.trim()).apply()

    fun apiKey(providerId: String): String =
        prefs.getString(KEY_PREFIX + providerId, "") ?: ""

    fun setApiKey(providerId: String, key: String) =
        prefs.edit().putString(KEY_PREFIX + providerId, key.trim()).apply()

    fun clearApiKey(providerId: String) = prefs.edit().remove(KEY_PREFIX + providerId).apply()

    fun hasKeyFor(providerId: String): Boolean = apiKey(providerId).isNotEmpty()

    // --- Git credentials ---
    var gitUser: String
        get() = prefs.getString(KEY_GIT_USER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GIT_USER, value.trim()).apply()

    var gitToken: String
        get() = prefs.getString(KEY_GIT_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GIT_TOKEN, value.trim()).apply()

    companion object {
        private const val KEY_ACTIVE_PROVIDER = "active_provider"
        private const val KEY_MODEL_OVERRIDE = "model_override"
        private const val KEY_PREFIX = "api_key_"
        private const val KEY_GIT_USER = "git_username"
        private const val KEY_GIT_TOKEN = "git_token"
    }
}
