#!/usr/bin/env python3
"""Fill every TrackSpeed Android locale from iOS or machine translation.

Existing Android copy is always preserved. Missing strings first reuse an exact
translation from the current iOS string catalog, then fall back to Google
Translate in bounded batches. Format arguments, escaped newlines, and product
names are protected before translation.
"""

from __future__ import annotations

import argparse
import html
import http.cookiejar
import json
import re
import threading
import time
import urllib.parse
import urllib.request
from urllib.error import HTTPError
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parent.parent
RES_ROOT = PROJECT_ROOT / "app/src/main/res"
BASE_PATH = RES_ROOT / "values/strings.xml"
IOS_PATH = (
    PROJECT_ROOT.parent
    / "speed-swift/SprintTimer/SprintTimer/Localizable.xcstrings"
)

LOCALE_DIRS = {
    "ar": "values-ar",
    "bn": "values-bn",
    "cs": "values-cs",
    "da": "values-da",
    "de": "values-de",
    "el": "values-el",
    "es": "values-es",
    "fi": "values-fi",
    "fil": "values-b+fil",
    "fr": "values-fr",
    "hi": "values-hi",
    "hu": "values-hu",
    "in": "values-in",
    "it": "values-it",
    "ja": "values-ja",
    "ko": "values-ko",
    "ms": "values-ms",
    "nb": "values-b+nb",
    "nl": "values-nl",
    "pl": "values-pl",
    "pt-BR": "values-b+pt+BR",
    "pt-PT": "values-b+pt+PT",
    "ro": "values-ro",
    "ru": "values-ru",
    "sv": "values-sv",
    "th": "values-th",
    "tr": "values-tr",
    "uk": "values-uk",
    "vi": "values-vi",
    "zh-Hans": "values-b+zh+Hans",
    "zh-Hant": "values-b+zh+Hant",
}

# Android lint requires every CLDR plural category used by a locale, even when
# an integer counter will rarely (or never) select one of the categories. New
# categories inherit the translated `other` copy as a safe grammatical
# fallback; translators can refine individual forms without changing code.
LOCALE_PLURAL_QUANTITIES = {
    "ar": ("zero", "one", "two", "few", "many", "other"),
    "bn": ("one", "other"),
    "cs": ("one", "few", "many", "other"),
    "da": ("one", "other"),
    "de": ("one", "other"),
    "el": ("one", "other"),
    "es": ("one", "many", "other"),
    "fi": ("one", "other"),
    "fil": ("one", "other"),
    "fr": ("one", "many", "other"),
    "hi": ("one", "other"),
    "hu": ("one", "other"),
    "in": ("other",),
    "it": ("one", "many", "other"),
    "ja": ("other",),
    "ko": ("other",),
    "ms": ("other",),
    "nb": ("one", "other"),
    "nl": ("one", "other"),
    "pl": ("one", "few", "many", "other"),
    "pt-BR": ("one", "many", "other"),
    "pt-PT": ("one", "many", "other"),
    "ro": ("one", "few", "other"),
    "ru": ("one", "few", "many", "other"),
    "sv": ("one", "other"),
    "th": ("other",),
    "tr": ("one", "other"),
    "uk": ("one", "few", "many", "other"),
    "vi": ("other",),
    "zh-Hans": ("other",),
    "zh-Hant": ("other",),
}

PLURAL_QUANTITY_ORDER = ("zero", "one", "two", "few", "many", "other")

GOOGLE_LANGUAGE = {
    "in": "id",
    "zh-Hans": "zh-CN",
    "zh-Hant": "zh-TW",
}

BING_LANGUAGE = {
    "in": "id",
    "pt-BR": "pt",
}

IOS_LANGUAGE = {
    "in": "id",
}

TRANSLATION_SOURCE_OVERRIDES = {
    "onboarding_spin_segment_20_off": "20% price reduction",
    "onboarding_spin_won_title": "You won a 20% price reduction!",
    "onboarding_spin_button_landed": "Claim a 20% price reduction",
}

FORMAT_RE = re.compile(
    r"%(?!%)(?:(?:\d+)\$)?[-#+ 0,(<]*\d*(?:\.\d+)?(?:lld|llu|ld|lu|[sdif@])"
)
FORMAT_PARTS_RE = re.compile(
    r"%(?:(\d+)\$)?[-#+ 0,(<]*\d*(?:\.\d+)?(lld|llu|ld|lu|[sdif@])"
)
UNICODE_ESCAPE_RE = re.compile(r"\\u([0-9a-fA-F]{4})")
SEPARATOR = "<<<TSSEP9F4A7B>>>"
PROTECTED_TERMS = (
    "TrackSpeed",
    "Google Play",
    "Bluetooth",
    "Supabase",
    "iPhone",
    "Android",
)

_google_rate_limited = False
_force_bing = False
_translation_batch_workers = 1
_bing_thread_local = threading.local()


def plain_android_text(value: str) -> str:
    value = value.replace("\\'", "'").replace('\\"', '"')
    value = value.replace("\\n", "\n")
    return UNICODE_ESCAPE_RE.sub(lambda match: chr(int(match.group(1), 16)), value)


def normalize(value: str) -> str:
    value = plain_android_text(value)
    value = value.replace("…", "...").replace("’", "'")
    value = value.replace("“", '"').replace("”", '"')
    value = FORMAT_RE.sub("<arg>", value)
    return re.sub(r"\s+", " ", value).strip().casefold()


def android_format(value: str) -> str:
    value = re.sub(r"%(\d+)\$@", r"%\1$s", value)
    value = value.replace("%@", "%s")
    return re.sub(r"%(?:(\d+)\$)?(?:lld|llu|ld|lu|li)", lambda m: f"%{m.group(1) + '$' if m.group(1) else ''}d", value)


def escape_android_text(value: str) -> str:
    value = value.replace("\\", "\\")
    value = value.replace("\r\n", "\n").replace("\r", "\n")
    value = value.replace("\n", "\\n")
    value = re.sub(r"(?<!\\)'", r"\\'", value)
    value = re.sub(r'(?<!\\)"', r'\\"', value)
    if value.startswith("@") or value.startswith("?"):
        value = "\\" + value
    return value


def parse_resources(path: Path) -> dict[tuple[str, str | None], str]:
    if not path.exists():
        return {}
    result: dict[tuple[str, str | None], str] = {}
    for element in ET.parse(path).getroot():
        name = element.attrib.get("name")
        if not name:
            continue
        if element.tag == "string":
            result[(name, None)] = element.text or ""
        elif element.tag == "plurals":
            for item in element.findall("item"):
                result[(name, item.attrib["quantity"])] = item.text or ""
    return result


def base_entries() -> list[tuple[str, list[tuple[str | None, str]]]]:
    entries: list[tuple[str, list[tuple[str | None, str]]]] = []
    for element in ET.parse(BASE_PATH).getroot():
        name = element.attrib.get("name")
        if not name or element.tag not in {"string", "plurals"}:
            continue
        if element.tag == "string":
            entries.append((name, [(None, element.text or "")]))
        else:
            entries.append(
                (
                    name,
                    [
                        (item.attrib["quantity"], item.text or "")
                        for item in element.findall("item")
                    ],
                )
            )
    return entries


def locale_entries(
    locale: str,
    entries: list[tuple[str, list[tuple[str | None, str]]]],
) -> list[tuple[str, list[tuple[str | None, str]]]]:
    """Add the locale's required CLDR forms to every plural resource."""
    localized: list[tuple[str, list[tuple[str | None, str]]]] = []
    required = LOCALE_PLURAL_QUANTITIES.get(locale, ())
    for name, variants in entries:
        if variants[0][0] is None or not required:
            localized.append((name, variants))
            continue
        sources = dict(variants)
        fallback = sources["other"]
        localized.append(
            (
                name,
                [
                    (quantity, sources.get(quantity, fallback))
                    for quantity in PLURAL_QUANTITY_ORDER
                    if quantity in required
                ],
            )
        )
    return localized


def ios_indices() -> tuple[dict, dict[str, list[str]]]:
    if not IOS_PATH.exists():
        return {}, {}
    strings = json.loads(IOS_PATH.read_text(encoding="utf-8")).get("strings", {})
    index: dict[str, list[str]] = {}
    for key in strings:
        index.setdefault(normalize(key), []).append(key)
    return strings, index


def ios_translation(
    source: str,
    locale: str,
    ios_strings: dict,
    index: dict[str, list[str]],
) -> str | None:
    candidates = index.get(normalize(source), [])
    if len(candidates) != 1:
        return None
    unit = (
        ios_strings[candidates[0]]
        .get("localizations", {})
        .get(IOS_LANGUAGE.get(locale, locale), {})
        .get("stringUnit", {})
    )
    value = unit.get("value")
    return android_format(value) if value else None


def protect(value: str) -> tuple[str, dict[str, str]]:
    replacements: dict[str, str] = {}

    def format_replacement(match: re.Match[str]) -> str:
        token = f"{{ZXQFMT{len(replacements)}QXZ}}"
        replacements[token] = match.group(0)
        return token

    value = FORMAT_RE.sub(format_replacement, value)
    # A literal percentage immediately before a word (for example "20% OFF"
    # or the second half of "%d%% complete") can cause translation engines to
    # retain the English word's first letter. Isolate it as its own token.
    def percent_replacement(match: re.Match[str]) -> str:
        token = f"{{ZXQPCT{len(replacements)}QXZ}}"
        replacements[token] = match.group(0)
        return token

    value = re.sub(r"%+", percent_replacement, value)
    value = value.replace("\\n", "\n")
    for term in PROTECTED_TERMS:
        if term in value:
            token = f"{{ZXQTERM{len(replacements)}QXZ}}"
            replacements[token] = term
            value = value.replace(term, token)
    return value, replacements


def restore(value: str, replacements: dict[str, str]) -> str:
    for token, original in replacements.items():
        value = value.replace(token, original)
    return re.sub(r"[^\S\n]+", " ", value).strip()


def format_matches(value: str) -> list[re.Match[str]]:
    """Return real printf arguments while ignoring the second '%' in '%%'."""
    return [
        match
        for match in FORMAT_RE.finditer(value)
        if match.start() == 0 or value[match.start() - 1] != "%"
    ]


def format_identity(value: str, fallback_position: int) -> tuple[int, str]:
    match = FORMAT_PARTS_RE.fullmatch(value)
    if match is None:
        raise ValueError(f"unsupported format argument: {value}")
    position = int(match.group(1)) if match.group(1) else fallback_position
    value_type = match.group(2)
    if value_type in {"lld", "llu", "ld", "lu"}:
        value_type = "d"
    if value_type == "@":
        value_type = "s"
    return position, value_type


def repair_format_arguments(source: str, translated: str) -> str | None:
    """Keep safe localized reordering and repair dropped printf positions/types."""
    source_matches = format_matches(source)
    if not source_matches:
        return translated
    translated_matches = format_matches(translated)
    if len(source_matches) != len(translated_matches):
        return None

    expected = sorted(
        format_identity(match.group(0), index)
        for index, match in enumerate(source_matches, start=1)
    )
    actual = sorted(
        format_identity(match.group(0), index)
        for index, match in enumerate(translated_matches, start=1)
    )
    all_actual_positional = all(
        FORMAT_PARTS_RE.fullmatch(match.group(0)).group(1) is not None
        for match in translated_matches
    )
    if actual == expected and (len(actual) <= 1 or all_actual_positional):
        return translated

    # Non-positional iOS arguments and stale Android argument types are safe to
    # repair in encounter order. Explicit, valid reordering returned above.
    pieces: list[str] = []
    cursor = 0
    for source_match, translated_match in zip(source_matches, translated_matches):
        pieces.append(translated[cursor : translated_match.start()])
        pieces.append(source_match.group(0))
        cursor = translated_match.end()
    pieces.append(translated[cursor:])
    return "".join(pieces)


def has_literal_percent(value: str) -> bool:
    pieces: list[str] = []
    cursor = 0
    for match in format_matches(value):
        pieces.append(value[cursor : match.start()])
        cursor = match.end()
    pieces.append(value[cursor:])
    return "%" in "".join(pieces)


def google_request(text: str, locale: str) -> str:
    global _google_rate_limited
    if _force_bing or _google_rate_limited:
        return bing_request(text, locale)
    payload = urllib.parse.urlencode(
        {
            "client": "gtx",
            "sl": "en",
            "tl": GOOGLE_LANGUAGE.get(locale, locale),
            "dt": "t",
            "q": text,
        }
    ).encode("utf-8")
    request = urllib.request.Request(
        "https://translate.googleapis.com/translate_a/single",
        data=payload,
        headers={"User-Agent": "TrackSpeed-Localization/1.0"},
    )
    last_error: Exception | None = None
    for attempt in range(5):
        try:
            with urllib.request.urlopen(request, timeout=45) as response:
                data = json.loads(response.read().decode("utf-8"))
            return "".join(part[0] for part in data[0] if part and part[0])
        except HTTPError as error:
            last_error = error
            if error.code == 429:
                _google_rate_limited = True
                break
            time.sleep(min(2**attempt, 8))
        except Exception as error:  # network retry boundary
            last_error = error
            time.sleep(min(2**attempt, 8))
    try:
        return bing_request(text, locale)
    except Exception as bing_error:
        raise RuntimeError(
            f"translation request failed for {locale}: google={last_error}; "
            f"bing={bing_error}"
        ) from bing_error


def bing_request(text: str, locale: str) -> str:
    """Use Bing's public translator page as a rate-limit fallback."""
    headers = {
        "User-Agent": (
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            "AppleWebKit/537.36 Chrome/136.0 Safari/537.36"
        )
    }
    session = getattr(_bing_thread_local, "session", None)
    if session is None:
        cookie_jar = http.cookiejar.CookieJar()
        opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(cookie_jar)
        )
        page = opener.open(
            urllib.request.Request(
                "https://www.bing.com/translator",
                headers=headers,
            ),
            timeout=45,
        ).read().decode("utf-8", "ignore")
        abuse_match = re.search(
            r"params_AbusePreventionHelper\s*=\s*(\[[^;]+\])",
            page,
        )
        ig_match = re.search(r'IG:"([^"]+)"', page)
        iid_match = re.search(r'data-iid="([^"]+)"', page)
        if not abuse_match or not ig_match or not iid_match:
            raise RuntimeError("Bing translator session tokens were not found")
        key, token, _ = json.loads(abuse_match.group(1))
        session = [
            opener,
            str(key),
            token,
            ig_match.group(1),
            iid_match.group(1),
            0,
        ]
        _bing_thread_local.session = session

    opener, key, token, ig, iid, request_index = session
    session[5] = request_index + 1
    payload = urllib.parse.urlencode(
        {
            "fromLang": "en",
            "text": text,
            "to": BING_LANGUAGE.get(locale, locale),
            "token": token,
            "key": key,
        }
    ).encode("utf-8")
    request = urllib.request.Request(
        (
            "https://www.bing.com/ttranslatev3?isVertical=1"
            f"&IG={ig}&IID={iid}.{session[5]}"
        ),
        data=payload,
        headers={
            **headers,
            "Content-Type": "application/x-www-form-urlencoded",
            "Referer": "https://www.bing.com/translator",
        },
    )
    response = json.loads(
        opener.open(request, timeout=60).read().decode("utf-8")
    )
    try:
        return response[0]["translations"][0]["text"]
    except (KeyError, IndexError, TypeError) as error:
        raise RuntimeError(f"unexpected Bing response: {response}") from error


def translate_batch(values: list[str], locale: str) -> list[str]:
    protected: list[str] = []
    replacements: list[dict[str, str]] = []
    for value in values:
        safe, mapping = protect(plain_android_text(value))
        protected.append(safe)
        replacements.append(mapping)

    try:
        translated = google_request(f"\n{SEPARATOR}\n".join(protected), locale)
    except Exception:
        if len(values) == 1:
            raise
        midpoint = len(values) // 2
        return translate_batch(values[:midpoint], locale) + translate_batch(values[midpoint:], locale)
    parts = translated.split(SEPARATOR)
    if len(parts) != len(values):
        if len(values) == 1:
            raise RuntimeError(f"translator changed separator for {locale}")
        midpoint = len(values) // 2
        return translate_batch(values[:midpoint], locale) + translate_batch(values[midpoint:], locale)
    return [restore(value, mapping) for value, mapping in zip(parts, replacements)]


def translated_missing(values: list[str], locale: str) -> list[str]:
    batches: list[list[str]] = []
    current: list[str] = []
    current_length = 0
    for value in values:
        length = len(value) + len(SEPARATOR) + 2
        max_batch_length = 850 if _force_bing or _google_rate_limited else 7000
        if current and current_length + length > max_batch_length:
            batches.append(current)
            current = []
            current_length = 0
        current.append(value)
        current_length += length
    if current:
        batches.append(current)

    if _translation_batch_workers <= 1 or len(batches) <= 1:
        translated: list[str] = []
        for batch in batches:
            translated.extend(translate_batch(batch, locale))
        return translated

    translated_batches: list[list[str] | None] = [None] * len(batches)
    with ThreadPoolExecutor(max_workers=_translation_batch_workers) as executor:
        futures = {
            executor.submit(translate_batch, batch, locale): index
            for index, batch in enumerate(batches)
        }
        for future in as_completed(futures):
            translated_batches[futures[future]] = future.result()
    return [
        value
        for batch in translated_batches
        for value in (batch or [])
    ]


def render_locale(
    locale: str,
    entries: list[tuple[str, list[tuple[str | None, str]]]],
    ios_strings: dict,
    ios_index: dict[str, list[str]],
    refresh_percent: bool,
) -> tuple[str, int, int]:
    output_path = RES_ROOT / LOCALE_DIRS[locale] / "strings.xml"
    existing = parse_resources(output_path)
    values: dict[tuple[str, str | None], str] = dict(existing)
    base_quantities = {
        name: {quantity for quantity, _ in variants}
        for name, variants in entries
        if variants[0][0] is not None
    }
    entries = locale_entries(locale, entries)
    pending_keys: list[tuple[str, str | None]] = []
    pending_source: list[str] = []
    ios_count = 0

    for name, variants in entries:
        for quantity, source in variants:
            key = (name, quantity)
            if (
                quantity is not None
                and quantity not in base_quantities[name]
                and (name, "other") in existing
            ):
                values[key] = existing[(name, "other")]
                continue
            if key in values:
                repaired = repair_format_arguments(source, values[key])
                if repaired is not None and not (
                    refresh_percent and has_literal_percent(source)
                ):
                    values[key] = repaired
                    continue
            translated = None
            if quantity is None:
                translated = ios_translation(source, locale, ios_strings, ios_index)
            repaired = repair_format_arguments(source, translated) if translated else None
            if repaired:
                values[key] = escape_android_text(repaired)
                ios_count += 1
            else:
                pending_keys.append(key)
                pending_source.append(TRANSLATION_SOURCE_OVERRIDES.get(name, source))

    machine_values = translated_missing(pending_source, locale) if pending_source else []
    source_by_key = {
        (name, quantity): source
        for name, variants in entries
        for quantity, source in variants
    }
    for key, translated in zip(pending_keys, machine_values):
        repaired = repair_format_arguments(source_by_key[key], translated)
        if repaired is None:
            retry = translate_batch([source_by_key[key]], locale)[0]
            repaired = repair_format_arguments(source_by_key[key], retry)
        if repaired is None:
            # Preserve runtime safety if a provider repeatedly drops an
            # argument. This is deliberately rare and remains visible to
            # linguistic QA as an English fallback instead of crashing.
            repaired = source_by_key[key]
        values[key] = escape_android_text(repaired)

    lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for name, variants in entries:
        if variants[0][0] is None:
            value = html.escape(values[(name, None)], quote=False)
            source = variants[0][1]
            formatted = (
                ' formatted="false"'
                if not format_matches(source) and has_literal_percent(source)
                else ""
            )
            lines.append(f'    <string name="{name}"{formatted}>{value}</string>')
        else:
            lines.append(f'    <plurals name="{name}">')
            for quantity, _ in variants:
                value = html.escape(values[(name, quantity)], quote=False)
                lines.append(f'        <item quantity="{quantity}">{value}</item>')
            lines.append("    </plurals>")
    lines.extend(["</resources>", ""])
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text("\n".join(lines), encoding="utf-8")
    return locale, ios_count, len(machine_values)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--locale", action="append", choices=sorted(LOCALE_DIRS))
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--batch-workers", type=int, default=1)
    parser.add_argument("--provider", choices=("auto", "bing"), default="auto")
    parser.add_argument(
        "--refresh-percent",
        action="store_true",
        help="retranslate resources containing literal percentage signs",
    )
    args = parser.parse_args()

    global _force_bing, _translation_batch_workers
    _force_bing = args.provider == "bing"
    _translation_batch_workers = max(1, args.batch_workers)

    selected = args.locale or list(LOCALE_DIRS)
    entries = base_entries()
    ios_strings, ios_index = ios_indices()
    print(f"Base: {len(entries)} resources; locales: {len(selected)}")

    with ThreadPoolExecutor(max_workers=max(1, args.workers)) as executor:
        futures = {
            executor.submit(
                render_locale,
                locale,
                entries,
                ios_strings,
                ios_index,
                args.refresh_percent,
            ): locale
            for locale in selected
        }
        for future in as_completed(futures):
            locale, ios_count, machine_count = future.result()
            print(f"{locale}: {ios_count} from iOS, {machine_count} machine translated")


if __name__ == "__main__":
    main()
