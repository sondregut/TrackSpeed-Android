#!/usr/bin/env python3
"""Submit the TrackSpeed Android release through the Google Play Developer API.

The script uses Application Default Credentials from `gcloud auth
application-default login` and intentionally defaults to validation only.
Pass `--commit` to send the edit to Google Play review.
"""

from __future__ import annotations

import argparse
import json
import mimetypes
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PACKAGE_NAME = "com.trackspeed.android"
LANGUAGE = "en-US"
API = "https://androidpublisher.googleapis.com/androidpublisher/v3"
UPLOAD_API = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3"
APP_TITLE = "Trackspeed"
SHORT_DESCRIPTION = "Turn your phone into a professional sprint timing system"
FULL_DESCRIPTION = """Turn your phone into a high-precision sprint timing system.

TrackSpeed helps athletes and coaches time sprints without expensive timing gates. Set up your phone at the finish line, choose a start mode, and capture sprint times with automatic motion detection.

Key features:
- Automatic finish-line crossing detection
- Touch release, countdown, voice command, flying start, and in-frame start modes
- Flying sprint timing for 10m, 20m, 30m, and custom distances
- Athlete and session history
- Exportable training results
- Multi-phone timing support for synchronized start and finish gates
- Clean setup built for track and field training

TrackSpeed is designed for sprinters, coaches, performance trainers, and teams that want practical timing tools without carrying dedicated hardware.

Terms of Use: https://mytrackspeed.com/terms
Privacy Policy: https://mytrackspeed.com/privacy"""


def auth_help() -> str:
    scopes = (
        "https://www.googleapis.com/auth/androidpublisher,"
        "https://www.googleapis.com/auth/cloud-platform"
    )
    return (
        "Missing Android Publisher API auth. Run:\n"
        f"  gcloud auth application-default login --scopes={scopes}\n"
        "Use the Play Console owner/admin account, then rerun this script."
    )


def get_token() -> str:
    try:
        result = subprocess.run(
            ["gcloud", "auth", "application-default", "print-access-token"],
            check=True,
            capture_output=True,
            text=True,
        )
    except (FileNotFoundError, subprocess.CalledProcessError) as exc:
        raise SystemExit(auth_help()) from exc

    token = result.stdout.strip()
    if not token:
        raise SystemExit(auth_help())
    return token


def quota_project() -> str | None:
    adc_path = Path.home() / ".config/gcloud/application_default_credentials.json"
    try:
        data = json.loads(adc_path.read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        return None
    return data.get("quota_project_id")


def request_json(
    method: str,
    url: str,
    token: str,
    body: object | None = None,
    content_type: str = "application/json",
    raw_body: bytes | None = None,
) -> dict:
    data: bytes | None
    if raw_body is not None:
        data = raw_body
    elif body is not None:
        data = json.dumps(body).encode("utf-8")
    else:
        data = None

    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/json",
        "Content-Type": content_type,
    }
    project = quota_project()
    if project:
        headers["X-Goog-User-Project"] = project

    request = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers=headers,
    )

    try:
        with urllib.request.urlopen(request, timeout=600) as response:
            payload = response.read()
    except urllib.error.HTTPError as exc:
        payload = exc.read().decode("utf-8", errors="replace")
        try:
            parsed = json.loads(payload)
        except json.JSONDecodeError:
            parsed = {"error": {"message": payload}}
        message = parsed.get("error", {}).get("message", payload)
        if "insufficient authentication scopes" in message.lower():
            message = f"{message}\n\n{auth_help()}"
        raise RuntimeError(f"{method} {url}\nHTTP {exc.code}: {message}") from exc

    if not payload:
        return {}
    return json.loads(payload.decode("utf-8"))


def q(value: str) -> str:
    return urllib.parse.quote(value, safe="")


def edit_url(edit_id: str, suffix: str) -> str:
    return f"{API}/applications/{q(PACKAGE_NAME)}/edits/{q(edit_id)}{suffix}"


def upload_url(edit_id: str, suffix: str) -> str:
    return f"{UPLOAD_API}/applications/{q(PACKAGE_NAME)}/edits/{q(edit_id)}{suffix}"


def create_edit(token: str) -> str:
    response = request_json(
        "POST",
        f"{API}/applications/{q(PACKAGE_NAME)}/edits",
        token,
        body={},
    )
    return response["id"]


def delete_images(token: str, edit_id: str, image_type: str) -> None:
    url = edit_url(edit_id, f"/listings/{q(LANGUAGE)}/{q(image_type)}")
    try:
        request_json("DELETE", url, token)
    except RuntimeError as exc:
        if "HTTP 404" not in str(exc):
            raise


def upload_image(token: str, edit_id: str, image_type: str, path: Path) -> None:
    mime_type = mimetypes.guess_type(path.name)[0] or "image/png"
    url = (
        upload_url(edit_id, f"/listings/{q(LANGUAGE)}/{q(image_type)}")
        + "?uploadType=media"
    )
    request_json(
        "POST",
        url,
        token,
        raw_body=path.read_bytes(),
        content_type=mime_type,
    )


def update_listing(token: str, edit_id: str) -> None:
    body = {
        "language": LANGUAGE,
        "title": APP_TITLE,
        "shortDescription": SHORT_DESCRIPTION,
        "fullDescription": FULL_DESCRIPTION,
    }
    request_json(
        "PUT",
        edit_url(edit_id, f"/listings/{q(LANGUAGE)}"),
        token,
        body=body,
    )


def upload_bundle(token: str, edit_id: str, path: Path) -> str:
    url = upload_url(edit_id, "/bundles") + "?uploadType=media"
    response = request_json(
        "POST",
        url,
        token,
        raw_body=path.read_bytes(),
        content_type="application/octet-stream",
    )
    return str(response["versionCode"])


def upload_deobfuscation_file(
    token: str,
    edit_id: str,
    version_code: str,
    symbol_type: str,
    path: Path,
) -> None:
    url = (
        upload_url(
            edit_id,
            f"/apks/{q(version_code)}/deobfuscationFiles/{q(symbol_type)}",
        )
        + "?uploadType=media"
    )
    request_json(
        "POST",
        url,
        token,
        raw_body=path.read_bytes(),
        content_type="application/octet-stream",
    )


def upload_native_symbols(
    token: str,
    edit_id: str,
    version_code: str,
    path: Path,
) -> None:
    upload_deobfuscation_file(token, edit_id, version_code, "nativeCode", path)


def upload_proguard_mapping(
    token: str,
    edit_id: str,
    version_code: str,
    path: Path,
) -> None:
    upload_deobfuscation_file(token, edit_id, version_code, "proguard", path)


def update_track(
    token: str,
    edit_id: str,
    track: str,
    version_code: str,
    release_status: str,
    release_name: str,
    release_notes: str,
) -> None:
    body = {
        "track": track,
        "releases": [
            {
                "name": release_name,
                "versionCodes": [version_code],
                "status": release_status,
                "releaseNotes": [{"language": LANGUAGE, "text": release_notes}],
            }
        ],
    }
    request_json("PUT", edit_url(edit_id, f"/tracks/{q(track)}"), token, body=body)


def require_files(paths: list[Path]) -> None:
    missing = [str(path) for path in paths if not path.exists()]
    if missing:
        raise SystemExit("Missing required files:\n" + "\n".join(missing))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--commit",
        action="store_true",
        help="Validate, commit, and send the edit to Google Play review.",
    )
    parser.add_argument(
        "--skip-listing-assets",
        action="store_true",
        help="Upload only the release artifact and track update; preserve the live store listing and images.",
    )
    parser.add_argument("--track", default="production")
    parser.add_argument("--release-status", default="completed")
    parser.add_argument("--release-name", default="1.0.5 (6)")
    parser.add_argument(
        "--release-notes",
        default="Fixes welcome screen session actions and updates sprint detection behavior to match the iOS app.",
    )
    parser.add_argument("--version-code", help="Use an existing uploaded version code instead of uploading the AAB.")
    parser.add_argument(
        "--native-symbols",
        default=str(ROOT / "app/build/outputs/native-debug-symbols/release/native-debug-symbols.zip"),
        help="Path to native-debug-symbols.zip for nativeCode deobfuscation upload.",
    )
    parser.add_argument(
        "--mapping",
        default=str(ROOT / "app/build/outputs/mapping/release/mapping.txt"),
        help="Path to R8/ProGuard mapping.txt for proguard deobfuscation upload.",
    )
    parser.add_argument(
        "--upload-native-symbols",
        action="store_true",
        help="Upload native debug symbols for the selected version code.",
    )
    parser.add_argument(
        "--upload-proguard-mapping",
        action="store_true",
        help="Upload R8/ProGuard mapping for the selected version code.",
    )
    parser.add_argument(
        "--symbols-only",
        action="store_true",
        help="Only upload native debug symbols, without changing listing assets or tracks.",
    )
    parser.add_argument(
        "--mapping-only",
        action="store_true",
        help="Only upload R8/ProGuard mapping, without changing listing assets or tracks.",
    )
    parser.add_argument(
        "--aab",
        default=str(ROOT / "app/build/outputs/bundle/release/app-release.aab"),
    )
    args = parser.parse_args()

    feature = ROOT / "play-assets/feature-graphic-1024x500.png"
    icon = ROOT / "app/src/main/res/play_store_icon_512.png"
    phone = [
        ROOT / "play-assets/upload/phone-01-welcome.png",
        ROOT / "play-assets/upload/phone-02-timing.png",
        ROOT / "play-assets/upload/phone-03-goals.png",
        ROOT / "play-assets/upload/phone-04-progress.png",
        ROOT / "play-assets/upload/phone-05-painpoints.png",
    ]
    tablet = [
        ROOT / "play-assets/upload/tablet-01-timing.png",
        ROOT / "play-assets/upload/tablet-02-progress.png",
        ROOT / "play-assets/upload/tablet-03-goals.png",
    ]
    aab = Path(args.aab)
    native_symbols = Path(args.native_symbols)
    mapping = Path(args.mapping)

    if args.symbols_only and not args.version_code:
        raise SystemExit("--symbols-only requires --version-code")
    if args.mapping_only and not args.version_code:
        raise SystemExit("--mapping-only requires --version-code")

    listing_files = [] if args.skip_listing_assets else [icon, feature, *phone, *tablet]
    required_files = listing_files + ([] if args.version_code else [aab])
    if args.symbols_only:
        required_files = [native_symbols]
    elif args.mapping_only:
        required_files = [mapping]
    elif args.upload_native_symbols:
        required_files.append(native_symbols)
    if not args.mapping_only and args.upload_proguard_mapping:
        required_files.append(mapping)
    require_files(required_files)

    token = get_token()
    edit_id = create_edit(token)
    print(f"Created Play edit {edit_id}")

    if args.symbols_only:
        upload_native_symbols(token, edit_id, args.version_code, native_symbols)
        print(f"Uploaded native debug symbols for versionCode {args.version_code}")
        if args.commit:
            response = request_json("POST", edit_url(edit_id, ":commit"), token, body={})
            print(json.dumps({"committed": response}, indent=2))
        else:
            response = request_json("POST", edit_url(edit_id, ":validate"), token, body={})
            print(json.dumps({"validated": response}, indent=2))
            print("Validation only. Rerun with --commit to upload native symbols.")
        return 0

    if args.mapping_only:
        upload_proguard_mapping(token, edit_id, args.version_code, mapping)
        print(f"Uploaded R8/ProGuard mapping for versionCode {args.version_code}")
        if args.commit:
            response = request_json("POST", edit_url(edit_id, ":commit"), token, body={})
            print(json.dumps({"committed": response}, indent=2))
        else:
            response = request_json("POST", edit_url(edit_id, ":validate"), token, body={})
            print(json.dumps({"validated": response}, indent=2))
            print("Validation only. Rerun with --commit to upload mapping.")
        return 0

    if args.skip_listing_assets:
        print("Preserving existing listing text and images")
    else:
        update_listing(token, edit_id)
        print("Updated en-US listing text")

        replacements: list[tuple[str, list[Path]]] = [
            ("icon", [icon]),
            ("featureGraphic", [feature]),
            ("phoneScreenshots", phone),
            ("sevenInchScreenshots", tablet),
            ("tenInchScreenshots", tablet),
        ]
        for image_type, paths in replacements:
            print(f"Replacing {image_type} ({len(paths)} file(s))")
            delete_images(token, edit_id, image_type)
            for path in paths:
                upload_image(token, edit_id, image_type, path)

    version_code = args.version_code or upload_bundle(token, edit_id, aab)
    print(f"Using versionCode {version_code}")

    if args.upload_native_symbols:
        upload_native_symbols(token, edit_id, version_code, native_symbols)
        print(f"Uploaded native debug symbols for versionCode {version_code}")

    if args.upload_proguard_mapping:
        upload_proguard_mapping(token, edit_id, version_code, mapping)
        print(f"Uploaded R8/ProGuard mapping for versionCode {version_code}")

    update_track(
        token,
        edit_id,
        args.track,
        version_code,
        args.release_status,
        args.release_name,
        args.release_notes,
    )
    print(f"Updated {args.track} track as {args.release_status}")

    if args.commit:
        response = request_json("POST", edit_url(edit_id, ":validate"), token, body={})
        print(json.dumps({"validated": response}, indent=2))
        # Never cancel or replace a review that started after the preflight.
        # With no active review, committing the edit submits it for review.
        url = (
            edit_url(edit_id, ":commit")
            + "?changesInReviewBehavior=ERROR_IF_IN_REVIEW"
        )
        response = request_json("POST", url, token, body={})
        print(json.dumps({"committed": response}, indent=2))
    else:
        response = request_json("POST", edit_url(edit_id, ":validate"), token, body={})
        print(json.dumps({"validated": response}, indent=2))
        print("Validation only. Rerun with --commit to send to Google Play review.")

    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(1) from None
