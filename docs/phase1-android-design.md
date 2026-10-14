# Phase 1 設計メモ：Androidノード（スキャン・集計・JSONログ）

作成日: 2026-10-08
対象端末: Pixel 9a（当面）/ Pixel 7a。Android 12以上を主対象。minSdk 26は維持するが実機検証はこれらのみ

## 目的

サーバなしで、Androidアプリ単体が以下を行えることを確認する。

1. BLEアドバタイズを常時スキャンし続ける
2. 1分ごとの窓で集計値（RSSI閾値別・メーカー別のユニーク端末数、RSSI統計）を作る
3. 集計値を画面に表示する
4. デバッグモード時は窓ごとの集計と端末ごとの要約をJSON Lines形式で端末内に保存する

Phase 2 の送信処理は、この集計値（`WindowSummary`）をそのままペイロードにする前提で設計する。

## CLAUDE.md からの確定事項・補足

| 項目 | 決定 |
|---|---|
| 集計窓 | 時計の分境界に揃えた固定窓。10:15:00〜10:15:59 の観測を `observed_at = 10:15:00+09:00` として扱う |
| 部分的な窓 | スキャンを開始・再開した窓（起動直後、Bluetooth 復帰、失敗からの再試行）と、スキャンが止まっている間の窓は `"partial": true` を付ける。端末内ログには残すが、送信はしない。定期再起動による数ミリ秒の中断は無視する |
| counts_by_rssi | 累積カウント。`"-70"` は「窓内の最大RSSIが -70 dBm 以上の端末数」。`"all"` を追加し RSSI を問わない総数も持つ |
| rssi_stats | 端末ごとの最大RSSIの分布に対する中央値・p10・p90（パケット単位ではない） |
| メーカー判定 | manufacturerSpecificData の Company ID。apple=0x004C, google=0x00E0, microsoft=0x0006, samsung=0x0075, それ以外=other, メーカーデータなし=none。同じ端末で複数観測した場合は「メーカーデータを含む最後の観測」の値（メーカーデータなしの広告で上書きしない） |
| ハッシュ | SHA-256(salt ‖ MAC文字列) の先頭16文字（64bit）。salt は Service 起動ごとに16バイト乱数で再生成。生MACはメモリ上の ScanResult 以外に保持しない |
| デバッグログ | JSON Lines。1行 = 1窓。集計値に加えて `devices` 配列（ハッシュ、最大RSSI、受信回数、Company ID）を含む。保存先はアプリ専用外部ストレージ `Android/data/<pkg>/files/logs/YYYY-MM-DD.jsonl`。送信はしない |
| スキャン降格対策 | OS はフィルタなしのスキャン（空の ScanFilter も含む）を、開始から DeviceConfig の `bluetooth/scan_timeout_millis` が経つと OPPORTUNISTIC に移し、結果を渡さなくなる。AOSP の既定は30分だが、Pixel 9a（Android 16）では 300000（5分）だった（2026-10-08 確認）。そこで3分ごとに stop/start してタイマーを数え直させる。想定外の理由で結果が途絶えたときのため、30秒間結果が1件も来なければ再起動する。どちらも数ミリ秒の中断なので部分的な窓にはしない |
| 画面常時点灯 | 運用の基本。設定で ON/OFF 可能で、ON の間は常駐画面に FLAG_KEEP_SCREEN_ON と最低輝度を適用する。画面オフでもスキャンが続くと実機で確認できた場合だけ OFF 運用を検討する |
| CPU | 常時給電前提のため、Service が動いている間は PARTIAL_WAKE_LOCK を保持する。画面オフ試験で「CPU 休止」と「スキャン停止」を切り分けるためでもある |
| 権限 | スキャンに必須なのは BLUETOOTH_SCAN（Android 12以上、neverForLocation なし）と ACCESS_FINE_LOCATION。Android 12以上では FINE だけを要求すると無視されるため COARSE も同じ要求に含める。POST_NOTIFICATIONS（13以上）は要求するが必須にしない。位置情報サービスが OFF の場合は案内する |

## 構成（app モジュール内のパッケージ）

```
com.crowdlevel.node
├── aggregate/     純Kotlin。Android依存なし。単体テストの対象
│   ├── ScanEvent          1パケット分の入力（hash, rssi, companyId, epochMillis）
│   ├── AddressHasher      ソルト付きSHA-256
│   ├── VendorClassifier   Company ID → カテゴリ
│   ├── WindowAggregator   窓への追加・窓の確定（close）
│   └── WindowSummary      窓の集計結果。JSONシリアライズ可能
├── scan/
│   ├── BleScanService     Foreground Service。スキャン開始・定期再起動・窓の確定を駆動
│   └── BleScannerWrapper  BluetoothLeScanner の薄いラッパ（ScanResult → ScanEvent 変換）
├── log/
│   └── JsonlLogger        WindowSummary を日付ごとの .jsonl ファイルに追記
├── settings/
│   └── NodeSettings       DataStore。nodeId, apiUrl, apiToken, sendIntervalSec, debugLog, keepScreenOn
├── boot/
│   └── BootReceiver       BOOT_COMPLETED で Service を起動
└── ui/
    ├── MainActivity       権限要求、画面点灯制御、画面切替
    ├── DashboardScreen    最新窓の集計値、スキャン状態、パケット受信レート
    └── SettingsScreen     設定項目の編集
```

### データフロー

```
ScanCallback(ScanResult)
  → BleScannerWrapper: MAC をハッシュ化し ScanEvent を生成（生MACはここで捨てる）
  → WindowAggregator.add(event)
  → 1秒ごとの ticker が分境界を検出 → WindowAggregator.close(windowStart) → WindowSummary
  → StateFlow<WindowSummary>（UI）
  → JsonlLogger（デバッグ設定 ON のときのみ）
  → （Phase 2）送信キュー
```

### WindowSummary の JSON（デバッグログ 1 行の例）

```json
{
  "node_id": "room-3A-01",
  "observed_at": "2026-10-08T10:15:00+09:00",
  "window_sec": 60,
  "counts_by_rssi": {"-60": 4, "-70": 11, "-80": 23, "-90": 31, "all": 33},
  "counts_by_vendor": {"apple": 14, "google": 3, "microsoft": 2, "samsung": 1, "other": 5, "none": 8},
  "rssi_stats": {"median": -78, "p10": -91, "p90": -62},
  "adv_count": 1842,
  "battery": 100, "charging": true, "app_version": "0.1.0",
  "devices": [
    {"h": "3f9a1c7e2b8d4e60", "max_rssi": -64, "n": 37, "cid": 76}
  ]
}
```

送信ペイロード（Phase 2）は `devices` と `adv_count` を除いたものにする。

## Ambient への送信（動作確認用、Phase 1.5）

自前 API ができるまでの動作確認として、確定した窓を Ambient（ambidata.io）へ送る。

- エンドポイント: `POST https://ambidata.io/api/v2/channels/{channelId}/dataarray`、本文は `{"writeKey": ..., "data": [{"created": ..., "d<スロット>": 推定人数}, ...]}`（未送信分をまとめて1回で送る）
- 制限: 1チャネルあたり 5秒に1回、1日 3,000 件
- 送るのは推定人数だけ（グラフを見やすくするため。2026-10-08 に d1〜d8 の生の集計値から変更）。生の集計値は端末内ログ（JSON Lines）に残し、校正に使う。ログには送った値の元になった推定人数も `estimated_people` として残す
- 推定人数 = 係数 × 特徴量 + 切片 を四捨五入し、0未満は0にする。特徴量は -60 / -70 / -80 / -90 dBm 以上の端末数か全端末数から選ぶ
- 係数・切片・特徴量は設定画面で変えられる。校正するまでの既定は「-80 dBm 以上の端末数 × 1 + 0」。Phase 2 以降は推定をサーバ側（calibrations テーブル）に移す

### 複数ノードで1つのグラフを共有する

- 全ノードが同じチャネルへ送り、ノードごとに d1〜d8 のどれか1つ（設定画面の「データ欄の番号」、既定は 1）に書く。最大8ノード
- Ambient のチャート設定で d1〜d8 をすべて1つの折れ線グラフに表示しておく。ノードを足して番号を割り当てるだけで線が増え、チャネルやグラフを作り直す必要はない。各系列の名前（凡例）はチャート設定でノードIDにする
- 5秒制限: スロット k は毎分 `2 + 7×(k−1)` 秒目から1秒間だけ送る（d1 = 2秒、d2 = 9秒、… d8 = 51秒）。隣のスロットとは6秒以上空くので、端末の時計のずれが1秒程度までならぶつからない。窓の確定（毎分0秒すぎ）より後なので、その分の点は同じ分のうちに送れる
- 1日3,000件: ノード数 × 1440 ÷ 送信間隔[分] ≦ 3000 にする。間隔1分なら2台、2分なら4台、3分なら6台まで。間隔は設定画面の「Ambient への送信間隔」（1〜10分）で、2分以上のときは時計に揃えた区間（10:00〜10:02 …）内の完全な窓の推定人数の平均を、区間の開始時刻で1点送る
- `created` には常に窓（区間）の開始時刻（日本時間 `yyyy-MM-dd HH:mm:ss.SSS`）を付ける。全ノードが同じ時刻に揃い、直後の送信と再送で時刻の基準も揃う。自前 API の `observed_at` とも一致する
- 送信は1本のコルーチンが行い、自分の時間帯にキューの先頭から最大60点をまとめて送る。送信失敗分はメモリ上のキュー（最大240点）に残し、次の点が入ったときに再送する
- `created` を日本時間で送ると UTC に正しく変換されることを 2026-10-08 に curl で確認済み（11:41:59 JST → 02:41:59Z）
- 4xx（429 を除く）はキー・チャネル ID の誤りとみなして破棄、5xx・429・通信失敗は再送待ちにする。なお Ambient は誤ったライトキーにも 404 を返す
- 部分的な窓（`"partial": true`）は送らない（平均にも入れない）。そのため起動直後と再インストール直後の1分は Ambient に点がない
- チャネル ID とライトキーは `android/local.properties`（git 管理外）の `AMBIENT_CHANNEL_ID` / `AMBIENT_WRITE_KEY` から BuildConfig 経由で既定値として埋め込む。設定画面で上書き可能
- Service を止めると未送信キューは消える。永続キュー（Room）は Phase 2 の自前 API で実装する

## エラー処理

- Bluetooth が OFF: Service は通知に「Bluetooth OFF」を表示し、BluetoothAdapter の状態変化を受けて再開する
- `onScanFailed`: エラーコードを通知と画面に表示し、30秒後に再試行する。SCAN_FAILED_APPLICATION_REGISTRATION_FAILED の場合は再試行間隔を伸ばす
- 権限未許可: Service を起動せず、画面で許可を求める
- ログ書き込み失敗: 画面にエラー表示。集計は継続

## ビルド環境（このMac）

- JDK 17: `~/.jdks/jdk-17.0.20.1+1/Contents/Home`（Android Studio 同梱の JDK 25 は Gradle 8.14 が未対応のため使わない）
- Android SDK: `~/Library/Android/sdk`（platform 36 / build-tools 36.0.0 / platform-tools）
- コマンドライン: `cd android && JAVA_HOME=~/.jdks/jdk-17.0.20.1+1/Contents/Home ./gradlew :app:testDebugUnitTest`

## テスト

- 単体テスト（JVM）: `aggregate/` の全クラス。窓の確定、累積カウント、分位点、メーカー分類、ハッシュの安定性と salt による変化、JSON の形
- 単体テスト（JVM）: Ambient のペイロード（d1〜d8 の割り当て、created、識別に使える値を含まないこと）
- 実機確認（Pixel 9a / Pixel 7a）: Phase 1 終了時の確認項目を参照。`android/scripts/setup-device.sh` でインストール・権限付与・最適化除外・起動をまとめて行える

## Phase 1 終了時の確認項目

1. 人（スマホ）が近づく・離れると `-60`/`-70` のカウントが変わる
2. デバッグ ON で `Android/data/com.crowdlevel.node/files/logs/` に .jsonl が1分ごとに追記される（`adb shell` または Files アプリで確認）
3. Ambient に1分ごとに点が増え、`created` が窓の開始時刻（:00 秒）になっている
4. 画面常時点灯 ON（運用の基本）で1時間以上動かし、パケット数と端末数が落ちない。Bluetooth のログに `regularScanTimeout` が出ない（スキャンタイムアウトの回避の確認）
5. 機内モードを数分 ON にして戻すと、未送信分が再送され Ambient のグラフに欠けがない
6. 参考試験：画面常時点灯 OFF で画面を消し、Ambient の値がどう変わるかを見る（空フィルタで画面オフ停止を避けられるかの確認）
7. 端末を再起動すると自動で Service が立ち上がる。ただし常駐画面は自動では前面に出ないため、画面点灯運用では再起動後にアプリを開く必要がある

## 運用上の注意

- ノード端末は画面ロックを「なし」にする。ロックがあると最初のロック解除まで BOOT_COMPLETED が届かず、Service が起動しない
- 再起動後に常駐画面を自動で前面に出す方法（ホームアプリ化など）は、確認項目 6 と 7 の結果を見て決める
