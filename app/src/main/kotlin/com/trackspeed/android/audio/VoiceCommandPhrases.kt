package com.trackspeed.android.audio

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import com.trackspeed.android.R
import java.util.Locale

/**
 * Localized athletics starting commands for TTS voice start.
 * These are domain-specific track & field commands, not general UI translations.
 */
data class VoiceCommands(
    val onYourMarks: String,
    val set: String,
    val go: String,
    val ready: String,
    val decimalWord: String,
    val ttsLocale: Locale
)

object VoiceCommandPhrases {
    /**
     * Get voice commands from the same Android locale catalog used by the UI.
     * Passing "system" follows the device locale instead of forcing English.
     */
    fun forLanguage(context: Context, languageTag: String): VoiceCommands {
        val locale = if (languageTag == "system") {
            context.resources.configuration.locales[0] ?: Locale.getDefault()
        } else {
            Locale.forLanguageTag(languageTag)
        }
        val localizedContext = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply {
                setLocales(LocaleList(locale))
            }
        )
        return VoiceCommands(
            onYourMarks = localizedContext.getString(R.string.voice_command_on_your_marks),
            set = localizedContext.getString(R.string.voice_command_set),
            go = localizedContext.getString(R.string.voice_command_go),
            ready = localizedContext.getString(R.string.voice_command_ready),
            decimalWord = localizedContext.getString(R.string.voice_command_decimal),
            ttsLocale = locale
        )
    }

    /**
     * Get voice commands for the given Locale.
     */
    fun forLocale(context: Context, locale: Locale): VoiceCommands {
        return forLanguage(context, locale.toLanguageTag().replace("_", "-"))
    }
}
