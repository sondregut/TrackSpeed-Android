package com.trackspeed.android.ui.screens.settings

import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.trackspeed.android.R

/**
 * Supported languages with their native display names.
 */
data class LanguageOption(
    val tag: String,
    val nativeName: String
)

val supportedLanguages = listOf(
    LanguageOption("system", ""),
    LanguageOption("en", "English"),
    LanguageOption("ar", "العربية"),
    LanguageOption("bn", "বাংলা"),
    LanguageOption("cs", "Čeština"),
    LanguageOption("da", "Dansk"),
    LanguageOption("de", "Deutsch"),
    LanguageOption("el", "Ελληνικά"),
    LanguageOption("es", "Espa\u00f1ol"),
    LanguageOption("fi", "Suomi"),
    LanguageOption("fil", "Filipino"),
    LanguageOption("fr", "Fran\u00e7ais"),
    LanguageOption("hi", "\u0939\u093f\u0928\u094d\u0926\u0940"),
    LanguageOption("hu", "Magyar"),
    LanguageOption("in", "Bahasa Indonesia"),
    LanguageOption("it", "Italiano"),
    LanguageOption("ja", "\u65e5\u672c\u8a9e"),
    LanguageOption("ko", "\ud55c\uad6d\uc5b4"),
    LanguageOption("ms", "Bahasa Melayu"),
    LanguageOption("nb", "Norsk bokm\u00e5l"),
    LanguageOption("nl", "Nederlands"),
    LanguageOption("pl", "Polski"),
    LanguageOption("pt-BR", "Portugu\u00eas (Brasil)"),
    LanguageOption("pt-PT", "Português (Portugal)"),
    LanguageOption("ro", "Rom\u00e2n\u0103"),
    LanguageOption("ru", "\u0420\u0443\u0441\u0441\u043a\u0438\u0439"),
    LanguageOption("sv", "Svenska"),
    LanguageOption("th", "ไทย"),
    LanguageOption("tr", "Türkçe"),
    LanguageOption("uk", "Українська"),
    LanguageOption("vi", "Tiếng Việt"),
    LanguageOption("zh-Hans", "\u7b80\u4f53\u4e2d\u6587"),
    LanguageOption("zh-Hant", "\u7e41\u9ad4\u4e2d\u6587")
)

/**
 * Get the display name for the current language setting.
 */
@Composable
fun getLanguageDisplayName(tag: String): String {
    if (tag == "system") return stringResource(R.string.settings_language_system_default)
    return supportedLanguages.find { it.tag == tag }?.nativeName
        ?: stringResource(R.string.settings_language_system_default)
}

/**
 * Apply the selected language using AppCompat per-app locale API.
 */
fun applyLanguage(languageTag: String) {
    val localeList = if (languageTag == "system") {
        LocaleListCompat.getEmptyLocaleList()
    } else {
        LocaleListCompat.forLanguageTags(languageTag)
    }
    AppCompatDelegate.setApplicationLocales(localeList)
}

/**
 * Get the current effective language tag from AppCompat.
 */
fun getCurrentLanguageTag(): String {
    val locales = AppCompatDelegate.getApplicationLocales()
    return if (locales.isEmpty) {
        "system"
    } else {
        locales.toLanguageTags()
    }
}

/**
 * Language picker dialog for the Settings screen.
 */
@Composable
fun LanguagePickerDialog(
    currentLanguage: String,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language_title)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 560.dp)) {
                items(supportedLanguages, key = { it.tag }) { lang ->
                    val displayName = if (lang.tag == "system") {
                        stringResource(R.string.settings_language_system_default)
                    } else {
                        lang.nativeName
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onLanguageSelected(lang.tag)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        if (lang.tag == currentLanguage) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
