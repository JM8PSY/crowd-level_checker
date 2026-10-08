#!/usr/bin/env bash
# 接続中の Android 端末にデバッグ版をインストールし、ノードとして動かす準備をする。
#
# 使い方（android/ で実行）:
#   ./gradlew :app:assembleDebug
#   scripts/setup-device.sh            # 端末が1台のとき
#   ANDROID_SERIAL=<serial> scripts/setup-device.sh   # 複数台つないでいるとき
#
# 行うこと:
#   - APK をインストールし、実行時権限（付近のデバイス・位置情報・通知）をすべて付与する
#   - バッテリー最適化の対象から外す
#   - Bluetooth と位置情報サービスが ON か確認する（OFF なら警告のみ。端末側で ON にする）
#   - アプリを起動する
set -euo pipefail
cd "$(dirname "$0")/.."

ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
PKG=com.crowdlevel.node
APK=app/build/outputs/apk/debug/app-debug.apk

if [ ! -f "$APK" ]; then
  echo "APK がありません。先に ./gradlew :app:assembleDebug を実行してください" >&2
  exit 1
fi

echo "== 端末"
"$ADB" devices -l | awk 'NR>1 && NF'
echo "model=$("$ADB" shell getprop ro.product.model | tr -d '\r') android=$("$ADB" shell getprop ro.build.version.release | tr -d '\r')"

echo "== インストール（実行時権限をすべて付与）"
"$ADB" install -r -g "$APK"

echo "== バッテリー最適化の対象から外す"
"$ADB" shell dumpsys deviceidle whitelist "+$PKG"

echo "== Bluetooth / 位置情報サービス"
bt=$("$ADB" shell settings get global bluetooth_on | tr -d '\r')
loc=$("$ADB" shell settings get secure location_mode | tr -d '\r')
echo "bluetooth_on=$bt location_mode=$loc"
[ "$bt" = "1" ] || echo "!! Bluetooth が OFF です。端末で ON にしてください"
[ "$loc" != "0" ] || echo "!! 位置情報サービスが OFF です。OFF だとスキャン結果が届きません。端末で ON にしてください"

echo "== 起動"
"$ADB" shell am start -n "$PKG/.ui.MainActivity"

echo
echo "ログを見る: $ADB logcat -s BleScanService AmbientUploader"
