# Supabase（データベース＋API）

ノードの観測値・実人数・校正値を Supabase（Postgres）に保存し、混雑度の推定も Postgres の関数で行います。
サーバ（FastAPI など）は置きません。Web ページ（`/web`）とノードは Supabase の REST API を直接呼びます。

- プロジェクト: `crowd-level-checker`（ref `vxhbjnbsrfopczqxqbhp`、東京リージョン、Free プラン）
- ダッシュボード: https://supabase.com/dashboard/project/vxhbjnbsrfopczqxqbhp
- API の URL: `https://vxhbjnbsrfopczqxqbhp.supabase.co/rest/v1`
- 秘密情報は `supabase/.env`（git 管理外）：DB パスワード、管理トークン

## API

詳しい仕様と例は `docs/api-reference.md` にあります。

どのリクエストにもヘッダ `apikey: <publishable key>` を付けます（publishable key は `web/config.js` にあり、公開してよいキーです）。

| 呼び出し | 認証 | 内容 |
|---|---|---|
| `POST /rpc/submit_observations` 本文 `{"payload": <観測値 または その配列>}` | ヘッダ `x-node-token` | ノードからの送信。同じ `(node_id, observed_at)` は `duplicate` として保存しない（再送してよい）。`"partial": true` は保存しない。返り値 `{"created", "duplicate", "ignored_partial"}` |
| `POST /rpc/submit_ground_truth` 本文 `{"room_id", "people", "observed_at"?, "note"?}` | ヘッダ `x-admin-token` | 実人数。`observed_at` を省くと受信時刻 |
| `GET /rpc/get_rooms`（`?room_id=` で1部屋だけ） | なし | 部屋ごとの段階・推定人数・ノード状態 |
| `GET /rpc/get_room_history?room_id=..&from_ts=..&to_ts=..` | なし | 観測時刻ごとの推定人数と RSSI 閾値別端末数（複数ノードは平均）、実人数。from/to を省くと今日（日本時間） |
| `GET /observations?room_id=eq.room-3A&order=observed_at.desc` など | なし | テーブルを直接読む（rooms / observations / ground_truth / calibrations）。nodes は読めない |

エラーは HTTP 403（トークンが無効、`code: 28000`）と 400（ペイロードの誤り、`code: 22023`）。

推定の仕組み：
- 推定人数 = max(0, 係数 × 特徴量 + 切片)。特徴量は `counts_by_rssi` のキー（`-60`/`-70`/`-80`/`-90`/`all`）
- 校正前の既定は「`-80` の端末数 × 1 + 0」、閾値は やや混雑 15人 / 混雑 30人（仮の値）
- ノードは最終受信から3分を超えると `offline`。部屋の推定は、その部屋にいる `online` のノードの最新値の平均で、`online` がなければ `level: null`

## 管理操作（SQL Editor で実行）

ダッシュボードの SQL Editor に貼って実行します。`private` スキーマの関数は API からは呼べません。

```sql
select private.set_room('rest-1f', '1F休憩室', '1F', 3);  -- 部屋の追加・変更（ID, 表示名, 階, 表示順, 使うか）。アプリの settings/Rooms.kt にも足す
select private.add_node('node-02', 'rest-3f');          -- ノードのトークンが返る（再表示できないので控える）。部屋はアプリのトグルで切り替わる
select private.rotate_node_token('room-3A-01');         -- トークンの再発行
select private.set_admin_token('長いランダム文字列');     -- 実人数の入力に使う管理トークン
select private.set_calibration('room-3A', '-80', 0.8, 0, 15, 30, 'メモ');  -- 特徴量, 係数, 切片, やや混雑, 混雑
```

部屋は 3F休憩室（`rest-3f`）と 2F休憩室（`rest-2f`）。試験用だった `room-1` は使わない部屋（`active = false`）にしてあり、データは残っています。
デモデータ（demo-3A / demo-3B）は `seed_demo.sql` を実行すると作り直せます（2026-10-17 に削除済み）。消す SQL は同じファイルの末尾にあります。

## ノードの監視（Discord）

10分ごとに、ノードごとの直近10分の状況を Discord の Webhook に投稿します（pg_cron のジョブ `node-report` → `private.send_node_report()`）。

```
**ノード状況** 10/17 08:09（直近10分 07:59〜）
🟢 **room-1-02**｜3F休憩室｜電池 76%（充電中）
　窓 8/10（**欠け 2**）｜3420 pkt/分｜端末 15台｜推定 1.7人
　画面 ON｜未送信 0｜無受信による再起動 0回
```

- 窓：直近10分（今の分の1分前まで）に届いた窓の数。部分的な窓（起動直後・部屋の切り替え）や通信できなかった分は「欠け」になる。あとで再送されれば次の報告から数に入る
- 遅れて届いた窓：観測から3分以上たって届いた窓（再送）
- pkt/分・端末：受信パケット数と端末数の平均。pkt/分が急に減ったらスキャンが止まりかけている
- 画面・未送信・無受信による再起動：ノードが観測値に添えて送る `diag`（最新の窓の値）。画面 OFF だとフィルタなしのスキャンが止まる
- 🔴 は最終受信から3分を超えたノード（停止）

```sql
select private.set_discord_webhook('https://discord.com/api/webhooks/...');  -- 設定・変更（URL は supabase/.env にも控える）
select private.set_discord_webhook('');                                      -- 送信を止める
select private.node_report_text();                                           -- 本文だけを確かめる
select private.send_node_report();                                           -- 今すぐ1回送る
select status, start_time from cron.job_run_details order by start_time desc limit 5;   -- 実行の記録
select status_code, error_msg from net._http_response order by created desc limit 5;  -- Discord の応答（204 が成功）
```

## 端末内ログの取り込み

```powershell
adb pull /sdcard/Android/data/com.crowdlevel.node/files/logs/ logs
python tools/import_jsonl.py room-3A-01 <ノードのトークン> logs/2026-10-08.jsonl
```

## スキーマを変えるとき

`supabase/migrations/` に新しい SQL ファイルを足し、`supabase db push` で反映します（Supabase CLI。`supabase login` と `supabase link --project-ref vxhbjnbsrfopczqxqbhp` が済んでいること）。

SQL のテストは手元の Postgres（pgserver）で動きます。

```powershell
uv venv .venv-sql; uv pip install --python .venv-sql\Scripts\python.exe -r supabase/tests/requirements.txt
.venv-sql\Scripts\python.exe -m pytest supabase/tests
```
