#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 3 ]]; then
  echo "Usage: SUPABASE_SERVICE_ROLE_KEY=... $0 <version_code> <version_name> <changelog> [apk_path]"
  echo "Example: SUPABASE_SERVICE_ROLE_KEY=... $0 2 1.1 \"Fix bug & OTA\" app/build/outputs/apk/debug/app-debug.apk"
  exit 1
fi

VERSION_CODE="$1"
VERSION_NAME="$2"
CHANGELOG="$3"
APK_PATH="${4:-app/build/outputs/apk/debug/app-debug.apk}"
SUPABASE_URL="${SUPABASE_URL:-https://yezxyhpczfnkcndfpuht.supabase.co}"
SERVICE_KEY="${SUPABASE_SERVICE_ROLE_KEY:?Set SUPABASE_SERVICE_ROLE_KEY in your environment}"

if [[ ! -f "$APK_PATH" ]]; then
  echo "APK not found: $APK_PATH"
  exit 1
fi

SHA256=$(sha256sum "$APK_PATH" | awk '{print $1}')
OBJ="jajanin-v${VERSION_CODE}.apk"
PUBLIC_URL="${SUPABASE_URL}/storage/v1/object/public/app-updates/${OBJ}"

echo "Uploading $APK_PATH ($SHA256) to $OBJ..."
curl -fsS -X POST "${SUPABASE_URL}/storage/v1/object/app-updates/${OBJ}" \
  -H "apikey: ${SERVICE_KEY}" \
  -H "Authorization: Bearer ${SERVICE_KEY}" \
  -H "Content-Type: application/vnd.android.package-archive" \
  -H "x-upsert: true" \
  --data-binary "@${APK_PATH}" >/dev/null

echo "Registering release v${VERSION_NAME} (code ${VERSION_CODE})..."
PAYLOAD=$(python3 - <<PY
import json
print(json.dumps({
    "version_code": int("${VERSION_CODE}"),
    "version_name": "${VERSION_NAME}",
    "apk_url": "${PUBLIC_URL}",
    "changelog": """${CHANGELOG}""",
    "mandatory": False,
    "sha256": "${SHA256}",
}))
PY
)

curl -fsS -X POST "${SUPABASE_URL}/rest/v1/app_releases" \
  -H "apikey: ${SERVICE_KEY}" \
  -H "Authorization: Bearer ${SERVICE_KEY}" \
  -H "Content-Type: application/json" \
  -H "Prefer: return=representation" \
  -d "$PAYLOAD"

echo
echo "OTA v${VERSION_NAME} published -> ${PUBLIC_URL}"
