#!/bin/bash
# Play の内部テストトラックへ AAB をアップロードして即時公開し、スマホの Play ストアで更新まで進める。
# 実車の Android Auto は Play 経由でインストールしたアプリしか一覧に出さないため、実車用はこの経路で入れる。
#
# 使い方: play-track.sh <packageName> <aab> [--no-install]
#
# 必要な環境変数:
#   PLAY_SERVICE_ACCOUNT  Play Console に招待したサービスアカウント（例 play-uploader@<project>.iam.gserviceaccount.com）
#   PLAY_GCLOUD_ACCOUNT   そのサービスアカウントになりすませる（serviceAccountTokenCreator を持つ）gcloud アカウント
#
# 前提（一度だけ）:
#   - gcloud に PLAY_GCLOUD_ACCOUNT でログイン済み
#   - Play Console の「ユーザーと権限」で $SA を招待してある（テストトラックへのリリース）
#   - Play Console の内部テスト「テスター」に自分の Gmail を登録し、オプトインリンクを一度開いてある
set -euo pipefail

PACKAGE="${1:?packageName}"
AAB="${2:?aab}"
SA="${PLAY_SERVICE_ACCOUNT:?PLAY_SERVICE_ACCOUNT を設定してください}"
# 偽装権限を持つ gcloud アカウント。アクティブアカウントが別作業で変わっても動くよう明示する。
GCLOUD_ACCOUNT="${PLAY_GCLOUD_ACCOUNT:?PLAY_GCLOUD_ACCOUNT を設定してください}"
API="https://androidpublisher.googleapis.com/androidpublisher/v3/applications/$PACKAGE"

TOKEN=$(gcloud auth print-access-token --account="$GCLOUD_ACCOUNT" --impersonate-service-account="$SA" \
  --scopes=https://www.googleapis.com/auth/androidpublisher 2>/dev/null)
[ -n "$TOKEN" ] || { echo "gcloud token failed (impersonation of $SA)"; exit 1; }
AUTH="Authorization: Bearer $TOKEN"

json_field() { python3 -c 'import sys,json
try:
    d=json.load(sys.stdin); v=d
    for k in sys.argv[1].split("."): v=v[k]
    print(v)
except Exception: print("")' "$1"; }

EDIT=$(curl -s -X POST -H "$AUTH" -H "Content-Length: 0" "$API/edits" | json_field id)
[ -n "$EDIT" ] || { echo "edits.insert failed (invited the service account in Play Console?)"; exit 1; }

echo "uploading $(basename "$AAB") for $PACKAGE ..."
UPLOAD=$(curl -s -X POST -H "$AUTH" -H "Content-Type: application/octet-stream" --data-binary "@$AAB" \
  "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/$PACKAGE/edits/$EDIT/bundles?uploadType=media")
VERSION=$(echo "$UPLOAD" | json_field versionCode)
[ -n "$VERSION" ] || { echo "bundle upload failed:"; echo "$UPLOAD" | head -c 800; echo; exit 1; }

curl -s -X PUT -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"releases\":[{\"versionCodes\":[\"$VERSION\"],\"status\":\"completed\"}]}" \
  "$API/edits/$EDIT/tracks/internal" >/dev/null
COMMIT=$(curl -s -X POST -H "$AUTH" -H "Content-Length: 0" "$API/edits/$EDIT:commit")
[ -n "$(echo "$COMMIT" | json_field id)" ] || { echo "commit failed:"; echo "$COMMIT" | head -c 800; echo; exit 1; }
echo "published versionCode=$VERSION to internal track"

if [ "${3:-}" = "--no-install" ]; then exit 0; fi
if ! adb devices | tail -n +2 | grep -q "device$"; then
  echo "phone not connected; update from Play Store manually"; exit 0
fi

# Play ストアのアプリページを開いて「更新」「インストール」を押す。反映まで数分かかることがあるので少し粘る。
for attempt in $(seq 1 6); do
  adb shell am start -a android.intent.action.VIEW -d "market://details?id=$PACKAGE" >/dev/null 2>&1
  for i in $(seq 1 8); do
    sleep 2
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || continue
    BOUNDS=$(adb shell cat /sdcard/ui.xml 2>/dev/null | grep -oE 'text="(Install|Update|インストール|更新)"[^>]*bounds="\[[0-9,]+\]\[[0-9,]+\]"' | head -1 | grep -oE '\[[0-9,]+\]\[[0-9,]+\]' || true)
    if [ -n "$BOUNDS" ]; then
      X=$(echo "$BOUNDS" | tr -d '[]' | awk -F, '{print int(($1+$3)/2)}')
      Y=$(echo "$BOUNDS" | tr -d '[]' | awk -F, '{print int(($2+$4)/2)}')
      adb shell input tap "$X" "$Y"
      echo "tapped install/update"
      break 2
    fi
  done
  echo "no install button yet (attempt $attempt); waiting for Play to propagate..."
  sleep 30
done

for i in $(seq 1 40); do
  sleep 3
  CODE=$(adb shell dumpsys package "$PACKAGE" 2>/dev/null | grep -oE 'versionCode=[0-9]+' | head -1 | cut -d= -f2)
  if [ "$CODE" = "$VERSION" ]; then
    echo "installed versionCode=$CODE via Play"; exit 0
  fi
done
echo "install not confirmed yet (installed=$CODE, expected=$VERSION); check the phone"
