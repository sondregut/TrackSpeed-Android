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

import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# Android locale directory mapping
ANDROID_LOCALE_DIRS = {
    "de": "values-de",
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
    text_to_names: dict
) -> str:
    """Generate a translated strings.xml for the given language."""

    # Build translations: android_name -> translated_text
    translations = {}
    matched_count = 0
    unmatched_names = []

    for android_name, english_value in android_strings.items():
        if android_name == 'app_name':
            # Don't translate app name
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
        if not translated:
            for ios_key_candidate, ios_value in ios_strings.items():
                if normalize_for_matching(ios_key_candidate) == normalized_key:
                    locs = ios_value.get('localizations', {})
                    loc = locs.get(target_lang, {})
                    su = loc.get('stringUnit', {})
                    if su.get('value'):
                        translated = su['value']
                        break

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

    print("\nGenerating translated strings.xml files:")
    for lang, dir_name in ANDROID_LOCALE_DIRS.items():
        target_dir = res_dir / dir_name
        target_dir.mkdir(parents=True, exist_ok=True)
        target_path = target_dir / "strings.xml"

        xml_content = generate_translated_strings_xml(
            android_strings, ios_strings, lang, text_to_names
        )

        with open(target_path, 'w', encoding='utf-8') as f:
            f.write(xml_content)

        print(f"  Written: {target_path.relative_to(project_root)}")

    print("\nDone!")


if __name__ == '__main__':
    main()
