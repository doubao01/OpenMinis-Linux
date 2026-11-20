package com.openminis.app.cottranslate

import com.openminis.app.config.Prefs
import com.openminis.app.config.PrefsHandle

/**
 * [T-cot-translate] User preferences for the reasoning translation feature.
 * Backed by the app's standard prefs file (NOT the vscreen/memory namespaces);
 * the toggle is read-only cold (UI gates the feature before any request).
 *
 * Uses the typed [PrefsHandle] accessor rather than raw SharedPreferences: the
 * file name and every key below are registered in [com.openminis.app.config.PrefsRegistry],
 * so a mistyped key fails at the call site instead of silently reading null.
 */
object CotTranslationPrefs {
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_PRIVACY_ACK = "privacy_ack"

    private fun prefs(): PrefsHandle = Prefs.file("cot_translation")

    fun enabled(context: android.content.Context): Boolean = prefs().bool(KEY_ENABLED, false)

    fun setEnabled(context: android.content.Context, value: Boolean) {
        prefs().set(KEY_ENABLED, value)
    }

    fun provider(context: android.content.Context): TranslationProvider =
        providerFromStored(prefs().stringOrNull(KEY_PROVIDER))

    /** Pure readback — "LLM"/"GOOGLE"/"BING" → enum; unknown/missing → BING. */
    internal fun providerFromStored(stored: String?): TranslationProvider =
        TranslationProvider.entries.firstOrNull { it.name == stored }
            ?: TranslationProvider.BING

    fun setProvider(context: android.content.Context, value: TranslationProvider) {
        prefs().set(KEY_PROVIDER, value.name)
    }

    /** Target language tag, or "auto" (follows the app locale at request time). */
    fun targetLanguage(context: android.content.Context): String =
        prefs().string(KEY_LANGUAGE, "auto")

    fun setTargetLanguage(context: android.content.Context, value: String) {
        prefs().set(KEY_LANGUAGE, value.ifBlank { "auto" })
    }

    /** True once the user accepted the privacy notice (content leaves the device). */
    fun privacyAcknowledged(context: android.content.Context): Boolean =
        prefs().bool(KEY_PRIVACY_ACK, false)

    fun setPrivacyAcknowledged(context: android.content.Context) {
        prefs().set(KEY_PRIVACY_ACK, true)
    }
}
