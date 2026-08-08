#!/usr/bin/env python3
"""
One-time script to import translations from iOS Localizable.xcstrings
into Android res/values-XX/strings.xml files.

Usage:
    python3 scripts/import_ios_translations.py

Reads:
  - iOS: ../speed-swift/SprintTimer/SprintTimer/Localizable.xcstrings
  - Android English: app/src/main/res/values/strings.xml

Generates:
  - app/src/main/res/values-XX/strings.xml for each target language
"""

import argparse
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# Android locale directory mapping
ANDROID_LOCALE_DIRS = {
    "de": "values-de",
    "es": "values-es",
    "fr": "values-fr",
    "hi": "values-hi",
    "it": "values-it",
    "ja": "values-ja",
    "ko": "values-ko",
    "nb": "values-b+nb",
    "nl": "values-nl",
    "pt-BR": "values-b+pt+BR",
    "ro": "values-ro",
    "ru": "values-ru",
    "zh-Hans": "values-b+zh+Hans",
    "zh-Hant": "values-b+zh+Hant",
}


def localized_value(entry: dict, language: str) -> str | None:
    return (
        entry.get("localizations", {})
        .get(language, {})
        .get("stringUnit", {})
        .get("value")
    )


def parse_existing_locale_strings(res_dir: Path) -> dict[str, dict[str, str]]:
    existing: dict[str, dict[str, str]] = {}
    for language, directory in ANDROID_LOCALE_DIRS.items():
        path = res_dir / directory / "strings.xml"
        existing[language] = parse_android_strings(str(path)) if path.exists() else {}
    return existing


def build_ios_localization_indices(
    ios_strings: dict,
    languages: list[str]
) -> dict[str, dict[str, set[str]]]:
    indices: dict[str, dict[str, set[str]]] = {
        language: {} for language in ["en", *languages]
    }
    for ios_key, entry in ios_strings.items():
        normalized_key = normalize_for_matching(ios_key).casefold()
        indices["en"].setdefault(normalized_key, set()).add(ios_key)
        for language in languages:
            value = localized_value(entry, language)
            if value:
                normalized_value = normalize_for_matching(value).casefold()
                indices[language].setdefault(normalized_value, set()).add(ios_key)
    return indices


def resolve_ios_key(
    android_name: str,
    english_value: str,
    indices: dict[str, dict[str, set[str]]],
    existing_translations: dict[str, dict[str, str]]
) -> str | None:
    scores: dict[str, int] = {}

    normalized_english = normalize_for_matching(
        unescape_android_xml(english_value)
    ).casefold()
    for ios_key in indices["en"].get(normalized_english, set()):
        scores[ios_key] = scores.get(ios_key, 0) + 3

    # Existing reviewed Android translations are a strong semantic bridge to
    # the matching iOS catalog entry even when the English source copy drifted.
    for language, translations in existing_translations.items():
        value = translations.get(android_name)
        if not value or language not in indices:
            continue
        normalized_value = normalize_for_matching(
            unescape_android_xml(value)
        ).casefold()
        for ios_key in indices[language].get(normalized_value, set()):
            scores[ios_key] = scores.get(ios_key, 0) + 1

    if not scores:
        return None
    best_score = max(scores.values())
    winners = [key for key, score in scores.items() if score == best_score]
    # Require either an English match or agreement across two translated
    # locales. A single coincidental short-word match is not safe to import.
    return winners[0] if len(winners) == 1 and best_score >= 2 else None

def convert_ios_format_to_android(text: str) -> str:
    """Convert iOS format specifiers to Android format specifiers."""
    if not text:
        return text

    result = text

    # Convert positional %1$@ -> %1$s, %2$@ -> %2$s etc.
    result = re.sub(r'%(\d+)\$@', r'%\1$s', result)

    # Convert %@ -> %s (non-positional)
    result = result.replace('%@', '%s')

    # Convert %lld -> %d
    result = result.replace('%lld', '%d')
    result = result.replace('%ld', '%d')
    result = result.replace('%llu', '%d')
    result = result.replace('%lu', '%d')

    # Convert %li -> %d
    result = result.replace('%li', '%d')

    # Convert %.0f -> %.0f (same in Android)
    # Convert %f -> %f (same)

    return result


ANDROID_FORMAT_RE = re.compile(
    r'%(?!%)(?:(\d+)\$)?[+ 0#,(<\-]*\d*(?:\.\d+)?[a-zA-Z@]'
)


def align_android_format_specifiers(text: str, english_value: str) -> str:
    """Keep translated copy while preserving Android's argument contracts."""
    source_matches = list(ANDROID_FORMAT_RE.finditer(english_value))
    translated_matches = list(ANDROID_FORMAT_RE.finditer(text))
    if not source_matches or len(source_matches) != len(translated_matches):
        return text

    source_by_position = {
        match.group(1): match.group(0)
        for match in source_matches
        if match.group(1)
    }
    translated_index = 0

    def replacement(match: re.Match[str]) -> str:
        nonlocal translated_index
        position = match.group(1)
        if position and position in source_by_position:
            specifier = source_by_position[position]
        else:
            specifier = source_matches[translated_index].group(0)
        translated_index += 1
        return specifier

    return ANDROID_FORMAT_RE.sub(replacement, text)


def escape_android_xml(text: str) -> str:
    """Escape special characters for Android string resources."""
    if not text:
        return text

    # Replace & first (before other entities)
    text = text.replace('&', '&amp;')
    # Replace < and >
    text = text.replace('<', '&lt;')
    text = text.replace('>', '&gt;')
    # Escape apostrophes
    text = text.replace("'", "\\'")
    # Escape double quotes
    text = text.replace('"', '\\"')
    # Escape @ at start
    if text.startswith('@'):
        text = '\\' + text

    return text


def unescape_android_xml(text: str) -> str:
    """Unescape Android XML to get raw text for matching."""
    text = text.replace("\\'", "'")
    text = text.replace('\\"', '"')
    text = text.replace('&amp;', '&')
    text = text.replace('&lt;', '<')
    text = text.replace('&gt;', '>')
    if text.startswith('\\@'):
        text = text[1:]
    return text


def normalize_for_matching(text: str) -> str:
    """Normalize text for fuzzy matching between iOS and Android strings."""
    text = re.sub(
        r'\\u([0-9a-fA-F]{4})',
        lambda match: chr(int(match.group(1), 16)),
        text,
    )
    text = text.replace('\\n', '\n').replace('…', '...')
    text = text.replace('’', "'").replace('“', '"').replace('”', '"')
    text = re.sub(
        r'%(?:\d+\$)?(?:\+)?(?:\.\d+)?(?:lld|llu|ld|lu|[@sdif])',
        '<arg>',
        text,
    )
    # Strip whitespace
    text = text.strip()
    # Normalize whitespace
    text = re.sub(r'\s+', ' ', text)
    return text


def parse_android_strings(filepath: str) -> dict:
    """Parse Android strings.xml and return {name: english_value} dict."""
    tree = ET.parse(filepath)
    root = tree.getroot()

    strings = {}
    for elem in root:
        if elem.tag == 'string':
            name = elem.get('name')
            # Get text content, handling mixed content
            text = elem.text or ''
            # Also handle tail text from child elements
            for child in elem:
                text += ET.tostring(child, encoding='unicode')
            strings[name] = text
        elif elem.tag == 'plurals':
            # Skip plurals for now - handle separately if needed
            pass

    return strings


def build_english_to_android_map(android_strings: dict) -> dict:
    """Build a mapping from English text -> Android resource name.

    Since iOS uses the English text as the key, we need to match
    Android English values back to their resource names.
    """
    # Map: normalized English text -> list of (android_name, raw_english)
    text_to_names = {}
    for name, english in android_strings.items():
        raw = unescape_android_xml(english)
        # Convert Android format specifiers back to generic for matching
        generic = raw.replace('%s', '%@').replace('%d', '%lld')
        generic = re.sub(r'%(\d+)\$s', r'%\1$@', generic)
        generic = re.sub(r'%(\d+)\$d', r'%\1$lld', generic)

        normalized = normalize_for_matching(raw)
        normalized_generic = normalize_for_matching(generic)

        if normalized not in text_to_names:
            text_to_names[normalized] = []
        text_to_names[normalized].append(name)

        if normalized_generic != normalized:
            if normalized_generic not in text_to_names:
                text_to_names[normalized_generic] = []
            text_to_names[normalized_generic].append(name)

    return text_to_names


def load_ios_translations(xcstrings_path: str) -> dict:
    """Load iOS xcstrings file. Returns the strings dict."""
    with open(xcstrings_path, 'r', encoding='utf-8') as f:
        data = json.load(f)
    return data.get('strings', {})


def generate_translated_strings_xml(
    android_strings: dict,
    ios_strings: dict,
    target_lang: str,
    text_to_names: dict,
    existing_target: dict[str, str] | None = None,
    indices: dict[str, dict[str, set[str]]] | None = None,
    existing_translations: dict[str, dict[str, str]] | None = None,
) -> str:
    """Generate a translated strings.xml for the given language."""

    # Build translations: android_name -> translated_text
    translations = {}
    matched_count = 0
    unmatched_names = []

    existing_target = existing_target or {}
    existing_translations = existing_translations or {}

    for android_name, english_value in android_strings.items():
        if android_name == 'app_name':
            # Don't translate app name
            continue

        if android_name in existing_target:
            translations[android_name] = align_android_format_specifiers(
                unescape_android_xml(existing_target[android_name]),
                unescape_android_xml(english_value),
            )
            matched_count += 1
            continue

        raw_english = unescape_android_xml(english_value)

        # Try direct match with iOS key (which is the English text)
        ios_key = raw_english
        normalized_key = normalize_for_matching(ios_key)

        translated = None

        # Try exact match
        if ios_key in ios_strings:
            locs = ios_strings[ios_key].get('localizations', {})
            loc = locs.get(target_lang, {})
            su = loc.get('stringUnit', {})
            if su.get('value'):
                translated = su['value']

        # Try normalized match
        if not translated and indices:
            normalized_candidates = indices["en"].get(
                normalized_key.casefold(),
                set(),
            )
            for ios_key_candidate in normalized_candidates:
                translated = localized_value(
                    ios_strings[ios_key_candidate],
                    target_lang,
                )
                if translated:
                    break
        elif not translated:
            for ios_key_candidate, ios_value in ios_strings.items():
                if normalize_for_matching(ios_key_candidate) == normalized_key:
                    locs = ios_value.get('localizations', {})
                    loc = locs.get(target_lang, {})
                    su = loc.get('stringUnit', {})
                    if su.get('value'):
                        translated = su['value']
                        break

        if not translated and indices:
            ios_key = resolve_ios_key(
                android_name,
                english_value,
                indices,
                existing_translations,
            )
            if ios_key:
                translated = localized_value(ios_strings[ios_key], target_lang)

        # Try matching with iOS format specifiers
        if not translated:
            # Convert Android format to iOS format for matching
            ios_format = raw_english.replace('%s', '%@').replace('%d', '%lld')
            ios_format = re.sub(r'%(\d+)\$s', r'%\1$@', ios_format)
            ios_format = re.sub(r'%(\d+)\$d', r'%\1$lld', ios_format)

            if ios_format in ios_strings:
                locs = ios_strings[ios_format].get('localizations', {})
                loc = locs.get(target_lang, {})
                su = loc.get('stringUnit', {})
                if su.get('value'):
                    translated = su['value']

        if translated:
            # Convert iOS format specifiers to Android
            translated = convert_ios_format_to_android(translated)
            translated = align_android_format_specifiers(translated, raw_english)
            translations[android_name] = translated
            matched_count += 1
        else:
            unmatched_names.append(android_name)

    # Generate XML
    lines = ['<?xml version="1.0" encoding="utf-8"?>']
    lines.append('<resources>')

    for android_name in android_strings:
        if android_name == 'app_name':
            continue
        if android_name in translations:
            escaped = escape_android_xml(translations[android_name])
            lines.append(f'    <string name="{android_name}">{escaped}</string>')

    lines.append('</resources>')
    lines.append('')

    print(f"  {target_lang}: {matched_count} matched, {len(unmatched_names)} unmatched")
    if unmatched_names and len(unmatched_names) <= 20:
        for name in unmatched_names[:20]:
            print(f"    - {name}: {repr(android_strings[name])[:60]}")

    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--language",
        action="append",
        choices=sorted(ANDROID_LOCALE_DIRS),
        help="Generate only the selected locale. Repeat for multiple locales.",
    )
    args = parser.parse_args()

    # Paths
    script_dir = Path(__file__).parent
    project_root = script_dir.parent
    ios_path = project_root.parent / "speed-swift" / "SprintTimer" / "SprintTimer" / "Localizable.xcstrings"
    android_strings_path = project_root / "app" / "src" / "main" / "res" / "values" / "strings.xml"
    res_dir = project_root / "app" / "src" / "main" / "res"

    if not ios_path.exists():
        print(f"ERROR: iOS xcstrings not found at {ios_path}")
        sys.exit(1)

    if not android_strings_path.exists():
        print(f"ERROR: Android strings.xml not found at {android_strings_path}")
        sys.exit(1)

    print("Loading iOS translations...")
    ios_strings = load_ios_translations(str(ios_path))
    print(f"  Loaded {len(ios_strings)} iOS string keys")

    print("Loading Android English strings...")
    android_strings = parse_android_strings(str(android_strings_path))
    print(f"  Loaded {len(android_strings)} Android string entries")

    print("Building English text -> Android name mapping...")
    text_to_names = build_english_to_android_map(android_strings)
    existing_translations = parse_existing_locale_strings(res_dir)
    indices = build_ios_localization_indices(
        ios_strings,
        list(ANDROID_LOCALE_DIRS),
    )

    print("\nGenerating translated strings.xml files:")
    selected_languages = args.language or list(ANDROID_LOCALE_DIRS)
    for lang in selected_languages:
        dir_name = ANDROID_LOCALE_DIRS[lang]
        target_dir = res_dir / dir_name
        target_dir.mkdir(parents=True, exist_ok=True)
        target_path = target_dir / "strings.xml"

        xml_content = generate_translated_strings_xml(
            android_strings,
            ios_strings,
            lang,
            text_to_names,
            existing_target=existing_translations.get(lang),
            indices=indices,
            existing_translations=existing_translations,
        )

        with open(target_path, 'w', encoding='utf-8') as f:
            f.write(xml_content)

        print(f"  Written: {target_path.relative_to(project_root)}")

    print("\nDone!")


if __name__ == '__main__':
    main()
