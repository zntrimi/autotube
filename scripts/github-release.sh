#!/bin/bash
# GitHub Releases に release 版の APK を置く。
#   scripts/github-release.sh 0.2.0   … v0.2.0 のタグとリリースを作り、AutoTube-0.2.0.apk を添付する
#
# 署名は app/build.gradle.kts のとおり ~/.android/personal-release.properties の鍵。無ければ止める
# （debug 鍵で署名すると、次のリリースへ上書き更新できなくなるため）。
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="${1:?使い方: scripts/github-release.sh <version>（例 0.2.0）}"
TAG="v$VERSION"
[ -f ~/.android/personal-release.properties ] || { echo "署名鍵の設定（~/.android/personal-release.properties）がありません"; exit 1; }
git diff --quiet && git diff --cached --quiet || { echo "コミットしていない変更があります"; exit 1; }
git fetch -q origin main
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || { echo "main を push してから実行してください"; exit 1; }
! git rev-parse -q --verify "refs/tags/$TAG" >/dev/null || { echo "$TAG はもうあります"; exit 1; }

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export VERSION_CODE=$(( $(date +%s) / 60 ))
export VERSION_NAME="$VERSION"
./gradlew :app:assembleRelease --console=plain -q
APK="build/AutoTube-$VERSION.apk"
mkdir -p build
cp app/build/outputs/apk/release/app-release.apk "$APK"
echo "versionName=$VERSION versionCode=$VERSION_CODE -> $APK"

NOTES=$(cat <<NOTES_EOF
### Install

\`\`\`bash
adb install -r -i com.android.vending AutoTube-$VERSION.apk
\`\`\`

Installing with \`-i com.android.vending\` is required for the app to appear in a real car. See [Installation](https://github.com/zntrimi/autotube#installation).

NOTES_EOF
)
gh release create "$TAG" "$APK" --target main --title "AutoTube $VERSION" --notes "$NOTES" --generate-notes
