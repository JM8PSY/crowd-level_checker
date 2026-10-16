# API リファレンス

BLE教室混雑度推定システムの API です。Supabase（Postgres + PostgREST）の REST API をそのまま使います。
2026-10-16 時点の内容で、例はすべて実際に呼んで確かめています。

- [共通事項](#共通事項)
- [混雑度を読む](#混雑度を読む)：`get_rooms` / `get_room_history`
- [テーブルを直接読む](#テーブルを直接読む)：`rooms` / `observations` / `ground_truth` / `calibrations`
- [書き込む](#書き込む)：`submit_observations`（ノード）/ `submit_ground_truth`（管理）
- [エラー](#エラー)
- [管理操作](#管理操作sql-editor)（API ではなく SQL Editor）

## 共通事項

### ベース URL

```
https://vxhbjnbsrfopczqxqbhp.supabase.co/rest/v1
```

### ヘッダ

| ヘッダ | 必須 | 内容 |
|---|---|---|
| `apikey` | すべてのリクエスト | publishable key（`web/config.js` の `supabaseKey`）。公開してよいキーで、読み取りと RPC の呼び出しだけができる |
| `Content-Type: application/json` | POST | |
| `x-node-token` | `submit_observations` | ノードのトークン |
| `x-admin-token` | `submit_ground_truth` | 管理トークン |

`Authorization` ヘッダは使いません（Supabase では JWT 用のため）。旧形式の anon key（`eyJ` で始まる JWT）を使う場合だけ、`Authorization: Bearer <anon key>` も付けます。

### 時刻

- 入力は ISO 8601 のタイムゾーン付き（例 `2026-10-16T23:35:00+09:00`）。タイムゾーンを省くと UTC とみなされるので、必ず付ける
- **出力は UTC**（例 `2026-10-16T14:35:00+00:00`）。日本時間で表示するときはクライアントで変換する
- 観測時刻 `observed_at` は1分の窓の開始時刻（10:15:00〜10:15:59 の観測は 10:15:00）

### 公開範囲とプライバシー

- 読めるのは集計値（窓内の端末数、RSSI の統計など）だけです。個人を識別・追跡できる値（MAC アドレスやそのハッシュ）は、端末の外に出していないので API にもありません
- `nodes`（トークンのハッシュを含む）は読めません。ノードの状態は `get_rooms` が返します
- テーブルへの直接の書き込みはできません。書き込みは下の RPC だけです

## 混雑度を読む

### `get_rooms` — 各教室の最新の混雑度

```
GET /rpc/get_rooms
```

引数なし。POST（本文 `{}`）でも呼べます。

```sh
curl "https://vxhbjnbsrfopczqxqbhp.supabase.co/rest/v1/rpc/get_rooms" -H "apikey: <publishable key>"
```

応答（200）:

```json
{
  "now": "2026-10-16T14:36:30.123456+00:00",
  "rooms": [
    {
      "id": "room-1",
      "name": "教室1（試験）",
      "level": "empty",
      "estimated_people": 12.0,
      "updated_at": "2026-10-16T14:36:00+00:00",
      "calibration": {
        "feature": "-80", "coef": 1, "intercept": 0,
        "threshold_moderate": 15, "threshold_crowded": 30, "calibrated": false
      },
      "nodes": [
        {
          "id": "room-1-02", "status": "online",
          "last_seen_at": "2026-10-16T14:37:00.897395+00:00",
          "last_observed_at": "2026-10-16T14:36:00+00:00",
          "battery": 13, "charging": true, "app_version": "0.1.0"
        }
      ]
    }
  ]
}
```

| フィールド | 型 | 内容 |
|---|---|---|
| `now` | 時刻 | サーバの現在時刻 |
| `rooms[].id` / `name` | 文字列 | 教室 ID と表示名。`id` 順 |
| `rooms[].level` | `"empty"` / `"moderate"` / `"crowded"` / `null` | 空き / やや混雑 / 混雑。稼働中のノードがなければ `null`（不明） |
| `rooms[].estimated_people` | 数値 / `null` | 推定人数（小数1桁）。稼働中のノードの最新値から計算し、複数あれば平均 |
| `rooms[].updated_at` | 時刻 / `null` | その教室で最も新しい観測の `observed_at` |
| `rooms[].calibration` | オブジェクト | 推定に使っている式と閾値（下の「推定の仕組み」）。`calibrated: false` は校正前の既定値 |
| `rooms[].nodes[].status` | `"online"` / `"offline"` | 最終受信から3分以内なら `online` |
| `rooms[].nodes[].last_seen_at` | 時刻 / `null` | 最後に送信を受けた時刻（再送分を受けたときも更新） |
| `rooms[].nodes[].last_observed_at` | 時刻 / `null` | そのノードの最新の観測の `observed_at` |
| `rooms[].nodes[].battery` / `charging` / `app_version` | | 最後の送信に含まれていた端末の状態 |

#### 推定の仕組み

```
推定人数 = max(0, coef × counts_by_rssi[feature] + intercept)
level   = 推定人数 ≥ threshold_crowded なら crowded、≥ threshold_moderate なら moderate、それ以外は empty
```

`feature` は `counts_by_rssi` のキー（`-60` / `-70` / `-80` / `-90` / `all`）。教室ごとに `calibrations` の最新の行を使い、行がなければ既定値（`-80`、係数1、切片0、閾値 15 / 30）を使います。

### `get_room_history` — 推移

```
GET /rpc/get_room_history?room_id=<教室ID>&from_ts=<開始>&to_ts=<終了>
```

| 引数 | 必須 | 内容 |
|---|---|---|
| `room_id` | ○ | 教室 ID |
| `from_ts` | | 開始時刻（この時刻を含む）。省くと今日（日本時間）の 0:00 |
| `to_ts` | | 終了時刻（この時刻を含まない）。省くと明日（日本時間）の 0:00 |

URL に書くときは `+` を `%2B` にします。POST で本文に JSON（`{"room_id": "room-1", "from_ts": "...", "to_ts": "..."}`）を渡しても同じです。

```sh
curl "https://vxhbjnbsrfopczqxqbhp.supabase.co/rest/v1/rpc/get_room_history?room_id=room-1&from_ts=2026-10-16T23:35:00%2B09:00&to_ts=2026-10-16T23:37:00%2B09:00" \
  -H "apikey: <publishable key>"
```

応答（200）:

```json
{
  "room_id": "room-1",
  "from": "2026-10-16T14:35:00+00:00",
  "to": "2026-10-16T14:37:00+00:00",
  "feature": "-80",
  "calibrated": false,
  "threshold_moderate": 15,
  "threshold_crowded": 30,
  "points": [
    {
      "observed_at": "2026-10-16T14:35:00+00:00",
      "estimated_people": 10.0,
      "level": "empty",
      "counts_by_rssi": {"-60": 2.5, "-70": 8.0, "-80": 10.0, "-90": 12.5, "all": 12.5},
      "nodes": 2
    }
  ],
  "ground_truth": [
    {"observed_at": "2026-10-16T14:05:00+00:00", "people": 18, "note": "授業中"}
  ]
}
```

| フィールド | 内容 |
|---|---|
| `points[]` | 観測時刻ごとに1点、古い順。同じ時刻に複数ノードの観測があれば `counts_by_rssi` は平均（小数1桁）、`nodes` はその数 |
| `points[].estimated_people` / `level` | その時点の平均値に、**現在の**推定式と閾値を当てはめたもの（校正値を変えると過去の点も変わる） |
| `ground_truth[]` | 期間内に入力された実人数、古い順 |

存在しない `room_id` でもエラーにはならず、`points` と `ground_truth` が空で返ります。

## テーブルを直接読む

PostgREST の書式で、テーブルを直接読めます。どれも `GET` で、`apikey` ヘッダが必要です。

| テーブル | 内容 |
|---|---|
| `rooms` | `id`, `name` |
| `observations` | ノードの観測値（1ノード・1分につき1行）。下の「観測値の形」 |
| `ground_truth` | 実人数。`id`, `room_id`, `observed_at`, `people`, `note`, `created_at` |
| `calibrations` | 推定式と閾値の履歴。`id`, `room_id`, `feature`, `coef`, `intercept`, `threshold_moderate`, `threshold_crowded`, `note`, `created_at` |

`observations` の列: `id`, `node_id`, `room_id`（受信時点の所属教室）, `observed_at`, `received_at`, `window_sec`, `counts_by_rssi`, `counts_by_vendor`, `rssi_stats`, `adv_count`, `battery`, `charging`, `app_version`

### よく使う書き方

```sh
B=https://vxhbjnbsrfopczqxqbhp.supabase.co/rest/v1
K="apikey: <publishable key>"

# 教室の最新60件（新しい順）
curl "$B/observations?room_id=eq.room-1&order=observed_at.desc&limit=60" -H "$K"

# 期間を指定し、必要な列だけ。JSON の中の値は ->> で取り出し、キーは "" で囲む（-g は curl に [] {} を解釈させない指定）
curl -g "$B/observations?select=observed_at,node_id,c80:counts_by_rssi->>\"-80\"&room_id=eq.room-1&observed_at=gte.2026-10-16T23:00:00%2B09:00&observed_at=lt.2026-10-17T00:00:00%2B09:00&order=observed_at" -H "$K"

# JSON の中の値で絞り込む
curl -g "$B/observations?select=observed_at&counts_by_rssi->>all=gte.20&node_id=eq.room-1-02" -H "$K"

# 件数だけ知りたい（応答ヘッダ Content-Range: 0-0/63 の / の後ろが総数）
curl -I "$B/observations?select=id&room_id=eq.room-1&limit=1" -H "$K" -H "Prefer: count=exact"

# CSV で受け取る
curl "$B/observations?select=observed_at,node_id,counts_by_rssi&room_id=eq.room-1" -H "$K" -H "Accept: text/csv"
```

演算子は `eq` / `neq` / `gt` / `gte` / `lt` / `lte` / `in.(a,b)` / `is.null` など。1回の応答は最大1,000行なので、それより多いときは `limit` と `offset`（または `observed_at` の範囲）で分けて取ります。

## 書き込む

### `submit_observations` — ノードからの観測値

```
POST /rpc/submit_observations
ヘッダ: apikey, Content-Type: application/json, x-node-token: <ノードのトークン>
本文:   {"payload": <観測値のオブジェクト または その配列>}
```

1回に500件まで送れます。Android アプリは未送信分を最大100件まとめて送ります。

```sh
curl -X POST "https://vxhbjnbsrfopczqxqbhp.supabase.co/rest/v1/rpc/submit_observations" \
  -H "apikey: <publishable key>" -H "x-node-token: <ノードのトークン>" -H "Content-Type: application/json" \
  -d '{"payload": [{
        "node_id": "room-1-02",
        "observed_at": "2026-10-16T23:35:00+09:00",
        "window_sec": 60,
        "counts_by_rssi": {"-60": 5, "-70": 16, "-80": 20, "-90": 25, "all": 25},
        "counts_by_vendor": {"apple": 14, "google": 3, "microsoft": 2, "samsung": 1, "other": 5, "none": 0},
        "rssi_stats": {"median": -72, "p10": -88, "p90": -58},
        "battery": 100, "charging": true, "app_version": "0.1.0"
      }]}'
```

応答（200）:

```json
{"created": 1, "duplicate": 0, "ignored_partial": 0}
```

#### 観測値の形

| キー | 必須 | 型 | 内容 |
|---|---|---|---|
| `node_id` | ○ | 文字列 | トークンのノード ID と一致していること |
| `observed_at` | ○ | 時刻 | 窓の開始時刻（タイムゾーン付き） |
| `window_sec` | ○ | 正の整数 | 窓の長さ（秒）。通常 60 |
| `counts_by_rssi` | ○ | オブジェクト | 窓内のユニーク端末のうち、最大 RSSI がその値以上の数（累積）。キー `-60` / `-70` / `-80` / `-90` / `all` |
| `counts_by_vendor` | ○ | オブジェクト | メーカー別の端末数。キー `apple` / `google` / `microsoft` / `samsung` / `other` / `none` |
| `rssi_stats` | | オブジェクト / null | 端末ごとの最大 RSSI の `median` / `p10` / `p90` |
| `adv_count` | | 整数 | 受信したパケット数 |
| `battery` / `charging` / `app_version` | | | 端末の状態 |
| `partial` | | 真偽 | `true` なら観測が欠けた窓。保存せず `ignored_partial` に数える |

ほかのキーは無視して保存しません。

#### 再送と重複

- 同じ `(node_id, observed_at)` が既にあれば保存せず `duplicate` に数えます。応答を受け取れずに再送しても二重になりません
- 重複や部分的な窓だけの送信でも、ノードの最終受信時刻（`last_seen_at`）は更新します
- 配列の途中に誤りがあると全体がエラーになり、何も保存されません

### `submit_ground_truth` — 実人数の入力（校正用）

```
POST /rpc/submit_ground_truth
ヘッダ: apikey, Content-Type: application/json, x-admin-token: <管理トークン>
本文:   {"room_id": "room-1", "people": 25, "observed_at": "2026-10-16T10:15:00+09:00", "note": "授業中"}
```

| キー | 必須 | 内容 |
|---|---|---|
| `room_id` | ○ | 教室 ID |
| `people` | ○ | 実人数（0以上の整数） |
| `observed_at` | | 数えた時刻。省くとサーバの受信時刻 |
| `note` | | メモ |

応答（200）: 登録した行

```json
{"id": 61, "room_id": "demo-3A", "observed_at": "2026-10-16T14:00:06.779882+00:00", "people": 3, "note": "check"}
```

## エラー

エラーの本文は PostgREST の形式です。

```json
{"code": "28000", "details": null, "hint": null, "message": "ノードトークンが無効です"}
```

| HTTP | `code` | 原因 | 例 |
|---|---|---|---|
| 401 | — | `apikey` ヘッダがない・誤り | `No API key found in request` |
| 403 | `28000` | トークンがない・無効・`node_id` が合わない、管理トークンが違う | `トークンはノード room-1-02 のものです（node_id=other）` |
| 400 | `22023` | 本文の誤り（`observed_at` が読めない、必須キーがない、500件超、存在しない教室への実人数） | `observed_at が読めません: x` |
| 400 | `PGRST102` | 本文が JSON として読めない | `Empty or invalid json` |
| 404 | `PGRST202` | 関数名・引数名の誤り | `Could not find the function public.add_node ...` |
| 401 / 403 | `42501` | テーブルへの直接の書き込み | `new row violates row-level security policy` |

Android アプリは、401・403・404・408・429・5xx・通信失敗のときは未送信分を残して1分後に再送し、それ以外の 4xx のときは捨てます。

## 管理操作（SQL Editor）

教室・ノードの登録、トークンの発行、校正値の登録は API からはできません。Supabase ダッシュボードの SQL Editor で実行します。

```sql
select private.add_room('room-2', '教室2');
select private.add_node('room-2-01', 'room-2');          -- ノードのトークンが返る（再表示できない）
select private.rotate_node_token('room-2-01');           -- トークンの再発行（古いトークンは使えなくなる）
select private.set_admin_token('長いランダム文字列');      -- 管理トークンの設定・変更
select private.set_calibration('room-2', '-80', 0.8, 0, 15, 30, 'メモ');
-- 引数: 教室, 特徴量, 係数, 切片, やや混雑の閾値, 混雑の閾値, メモ
```

## クライアントの例

### JavaScript（ブラウザ）

```js
const BASE = "https://vxhbjnbsrfopczqxqbhp.supabase.co/rest/v1";
const KEY = "<publishable key>";

const res = await fetch(`${BASE}/rpc/get_rooms`, { headers: { apikey: KEY } });
const { rooms } = await res.json();
for (const r of rooms) console.log(r.name, r.level, r.estimated_people);
```

### Python（標準ライブラリだけ）

```python
import json, urllib.request

BASE = "https://vxhbjnbsrfopczqxqbhp.supabase.co/rest/v1"
KEY = "<publishable key>"

req = urllib.request.Request(
    f"{BASE}/observations?select=observed_at,counts_by_rssi&room_id=eq.room-1&order=observed_at.desc&limit=10",
    headers={"apikey": KEY},
)
for row in json.load(urllib.request.urlopen(req)):
    print(row["observed_at"], row["counts_by_rssi"]["-80"])
```

端末内ログ（JSON Lines）の取り込みには `tools/import_jsonl.py` があります。
