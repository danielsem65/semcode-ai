package com.danielsem65.semcodeai.core

import android.content.Context

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("semcode_v2", Context.MODE_PRIVATE)

    // ----- AI provider -----
    var activeProviderId: String
        get() = prefs.getString(ACTIVE_PROVIDER, "zen") ?: "zen"
        set(value) = prefs.edit().putString(ACTIVE_PROVIDER, value).apply()

    var modelOverride: String
        get() = prefs.getString(MODEL_OVERRIDE, "") ?: ""
        set(value) = prefs.edit().putString(MODEL_OVERRIDE, value.trim()).apply()

    fun apiKey(providerId: String): String =
        prefs.getString("key_$providerId", "") ?: ""

    fun setApiKey(providerId: String, key: String) =
        prefs.edit().putString("key_$providerId", key.trim()).apply()

    fun clearApiKey(providerId: String) = prefs.edit().remove("key_$providerId").apply()

    fun hasKeyFor(providerId: String): Boolean = apiKey(providerId).isNotEmpty()

    fun anyKeySaved(): Boolean =
        com.danielsem65.semcodeai.ai.Providers.ALL.any { hasKeyFor(it.id) && !it.isLocal }

    // ----- storage mode -----
    var fullStorage: Boolean
        get() = prefs.getBoolean(FULL_STORAGE, false)
        set(value) = prefs.edit().putBoolean(FULL_STORAGE, value).apply()

    /** When true, destructive tools wait for explicit user approval in chat. */
    var askBeforeChanges: Boolean
        get() = prefs.getBoolean(ASK_BEFORE_CHANGES, false)
        set(value) = prefs.edit().putBoolean(ASK_BEFORE_CHANGES, value).apply()

    // ----- git hub -----
    var githubToken: String
        get() = prefs.getString(GITHUB_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(GITHUB_TOKEN, value.trim()).apply()

    private companion object {
        const val ACTIVE_PROVIDER = "active_provider"
        const val MODEL_OVERRIDE = "model_override"
        const val FULL_STORAGE = "full_storage"
        const val GITHUB_TOKEN = "github_token"
        const val ASK_BEFORE_CHANGES = "ask_before_changes"
    }
}
