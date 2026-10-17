#!/bin/bash
# 実車用ビルド。Play の内部テストトラックへ上げて、スマホの Play ストアで更新するところまでやる。
#   scripts/release.sh            … AAB を作るだけ（~/Desktop/AutoTube-<versionCode>.aab）
#   scripts/release.sh --upload   … 作って Play に上げてスマホに入れる
# DHU 用の adb ビルド（assembleDebug）は「.debug」付きの別パッケージなので、Play 版と共存する。
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export VERSION_CODE=$(( $(date +%s) / 60 ))
./gradlew :app:bundleRelease --console=plain -q
OUT=~/Desktop/AutoTube-$VERSION_CODE.aab
cp app/build/outputs/bundle/release/app-release.aab "$OUT"
echo "versionCode=$VERSION_CODE -> $OUT"

if [ "${1:-}" = "--upload" ]; then
  # PLAY_SERVICE_ACCOUNT / PLAY_GCLOUD_ACCOUNT を手元だけに置く（gitignore 済み）。
  [ -f scripts/release.local.env ] && source scripts/release.local.env
  "$(dirname "$0")/play-track.sh" com.autotube.zentaro "$OUT"
fi
