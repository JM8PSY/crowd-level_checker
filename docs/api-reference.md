# API リファレンス

BLE教室混雑度推定システムの API です。Supabase（Postgres + PostgREST）の REST API をそのまま使います。
2026-10-17 時点の内容で、例は実際に呼んで確かめています。
外部サイトに組み込む手順は [外部サイトから混雑度を取得するガイド](external-api-guide.md) にあります。

- [共通事項](#共通事項)
- [部屋](#部屋)
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

- 入力は ISO 8601 のタイムゾーン付き（例 `2026-10-17T00:45:00+09:00`）。タイムゾーンを省くと UTC とみなされるので、必ず付ける
- **出力は UTC**（例 `2026-10-16T15:45:00+00:00`）。日本時間で表示するときはクライアントで変換する
- 観測時刻 `observed_at` は1分の窓の開始時刻（10:15:00〜10:15:59 の観測は 10:15:00）

### 公開範囲とプライバシー

- 読めるのは集計値（窓内の端末数、RSSI の統計など）だけです。個人を識別・追跡できる値（MAC アドレスやそのハッシュ）は、端末の外に出していないので API にもありません
- `nodes`（トークンのハッシュを含む）は読めません。ノードの状態は `get_rooms` が返します
- テーブルへの直接の書き込みはできません。書き込みは下の RPC だけです

## 部屋

混雑度は部屋ごとに推定します。いま使っている部屋は次の2つです。

| `id` | `name` | `floor` |
|---|---|---|
| `rest-3f` | 3F休憩室 | 3F |
| `rest-2f` | 2F休憩室 | 2F |

- ノード（スマホ）は、アプリの「設置場所」のトグルで部屋を選びます。選んだ部屋の ID を観測値に添えて送り、サーバはその部屋の観測として保存します
- ノードの今の部屋は、最後に届いた観測の部屋です。部屋を切り替えると、ノードは `get_rooms` の応答で新しい部屋の下に表示されます
- 窓の途中で切り替えた1分は、2部屋の観測が混ざるのでアプリは送りません
- 使わなくなった部屋（`active = false`、例：試験用だった `room-1`）は `get_rooms` に出ず、観測も受け付けません。過去の推移は `get_room_history` で読めます

## 混雑度を読む

### `get_rooms` — 部屋ごとの最新の混雑度

```
GET /rpc/get_rooms
GET /rpc/get_rooms?room_id=rest-3f
```

| 引数 | 必須 | 内容 |
|---|---|---|
| `room_id` | | 指定するとその部屋だけを返す（使っていない部屋や存在しない ID なら `rooms` は空） |

POST（本文 `{}` か `{"room_id": "rest-3f"}`）でも呼べます。

```sh
curl "https://vxhbjnbsrfopczqxqbhp.supabase.co/rest/v1/rpc/get_rooms" -H "apikey: <publishable key>"
```

応答（200）:

```json
{
  "now": "2026-10-16T15:46:10.123456+00:00",
  "rooms": [
    {
      "id": "rest-3f",
      "name": "3F休憩室",
      "floor": "3F",
      "level": "empty",
      "estimated_people": 4.5,
      "updated_at": "2026-10-16T15:45:00+00:00",
      "calibration": {
        "feature": "-80", "coef": 1, "intercept": 0,
        "threshold_moderate": 15, "threshold_crowded": 30, "calibrated": false
      },
      "nodes": [
        {
          "id": "room-1-02", "status": "online",
          "last_seen_at": "2026-10-16T15:46:00.586000+00:00",
          "last_observed_at": "2026-10-16T15:45:00+00:00",
          "estimator": {"feature": "-60", "coef": 0.5, "intercept": 0},
          "battery": 25, "charging": true, "app_version": "0.1.0"
        }
      ]
    },
    {
      "id": "rest-2f",
      "name": "2F休憩室",
      "floor": "2F",
      "level": null,
      "estimated_people": null,
      "updated_at": "2026-10-16T15:43:00+00:00",
      "calibration": { "...": "..." },
      "nodes": []
    }
  ]
}
```

| フィールド | 型 | 内容 |
|---|---|---|
| `now` | 時刻 | サーバの現在時刻 |
| `rooms[].id` / `name` / `floor` | 文字列 | 部屋の ID、表示名、階。表示順（3F → 2F） |
| `rooms[].level` | `"empty"` / `"moderate"` / `"crowded"` / `null` | 空き / やや混雑 / 混雑。その部屋に稼働中のノードがなければ `null`（不明） |
| `rooms[].estimated_people` | 数値 / `null` | 推定人数（小数1桁）。その部屋にいる稼働中のノードの最新の観測から計算し、複数あれば平均 |
| `rooms[].updated_at` | 時刻 / `null` | その部屋で最も新しい観測の `observed_at`。ノードがほかの部屋へ移ったあとも残る |
| `rooms[].calibration` | オブジェクト | 段階の閾値と、式の添えられていない観測に使う代わりの式（下の「推定の仕組み」）。`calibrated: false` は校正前の既定値 |
| `rooms[].nodes[]` | 配列 | いまその部屋にいるノード。いなければ空 |
| `rooms[].nodes[].status` | `"online"` / `"offline"` | 最終受信から3分以内なら `online` |
| `rooms[].nodes[].last_seen_at` | 時刻 / `null` | 最後に送信を受けた時刻（再送分を受けたときも更新） |
| `rooms[].nodes[].last_observed_at` | 時刻 / `null` | そのノードの最新の観測の `observed_at` |
| `rooms[].nodes[].estimator` | オブジェクト / `null` | そのノードの最新の観測に添えられていた推定の式 |
| `rooms[].nodes[].battery` / `charging` / `app_version` | | 最後の送信に含まれていた端末の状態 |

#### 推定の仕組み

```
推定人数 = max(0, coef × counts_by_rssi[feature] + intercept)
level   = 推定人数 ≥ threshold_crowded なら crowded、≥ threshold_moderate なら moderate、それ以外は empty
```

- **式（feature / coef / intercept）はノードが決めます。** ノードは観測値に `estimator`（スマホの設定画面の式）を添えて送り、サーバはその観測をその式で推定します。スマホの画面に出る推定人数と同じ値になります
- 式は観測ごとに保存されるので、ノードの式をあとで変えても、過去の点はそのときの式のまま残ります
- `estimator` のない観測（2026-10-17 0:20 より前のもの、式を送らないクライアント）は、`calibrations` の最新の行の式、行がなければ既定値（`-80`、係数1、切片0）で推定します
- 閾値（`threshold_moderate` / `threshold_crowded`）は部屋ごとに `calibrations` の最新の行から読み、行がなければ 15 / 30 です
- `feature` は `counts_by_rssi` のキー（`-60` / `-70` / `-80` / `-90` / `all`）
- 複数のノードがある部屋では、ノードごとに推定してから平均します

### `get_room_history` — 推移

```
GET /rpc/get_room_history?room_id=<部屋ID>&from_ts=<開始>&to_ts=<終了>
```

| 引数 | 必須 | 内容 |
|---|---|---|
| `room_id` | ○ | 部屋 ID。使っていない部屋（`room-1` など）も読める |
| `from_ts` | | 開始時刻（この時刻を含む）。省くと今日（日本時間）の 0:00 |
| `to_ts` | | 終了時刻（この時刻を含まない）。省くと明日（日本時間）の 0:00 |

URL に書くときは `+` を `%2B` にします。POST で本文に JSON（`{"room_id": "rest-3f", "from_ts": "...", "to_ts": "..."}`）を渡しても同じです。

```sh
curl "https://vxhbjnbsrfopczqxqbhp.supabase.co/rest/v1/rpc/get_room_history?room_id=rest-3f&from_ts=2026-10-17T00:45:00%2B09:00&to_ts=2026-10-17T00:47:00%2B09:00" \
  -H "apikey: <publishable key>"
```

応答（200）:

```json
{
  "room_id": "rest-3f",
  "from": "2026-10-16T15:45:00+00:00",
  "to": "2026-10-16T15:47:00+00:00",
  "feature": "-80",
  "calibrated": false,
  "threshold_moderate": 15,
  "threshold_crowded": 30,
  "points": [
    {
      "observed_at": "2026-10-16T15:45:00+00:00",
      "estimated_people": 4.5,
      "level": "empty",
      "counts_by_rssi": {"-60": 9.0, "-70": 17.0, "-80": 21.0, "-90": 26.0, "all": 26.0},
      "nodes": 1
    }
  ],
  "ground_truth": []
}
```

| フィールド | 内容 |
|---|---|
| `points[]` | 観測時刻ごとに1点、古い順。同じ時刻に複数ノードの観測があれば `counts_by_rssi` は平均（小数1桁）、`nodes` はその数 |
| `points[].estimated_people` / `level` | その時刻の各観測を、それぞれに添えられていた式で推定した値の平均と、その段階（閾値は現在の値） |
| `ground_truth[]` | 期間内に入力された実人数、古い順 |
| `feature` / `calibrated` / `threshold_*` | 部屋の代わりの式と閾値（`get_rooms` の `calibration` と同じ） |

存在しない `room_id` でもエラーにはならず、`points` と `ground_truth` が空で返ります。

## テーブルを直接読む

PostgREST の書式で、テーブルを直接読めます。どれも `GET` で、`apikey` ヘッダが必要です。

| テーブル | 内容 |
|---|---|
| `rooms` | `id`, `name`, `floor`, `sort_order`（表示順）, `active`（使っているか） |
| `observations` | ノードの観測値（1ノード・1分につき1行）。下の「観測値の形」 |
| `ground_truth` | 実人数。`id`, `room_id`, `observed_at`, `people`, `note`, `created_at` |
| `calibrations` | 閾値と代わりの式の履歴。`id`, `room_id`, `feature`, `coef`, `intercept`, `threshold_moderate`, `threshold_crowded`, `note`, `created_at` |

`observations` の列: `id`, `node_id`, `room_id`（観測したときの部屋）, `observed_at`, `received_at`, `window_sec`, `counts_by_rssi`, `counts_by_vendor`, `rssi_stats`, `adv_count`, `battery`, `charging`, `app_version`, `estimator`（推定の式。ない観測は `null`）

### よく使う書き方

```sh
B=https://vxhbjnbsrfopczqxqbhp.supabase.co/rest/v1
K="apikey: <publishable key>"

# 使っている部屋の一覧（表示順）
curl "$B/rooms?active=is.true&order=sort_order" -H "$K"

# 3F休憩室の最新60件（新しい順）
curl "$B/observations?room_id=eq.rest-3f&order=observed_at.desc&limit=60" -H "$K"

# 期間を指定し、必要な列だけ。JSON の中の値は ->> で取り出し、キーは "" で囲む（-g は curl に [] {} を解釈させない指定）
curl -g "$B/observations?select=observed_at,node_id,c80:counts_by_rssi->>\"-80\"&room_id=eq.rest-3f&observed_at=gte.2026-10-17T00:00:00%2B09:00&observed_at=lt.2026-10-18T00:00:00%2B09:00&order=observed_at" -H "$K"

# JSON の中の値で絞り込む
curl -g "$B/observations?select=observed_at&counts_by_rssi->>all=gte.20&room_id=eq.rest-3f" -H "$K"

# 件数だけ知りたい（応答ヘッダ Content-Range: 0-0/63 の / の後ろが総数）
curl -I "$B/observations?select=id&room_id=eq.rest-3f&limit=1" -H "$K" -H "Prefer: count=exact"

# CSV で受け取る
curl "$B/observations?select=observed_at,room_id,counts_by_rssi&node_id=eq.room-1-02" -H "$K" -H "Accept: text/csv"
```

演算子は `eq` / `neq` / `gt` / `gte` / `lt` / `lte` / `in.(a,b)` / `is.null` / `is.true` など。1回の応答は最大1,000行なので、それより多いときは `limit` と `offset`（または `observed_at` の範囲）で分けて取ります。

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
        "room_id": "rest-3f",
        "observed_at": "2026-10-17T00:45:00+09:00",
        "window_sec": 60,
        "counts_by_rssi": {"-60": 9, "-70": 17, "-80": 21, "-90": 26, "all": 26},
        "counts_by_vendor": {"apple": 14, "google": 3, "microsoft": 2, "samsung": 1, "other": 6, "none": 0},
        "rssi_stats": {"median": -72, "p10": -88, "p90": -58},
        "battery": 25, "charging": true, "app_version": "0.1.0",
        "estimator": {"feature": "-60", "coef": 0.5, "intercept": 0}
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
| `room_id` | | 文字列 | 観測した部屋（`rest-3f` / `rest-2f`）。使っている部屋だけ受け付ける。省くとノードの今の部屋 |
| `observed_at` | ○ | 時刻 | 窓の開始時刻（タイムゾーン付き） |
| `window_sec` | ○ | 正の整数 | 窓の長さ（秒）。通常 60 |
| `counts_by_rssi` | ○ | オブジェクト | 窓内のユニーク端末のうち、最大 RSSI がその値以上の数（累積）。キー `-60` / `-70` / `-80` / `-90` / `all`。Android アプリは、Apple の「探す」だけ・iBeacon だけを出しているアドレスを数えない（同じ端末の二重カウントと固定ビーコンを外すため。`counts_by_vendor` と `rssi_stats` も同じ） |
| `counts_by_vendor` | ○ | オブジェクト | メーカー別の端末数。キー `apple` / `google` / `microsoft` / `samsung` / `other` / `none` |
| `rssi_stats` | | オブジェクト / null | 端末ごとの最大 RSSI の `median` / `p10` / `p90` |
| `adv_count` | | 整数 | 受信したパケット数 |
| `battery` / `charging` / `app_version` | | | 端末の状態 |
| `estimator` | | オブジェクト | 推定の式 `{"feature": "-60", "coef": 0.5, "intercept": 0}`。`feature` は `-60` / `-70` / `-80` / `-90` / `all`、`coef` と `intercept` は数値（`intercept` は省くと0）。省くとサーバの代わりの式で推定する。Android アプリは設定画面の式を必ず添える |
| `partial` | | 真偽 | `true` なら観測が欠けた窓。保存せず `ignored_partial` に数える |

ほかのキーは無視して保存しません。

#### 部屋、再送と重複

- 観測は `room_id` の部屋に保存します。ノードの今の部屋は、その送信で最も新しい観測の部屋に切り替わります。古い窓の再送（切り替える前の部屋の観測）では戻りません
- 同じ `(node_id, observed_at)` が既にあれば保存せず `duplicate` に数えます。応答を受け取れずに再送しても二重になりません
- 重複や部分的な窓だけの送信でも、ノードの最終受信時刻（`last_seen_at`）は更新します
- 配列の途中に誤りがあると全体がエラーになり、何も保存されません

### `submit_ground_truth` — 実人数の入力（校正用）

```
POST /rpc/submit_ground_truth
ヘッダ: apikey, Content-Type: application/json, x-admin-token: <管理トークン>
本文:   {"room_id": "rest-3f", "people": 5, "observed_at": "2026-10-17T12:15:00+09:00", "note": "昼休み"}
```

| キー | 必須 | 内容 |
|---|---|---|
| `room_id` | ○ | 部屋 ID |
| `people` | ○ | 実人数（0以上の整数） |
| `observed_at` | | 数えた時刻。省くとサーバの受信時刻 |
| `note` | | メモ |

応答（200）: 登録した行（`id`, `room_id`, `observed_at`, `people`, `note`）

## エラー

エラーの本文は PostgREST の形式です。

```json
{"code": "28000", "details": null, "hint": null, "message": "ノードトークンが無効です"}
```

| HTTP | `code` | 原因 | 例 |
|---|---|---|---|
| 401 | — | `apikey` ヘッダがない・誤り | `No API key found in request` / `Invalid API key` |
| 403 | `28000` | トークンがない・無効・`node_id` が合わない、管理トークンが違う | `トークンはノード room-1-02 のものです（node_id=other）` |
| 400 | `22023` | 本文の誤り（`observed_at` が読めない、必須キーがない、`estimator` の形が違う、使っていない部屋、500件超、存在しない部屋への実人数） | `部屋 rest-9f はありません（使える部屋は get_rooms で確認）` |
| 400 | `PGRST102` | 本文が JSON として読めない | `Empty or invalid json` |
| 404 | `PGRST202` | 関数名・引数名の誤り | `Could not find the function public.add_node ...` |
| 401 / 403 | `42501` | テーブルへの直接の書き込み | `new row violates row-level security policy` |

Android アプリは、401・403・404・408・429・5xx・通信失敗のときは未送信分を残して1分後に再送し、それ以外の 4xx のときは捨てます。

## 管理操作（SQL Editor）

部屋・ノードの登録、トークンの発行、校正値の登録は API からはできません。Supabase ダッシュボードの SQL Editor で実行します。

```sql
-- 部屋の追加・変更（ID, 表示名, 階, 表示順, 使うか）。アプリのトグルにも同じ ID を足す（android の settings/Rooms.kt）
select private.set_room('rest-1f', '1F休憩室', '1F', 3);
select private.set_room('room-1', '教室1（試験）', null, 99, false);   -- 使わない部屋にする

select private.add_node('node-02', 'rest-3f');            -- ノードを追加。トークンが返る（再表示できない）
select private.rotate_node_token('node-02');              -- トークンの再発行（古いトークンは使えなくなる）
select private.set_admin_token('長いランダム文字列');      -- 管理トークンの設定・変更
select private.set_calibration('rest-3f', '-80', 1, 0, 15, 30, 'メモ');
-- 引数: 部屋, 代わりの特徴量, 係数, 切片, やや混雑の閾値, 混雑の閾値, メモ
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
    f"{BASE}/observations?select=observed_at,counts_by_rssi&room_id=eq.rest-3f&order=observed_at.desc&limit=10",
    headers={"apikey": KEY},
)
for row in json.load(urllib.request.urlopen(req)):
    print(row["observed_at"], row["counts_by_rssi"]["-80"])
```

端末内ログ（JSON Lines）の取り込みには `tools/import_jsonl.py` があります。
