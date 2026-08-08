package com.trackspeed.android.audio

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

    private val commands = mapOf(
        "en" to VoiceCommands("On your marks", "Set", "Go", "Ready", "point", Locale.US),
        "de" to VoiceCommands("Auf die Pl\u00e4tze", "Fertig", "Los", "Bereit", "Komma", Locale.GERMAN),
        "fr" to VoiceCommands("\u00c0 vos marques", "Pr\u00eats", "Partez", "Pr\u00eat", "virgule", Locale.FRENCH),
        "hi" to VoiceCommands("\u0905\u092a\u0928\u0947 \u0928\u093f\u0936\u093e\u0928 \u092a\u0930", "\u0924\u0948\u092f\u093e\u0930", "\u091c\u093e\u0913", "Ready", "\u0926\u0936\u092e\u0932\u0935", Locale.forLanguageTag("hi")),
        "it" to VoiceCommands("Ai vostri posti", "Pronti", "Via", "Pronto", "virgola", Locale.ITALIAN),
        "ja" to VoiceCommands("\u4f4d\u7f6e\u306b\u3064\u3044\u3066", "\u7528\u610f", "\u30c9\u30f3", "\u7528\u610f", "\u70b9", Locale.JAPANESE),
        "ko" to VoiceCommands("\uc81c\uc790\ub9ac\uc5d0", "\ucc28\ub824", "\ucd9c\ubc1c", "\uc900\ube44", "\uc810", Locale.KOREAN),
        "nb" to VoiceCommands("Inta plassene", "Klar", "G\u00e5", "Klar", "komma", Locale.forLanguageTag("nb")),
        "nl" to VoiceCommands("Op uw plaatsen", "Klaar", "Af", "Klaar", "komma", Locale.forLanguageTag("nl")),
        "pt-BR" to VoiceCommands("Aos seus lugares", "Prontos", "Vai", "Pronto", "v\u00edrgula", Locale.forLanguageTag("pt-BR")),
        "ro" to VoiceCommands("Pe locuri", "Fiti gata", "Start", "Gata", "virgul\u0103", Locale.forLanguageTag("ro")),
        "ru" to VoiceCommands("\u041d\u0430 \u0441\u0442\u0430\u0440\u0442", "\u0412\u043d\u0438\u043c\u0430\u043d\u0438\u0435", "\u041c\u0430\u0440\u0448", "\u0412\u043d\u0438\u043c\u0430\u043d\u0438\u0435", "\u0442\u043e\u0447\u043a\u0430", Locale.forLanguageTag("ru")),
        "zh-Hans" to VoiceCommands("\u5404\u5c31\u5404\u4f4d", "\u9884\u5907", "\u8dd1", "\u51c6\u5907", "\u70b9", Locale.SIMPLIFIED_CHINESE),
        "zh-Hant" to VoiceCommands("\u5404\u5c31\u5404\u4f4d", "\u9810\u5099", "\u8dd1", "\u6e96\u5099", "\u9ede", Locale.TRADITIONAL_CHINESE)
    )

    /**
     * Get voice commands for the given language tag.
     * Falls back to English if language is not supported.
     */
    fun forLanguage(languageTag: String): VoiceCommands {
        return commands[languageTag]
            ?: commands[languageTag.substringBefore("-")]
            ?: commands["en"]!!
    }

    /**
     * Get voice commands for the given Locale.
     */
    fun forLocale(locale: Locale): VoiceCommands {
        val tag = locale.toLanguageTag().replace("_", "-")
        return forLanguage(tag)
    }

    val supportedLanguages: Set<String> get() = commands.keys
}
