# BLE教室混雑度推定システム

教室に固定設置した Android スマホ（ノード）が周囲の BLE アドバタイズを受信し、集計値だけを API へ送信します。サーバ側で混雑度を推定し、Web ページで教室ごとの混雑度を表示します。

```
[Androidノード] --HTTPS/JSON(60秒ごと)--> [API: FastAPI + SQLite] <-- [Webページ: HTML + Chart.js]
```

## プライバシー方針

- 個人を識別・追跡できる情報は端末の外に出しません
- BLE の MAC アドレスは受信した瞬間にソルト付き SHA-256 でハッシュ化し、生の値は保存も送信もしません
- ソルトはアプリの起動ごとに再生成するため、再起動をまたいで同じ端末を追跡することはできません
- 端末外に送るのは「窓（60秒）内のユニーク端末数を RSSI 閾値別・メーカー別に数えた値」と RSSI の統計値だけです
- デバッグモードで端末内に保存するログにも生の MAC アドレスは含まれません。ログは送信しません

## リポジトリ構成

```
/android   Kotlin アプリ（ノード）
/server    FastAPI（API + 静的フロント配信）
/analysis  校正用スクリプト
/docs      設計メモ・実験記録
```

## Android アプリのビルド

Android Studio で `android/` を開くか、コマンドラインで次を実行します。JDK 17 と Android SDK（API 36）が必要です。

```sh
cd android
export JAVA_HOME=/path/to/jdk-17
./gradlew :app:testDebugUnitTest      # 単体テスト
./gradlew :app:assembleDebug          # デバッグ APK
./gradlew :app:installDebug           # 接続中の端末へインストール
```

`android/local.properties` に `sdk.dir=<Android SDK のパス>` を書きます（git には含めません）。
動作確認用に Ambient へ送る場合は、同じファイルに `AMBIENT_CHANNEL_ID` と `AMBIENT_WRITE_KEY` も書きます。
Ambient には推定人数だけを送ります。複数のノードで1つのチャネル（1つのグラフ）を共有し、ノードごとに設定画面で d1〜d8 の番号を重ならないように割り当てます（詳細は `docs/phase1-android-design.md`）。

端末への導入は `android/scripts/setup-device.sh` でまとめて行えます（インストール、権限付与、バッテリー最適化の除外、起動）。

## API サーバ

`server/README.md` を参照してください。ローカルでは `seed-demo` でダミーデータを作り、http://localhost:8000/ で教室カードと推移グラフを確認できます。

## 開発フェーズ

1. Android アプリ単体でスキャン・集計・JSON ログ記録（`docs/phase1-android-design.md`）
2. API + SQLite、Android からの送信と再送
3. Web ページ
4. 校正スクリプトと実験手順書
