package com.openminis.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.cottranslate.CotTranslationPrefs
import com.openminis.app.cottranslate.TranslationProvider
import com.openminis.app.ui.theme.IosGreen

/**
 * [T-cot-translate] Reasoning-translation preferences: master toggle +
 * provider + target language. The privacy notice stays visible so the send
 * destination is never hidden behind a toggle the user forgot about.
 *
 * Lives outside AppearanceScreen on purpose — that file sits at its
 * architecture size ceiling (see scripts/ci/architecture-baseline.json), so a
 * new section is added as its own composable rather than inlined.
 */
@Composable
internal fun CotTranslationSection() {
    val context = LocalContext.current
    val prefs = remember { CotTranslationPrefs }
    var enabled by remember { mutableStateOf(prefs.enabled(context)) }
    var provider by remember { mutableStateOf(prefs.provider(context)) }
    var language by remember { mutableStateOf(prefs.targetLanguage(context)) }
    // [T-cot-translate-privacy] First enable shows the notice as a dialog.
    // The handle used to stamp `privacy_ack` silently on the first tap, which
    // recorded consent for a notice the user was never shown. Now consent is
    // taken here, before the feature can send anything.
    var showPrivacyDialog by remember { mutableStateOf(false) }
    // [T-cot-translate-settings] The anonymous-endpoint notice applies only to
    // the web routes; the LLM route stays on the user's own provider instance.
    val footer = stringResource(R.string.cot_translate_privacy_notice) +
        if (provider == TranslationProvider.LLM) {
            ""
        } else {
            " " + stringResource(R.string.cot_translate_anonymous_notice)
        }
    SettingsSection(
        header = stringResource(R.string.cot_translate_settings_header),
        footer = footer,
    ) {
        SettingsSwitchRow(
            icon = Icons.Outlined.Translate,
            iconColor = IosGreen,
            title = stringResource(R.string.cot_translate_settings_enabled),
            onCheckedChange = { want ->
                if (want && !prefs.privacyAcknowledged(context)) {
                    // Ask first; the switch only flips once the user accepts.
                    showPrivacyDialog = true
                } else {
                    enabled = want
                    prefs.setEnabled(context, want)
                }
            },
            checked = enabled,
        )
        SettingsValueRow(
            title = stringResource(R.string.cot_translate_settings_provider),
            value = cotProviderDisplayName(provider),
            onClick = {
                // BING → GOOGLE → LLM → BING. The LLM route reuses the chat's
                // configured provider instance (no extra setup); blocks without
                // one degrade to the anonymous Bing route.
                val cycle = TranslationProvider.entries
                // indexOf returns -1 for a value from a newer build; +1 then
                // lands on 0, so the negative case is already covered.
                val next = cycle[(cycle.indexOf(provider) + 1).mod(cycle.size)]
                provider = next
                prefs.setProvider(context, next)
            },
        )
        SettingsValueRow(
            title = stringResource(R.string.cot_translate_settings_language),
            value = cotLanguageDisplayName(language),
            onClick = {
                // Cycle the common targets; "auto" follows the app locale.
                val cycle = listOf("auto", "zh", "en", "ja")
                // Unknown tag (edited prefs, future locale) -> -1 -> "auto".
                val next = cycle[(cycle.indexOf(language) + 1).mod(cycle.size)]
                language = next
                prefs.setTargetLanguage(context, next)
            },
            showDivider = false,
        )
    }

    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            title = { Text(stringResource(R.string.cot_translate_settings_header)) },
            text = {
                Text(
                    stringResource(R.string.cot_translate_privacy_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.setPrivacyAcknowledged(context)
                    prefs.setEnabled(context, true)
                    enabled = true
                    showPrivacyDialog = false
                }) {
                    Text(stringResource(R.string.cot_translate_privacy_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPrivacyDialog = false }) {
                    Text(stringResource(R.string.cot_translate_privacy_decline))
                }
            },
        )
    }
}

/** Display names for the provider row (raw enum names would read "Llm"). */
private fun cotProviderDisplayName(provider: TranslationProvider): String = when (provider) {
    TranslationProvider.BING -> "Bing"
    TranslationProvider.GOOGLE -> "Google"
    TranslationProvider.LLM -> "LLM"
}

/** Friendly target-language labels; an unknown tag reads raw. */
private fun cotLanguageDisplayName(tag: String): String = when (tag) {
    "auto" -> "Auto"
    "zh" -> "Chinese"
    "en" -> "English"
    "ja" -> "Japanese"
    else -> tag
}
