#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK_PATH="${REPO_DIR}/app/build/outputs/apk/debug/app-debug.apk"

if command -v adb >/dev/null 2>&1; then
    ADB_BIN="$(command -v adb)"
else
    SDK_DIR="$(awk -F= '$1 == "sdk.dir" {sub(/^[^=]*=/, ""); print; exit}' "${REPO_DIR}/local.properties")"
    ADB_BIN="${SDK_DIR}/platform-tools/adb"
fi

if [[ ! -x "${ADB_BIN}" ]]; then
    echo "Android Debug Bridge was not found. Check sdk.dir in local.properties."
    exit 1
fi

DEVICES=()
if [[ $# -eq 2 ]]; then
    DEVICES=("$1" "$2")
elif [[ $# -eq 0 ]]; then
    while IFS= read -r serial; do
        [[ -n "${serial}" ]] && DEVICES+=("${serial}")
    done < <("${ADB_BIN}" devices | awk 'NR > 1 && $2 == "device" {print $1}')
else
    echo "Usage: $0 [HOST_SERIAL JOINER_SERIAL]"
    exit 1
fi

if [[ ${#DEVICES[@]} -lt 2 ]]; then
    echo "Two Android devices are required; found ${#DEVICES[@]}."
    "${ADB_BIN}" devices -l
    exit 2
fi

cd "${REPO_DIR}"
./gradlew assembleDebug

for index in 0 1; do
    serial="${DEVICES[$index]}"
    role="HOST"
    [[ ${index} -eq 1 ]] && role="JOINER"

    echo "Installing ${role} build on ${serial}"
    "${ADB_BIN}" -s "${serial}" install -r "${APK_PATH}"

    for permission in \
        android.permission.CAMERA \
        android.permission.RECORD_AUDIO \
        android.permission.BLUETOOTH_SCAN \
        android.permission.BLUETOOTH_CONNECT \
        android.permission.BLUETOOTH_ADVERTISE \
        android.permission.POST_NOTIFICATIONS; do
        "${ADB_BIN}" -s "${serial}" shell pm grant com.trackspeed.android "${permission}" 2>/dev/null || true
    done

    "${ADB_BIN}" -s "${serial}" shell am force-stop com.trackspeed.android
    "${ADB_BIN}" -s "${serial}" shell am start -W -n com.trackspeed.android/.MainActivity
done

echo "TrackSpeed is installed and launched on both devices."
