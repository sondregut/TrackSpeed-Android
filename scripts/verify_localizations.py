#!/usr/bin/env python3
"""Verify that every advertised Android locale covers every app resource."""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from translate_android_resources import (
    BASE_PATH,
    FORMAT_PARTS_RE,
    LOCALE_DIRS,
    LOCALE_PLURAL_QUANTITIES,
    RES_ROOT,
    format_matches,
)


FORMAT_RE = re.compile(
    r"%(?!%)(?:(?:\d+)\$)?[-#+ 0,(<]*\d*(?:\.\d+)?(?:lld|llu|ld|lu|[sdif@])"
)


def entries(path: Path) -> dict[tuple[str, str | None], str]:
    result: dict[tuple[str, str | None], str] = {}
    root = ET.parse(path).getroot()
    for element in root:
        name = element.attrib.get("name")
        if not name:
            continue
        if element.tag == "string":
            result[(name, None)] = element.text or ""
        elif element.tag == "plurals":
            for item in element.findall("item"):
                result[(name, item.attrib["quantity"])] = item.text or ""
    return result


def format_signature(value: str) -> list[tuple[int, str]]:
    signature: list[tuple[int, str]] = []
    for fallback_position, match in enumerate(format_matches(value), start=1):
        parts = FORMAT_PARTS_RE.fullmatch(match.group(0))
        if parts is None:
            continue
        position = int(parts.group(1)) if parts.group(1) else fallback_position
        value_type = parts.group(2)
        if value_type in {"lld", "llu", "ld", "lu"}:
            value_type = "d"
        if value_type == "@":
            value_type = "s"
        signature.append((position, value_type))
    return sorted(signature)


def main() -> int:
    failures: list[str] = []
    base = entries(BASE_PATH)
    locale_config = ET.parse(RES_ROOT / "xml/locales_config.xml").getroot()
    advertised = {
        element.attrib["{http://schemas.android.com/apk/res/android}name"]
        for element in locale_config.findall("locale")
    }
    expected = {"en", *LOCALE_DIRS}
    if advertised != expected:
        failures.append(
            f"locales_config mismatch: missing={sorted(expected - advertised)}, "
            f"extra={sorted(advertised - expected)}"
        )

    picker_source = (
        Path(__file__).resolve().parent.parent
        / "app/src/main/kotlin/com/trackspeed/android/ui/screens/settings/LanguagePicker.kt"
    ).read_text(encoding="utf-8")
    picker_tags = set(re.findall(r'LanguageOption\("([^"]+)"', picker_source))
    picker_expected = {"system", *expected}
    if picker_tags != picker_expected:
        failures.append(
            f"language picker mismatch: missing={sorted(picker_expected - picker_tags)}, "
            f"extra={sorted(picker_tags - picker_expected)}"
        )

    for locale, directory in LOCALE_DIRS.items():
        path = RES_ROOT / directory / "strings.xml"
        if not path.exists():
            failures.append(f"{locale}: missing {path.relative_to(RES_ROOT)}")
            continue
        localized = entries(path)
        string_keys = {key for key in base if key[1] is None}
        plural_names = {name for name, quantity in base if quantity == "other"}
        expected = string_keys | {
            (name, quantity)
            for name in plural_names
            for quantity in LOCALE_PLURAL_QUANTITIES[locale]
        }
        missing = expected - set(localized)
        extra = set(localized) - expected
        if missing or extra:
            failures.append(
                f"{locale}: missing={len(missing)} extra={len(extra)}"
            )
        for key in expected & set(localized):
            source = base[key] if key in base else base[(key[0], "other")]
            expected_signature = format_signature(source)
            actual_signature = format_signature(localized[key]) if expected_signature else []
            if expected_signature != actual_signature:
                failures.append(
                    f"{locale}:{key[0]}:{key[1] or 'string'} format "
                    f"{actual_signature} != {expected_signature}"
                )
            elif len(expected_signature) > 1:
                actual_matches = format_matches(localized[key])
                if any(
                    FORMAT_PARTS_RE.fullmatch(match.group(0)).group(1) is None
                    for match in actual_matches
                ):
                    failures.append(
                        f"{locale}:{key[0]}:{key[1] or 'string'} uses "
                        "non-positional multiple format arguments"
                    )
            if "ZXQFMT" in localized[key] or "ZXQPCT" in localized[key]:
                failures.append(f"{locale}:{key[0]} contains an unrestored token")

    if failures:
        print("Localization verification failed:")
        for failure in failures:
            print(f"- {failure}")
        return 1

    print(
        f"Localization verification passed: {len(LOCALE_DIRS) + 1} languages, "
        f"{len(base)} base resource variants covered; locale plural forms verified."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
