#!/usr/bin/env bash
# Build Masal and install it on an Android TV device.
# Configuration comes from .env in the project root (copy .env.dist and fill it in).
# 1. Network ADB to TV_ADB (the TV must have allowed this computer once).
# 2. Optional fallback: Home Assistant's Android TV integration (upload + pm install), when HA_URL is set.
# After installing, the app is compiled on the device (a debug APK otherwise runs from the APK
# and cold-starts slowly; Android compiles debuggable apps only to "quicken") and then opened,
# but only when the TV is awake: it is never woken up. NO_START=1 installs without opening.
set -euo pipefail
cd "$(dirname "$0")/.."

# Read KEY=value lines from .env; empty values are skipped so they do not override the environment.
if [ -f .env ]; then
  while IFS='=' read -r key value; do
    key="${key//[[:space:]]/}"
    [[ -z "$key" || "$key" == \#* ]] && continue
    value="${value%\"}"; value="${value#\"}"
    [ -n "$value" ] && export "$key=$value"
  done < .env
fi

: "${TV_ADB:?Set TV_ADB (ip:port of the TV) in .env, see .env.dist}"
ADB="${ANDROID_HOME:+$ANDROID_HOME/platform-tools/}adb"
APK=app/build/outputs/apk/debug/app-debug.apk
PKG=com.alike.masal
# Opens Masal only on an awake TV; never wakes it.
START="dumpsys power | grep -q mWakefulness=Awake && am start -n $PKG/.MainActivity >/dev/null || true"

if [ "${SKIP_BUILD:-0}" = 1 ]; then
  # Without a build, never install an APK that is older than the app sources (tests do not change the APK).
  if [ -n "$(find app/src/main app/src/debug app/build.gradle.kts -newer "$APK" -type f -print -quit)" ]; then
    echo "APK is older than the sources; build first." >&2
    exit 1
  fi
else
  # A failed build stops the script (set -e); an unchanged APK is simply up to date.
  ./gradlew assembleDebug -q --console=plain
fi

"$ADB" connect "$TV_ADB" >/dev/null 2>&1 || true
if "$ADB" -s "$TV_ADB" get-state 2>/dev/null | grep -q device; then
  "$ADB" -s "$TV_ADB" install -r "$APK"
  "$ADB" -s "$TV_ADB" shell cmd package compile -m speed -f "$PKG" >/dev/null || echo "Compiling failed (the app still works, starts slower)." >&2
  echo "Installed and compiled via ADB."
  if [ "${NO_START:-0}" != 1 ]; then "$ADB" -s "$TV_ADB" shell "$START" && echo "Opened (if the TV was awake)."; fi
  exit 0
fi

if [ -z "${HA_URL:-}" ]; then
  echo "ADB to $TV_ADB is not authorised (allow this computer on the TV) and no Home Assistant fallback is configured." >&2
  exit 1
fi

# Home Assistant fallback. HA_WWW_DIR is the host folder that is /config/www inside the HA container
# (an allowed path for androidtv.upload); the staging folder is removed again afterwards.
echo "ADB not authorised, using Home Assistant."
: "${HA_TOKEN_FILE:?Set HA_TOKEN_FILE in .env}" "${HA_ENTITY:?Set HA_ENTITY in .env}" "${HA_WWW_DIR:?Set HA_WWW_DIR in .env}"
TOKEN=$(cat "$HA_TOKEN_FILE")
STAGE="$HA_WWW_DIR/masal-install"
mkdir -p "$STAGE"
trap 'rm -rf "$STAGE"' EXIT
cp "$APK" "$STAGE/masal.apk"
call() { curl -sf -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d "$2" "$HA_URL/api/services/androidtv/$1" >/dev/null; }
call upload "{\"entity_id\":\"$HA_ENTITY\",\"device_path\":\"/data/local/tmp/masal.apk\",\"local_path\":\"/config/www/masal-install/masal.apk\"}"
call adb_command "{\"entity_id\":\"$HA_ENTITY\",\"command\":\"pm install -r /data/local/tmp/masal.apk && rm /data/local/tmp/masal.apk\"}"
sleep 2
curl -s -H "Authorization: Bearer $TOKEN" "$HA_URL/api/states/$HA_ENTITY" | python3 -c 'import json,sys;print("TV says:", json.load(sys.stdin)["attributes"].get("adb_response"))'
# Separate calls: a command started in the background is ended with the shell, so compile runs to the end (about 10 s).
call adb_command "{\"entity_id\":\"$HA_ENTITY\",\"command\":\"cmd package compile -m speed -f $PKG >/dev/null\"}" || echo "Compiling failed (the app still works, starts slower)." >&2
if [ "${NO_START:-0}" != 1 ]; then call adb_command "{\"entity_id\":\"$HA_ENTITY\",\"command\":\"$START\"}" && echo "Opened (if the TV was awake)."; fi
