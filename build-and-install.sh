#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/root/android-sdk}}"
ADB="/root/tools/tv-adb"
GRADLE="${ROOT}/gradlew"

export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"
export PATH="$SDK_ROOT/cmdline-tools/latest/bin:$SDK_ROOT/platform-tools:$PATH"

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
command -v java >/dev/null || fail "java не найден"
command -v javac >/dev/null || fail "javac не найден"
[[ -x "$SDK_ROOT/platform-tools/adb" ]] || fail "Android SDK не найден: $SDK_ROOT"
[[ -d "$SDK_ROOT/platforms/android-34" ]] || fail "platform android-34 не установлен"
[[ -d "$SDK_ROOT/platforms/android-28" ]] || fail "platform android-28 не установлен"
[[ -d "$SDK_ROOT/build-tools/35.0.0" ]] || fail "build-tools 35.0.0 не установлен"
[[ -x "$ADB" ]] || fail "не найден $ADB"
[[ -x "$GRADLE" ]] || fail "не найден Gradle wrapper: $GRADLE"

cd "$ROOT"
"$GRADLE" --no-daemon --stacktrace :app-tv:assembleDebug
APK="$ROOT/app-tv/build/outputs/apk/debug/app-tv-debug.apk"
[[ -s "$APK" ]] || fail "APK не создан: $APK"
printf 'APK: %s (%s bytes)\n' "$APK" "$(stat -c %s "$APK")"
"$ADB" install -r "$APK"
printf 'Установка: PASS\n'
