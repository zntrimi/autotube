#!/bin/bash
# ワイヤレスデバッグに繋ぎ直す。ポートは接続のたびに変わるので mDNS で探す。
# 前提: スマホの開発者向けオプションで「ワイヤレスデバッグ」がオンで、Mac と同じ Wi-Fi にいる。
for i in 1 2 3 4 5 6; do
  P=$(adb mdns services 2>&1 | grep -oE "[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+:[0-9]+" | head -1)
  if [ -z "$P" ]; then sleep 4; continue; fi
  out=$(adb connect "$P" 2>&1)
  if echo "$out" | grep -qE "^connected|already connected"; then
    echo "connected $P"
    exit 0
  fi
  sleep 4
done
echo "not connected (ワイヤレスデバッグがオンか、同じ Wi-Fi か確認)"
exit 1
