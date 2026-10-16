# BLE教室混雑度推定システム

## 概要
教室に固定設置したAndroidスマホ（ノード）が周囲のBLEアドバタイズを受信し、集計値をAPIへ送信する。サーバ側で混雑度を推定し、Webページで教室ごとの混雑度を表示する。
規模：1〜2教室の試作（コンテスト出展用）。動くデモと、推定精度の検証データを得ることが目標。

## 構成
```
[Androidノード] --HTTPS/JSON(SIM回線, 60秒ごと)--> [Supabase: Postgres + REST API] <-- [Webページ: HTML + Chart.js]
```

## リポジトリ構成
```
/android   Kotlinアプリ（ノード）
/supabase  Supabase のスキーマ（migrations）・デモデータ・SQL のテスト
/web       フロントエンド（GitHub Pages で公開）
/tools     補助スクリプト
/analysis  校正用スクリプト（Python, Jupyter可）
/docs      設計メモ・実験記録
```

## 1. Androidノード（/android）
- Kotlin / minSdk 26 / targetSdk 最新 / Jetpack Compose（UIは最小限でよい）
- BLEスキャンはForeground Service（foregroundServiceType=connectedDevice）で常時実行
- スキャンは一度開始したら止めずに継続する（30秒間に5回を超えるstartScanはOSにブロックされるため）
- ScanSettings: SCAN_MODE_LOW_LATENCY（常時給電前提）
- Android 8.1以降は画面オフ中にフィルタなしスキャンが停止する。そのため、FLAG_KEEP_SCREEN_ON＋最低輝度の常駐画面で画面を点灯させ続ける
- 権限：Android 12以上はBLUETOOTH_SCAN、11以下はACCESS_FINE_LOCATION。neverForLocationフラグは一部ビーコンを除外する可能性があるので、使う場合は影響を検証する
- 端末再起動時の自動起動（BOOT_COMPLETED）、バッテリー最適化の除外を案内する画面
- 設定画面：ノードID、APIのURL、APIトークン、送信間隔

### 集計ロジック（窓：直近60秒、送信も60秒ごと）
- MACアドレスはその場でソルト付きSHA-256にし、生の値は保存も送信もしない。ソルトは起動ごとに再生成してよい
- 窓内のユニークなハッシュを、最大RSSIで次の閾値別に数える：-60 / -70 / -80 / -90 dBm
- メーカーデータ（Company ID）でカテゴリ別に数える：apple(0x004C) / google(0x00E0) / microsoft(0x0006) / samsung(0x0075) / other / none
- RSSIの統計：中央値、上位/下位の分位点
- デバッグモードON時のみ、端末内CSVに生ログ（時刻、ハッシュ、RSSI、Company ID）を保存する。校正分析用で、送信はしない

### 送信
- Supabase の RPC `POST /rest/v1/rpc/submit_observations`（本文 `{"payload": <観測値 または 配列>}`）、ヘッダ `apikey: <publishable key>` と `x-node-token: <node_token>`（Supabase では Authorization ヘッダが JWT 用のため独自ヘッダにする）
- 送信に失敗したら端末内キュー（Room）に溜め、復帰時に再送する（timestampは観測時刻）
- ペイロード例：
```json
{
  "node_id": "room-3A-01",
  "observed_at": "2026-10-08T10:15:00+09:00",
  "window_sec": 60,
  "counts_by_rssi": {"-60": 4, "-70": 11, "-80": 23, "-90": 31},
  "counts_by_vendor": {"apple": 14, "google": 3, "microsoft": 2, "samsung": 1, "other": 5, "none": 6},
  "rssi_stats": {"median": -78, "p10": -91, "p90": -62},
  "battery": 100, "charging": true, "app_version": "0.1.0"
}
```

## 2. API（/supabase）
- 2026-10-16 に FastAPI + SQLite から Supabase（Postgres + 自動 REST API）へ変更。サーバは置かない
- テーブル：rooms（floor・表示順・active）, nodes（今いる部屋の room_id、トークンはハッシュで保存）, observations, ground_truth（実人数）, calibrations（部屋ごとの係数と段階の閾値）。書き込みは RPC 経由のみ（RLS）
- 部屋：3F休憩室（rest-3f）と 2F休憩室（rest-2f）。ノードはアプリのトグルで部屋を選び、観測値に room_id を添えて送る（2026-10-17〜）
- RPC（詳細は supabase/README.md）
  - submit_observations（ノード用、x-node-token で認証）
  - submit_ground_truth（実人数の手入力。x-admin-token で認証）
  - get_rooms(room_id?)（各部屋の最新の混雑度、ノードの生死）
  - get_room_history(room_id, from_ts, to_ts)（推移データ）
- 管理操作（部屋・ノード登録、トークン発行、校正値登録）は SQL Editor から private.* の関数で行う
- 推定：採用する特徴量（初期値は counts_by_rssi["-80"]）に線形係数を掛けて推定人数を出し、閾値で「空き／やや混雑／混雑」に変換する。式（特徴量・係数・切片）はノードが観測値に estimator として添えて送り（スマホの設定画面の式）、サーバはその式で推定する（2026-10-17〜）。式のない観測と段階の閾値は calibrations テーブルから読む
- 最終受信から3分を超えたノードは offline と表示する

## 3. Webページ（/web）
- ビルド不要の素のHTML/CSS/JS＋Chart.js（CDN）
- 部屋カード：段階表示（色＋文字）、推定人数の目安、最終更新時刻、ノード状態
- 部屋詳細：今日の推移グラフ
- スマホで見やすいレスポンシブ表示
- 実人数を入力する簡易フォーム（校正データ収集用、管理トークン必須）

## 4. 校正（/analysis）
- observationsとground_truthを時刻で突き合わせ、閾値・特徴量ごとに相関と回帰誤差を比較するスクリプト
- 最良の特徴量と係数をcalibrationsへ登録するコマンド

## 開発の進め方
Phaseごとに動作確認してから次へ進む。各Phaseの終わりに、何を確認すべきかを提示すること。
1. Androidアプリでスキャンし、集計値を画面に表示し、CSVに記録する（サーバなし）
2. API＋SQLiteの構築、curlでの動作確認、Androidからの送信と再送
3. Webページ
4. 校正スクリプトと実験手順書（/docs/calibration.md）

## 方針
- プライバシー：個人を識別・追跡できる情報は端末外に出さない。READMEにも明記する
- 秘密情報（トークン類）はリポジトリに含めない（.env / local.properties）
- 仕様が曖昧な点や、Android実機でしか確認できない点は、推測で進めずに質問する
- コメントとドキュメントは日本語
