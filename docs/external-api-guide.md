# 外部サイトから混雑度を取得するガイド

ほかの Web サイトやアプリから、教室の混雑度を読み出して表示するための手順です。
仕様の細部は [API リファレンス](api-reference.md) を参照してください。

## 1. 必要なもの

| 項目 | 値 |
|---|---|
| ベース URL | `https://vxhbjnbsrfopczqxqbhp.supabase.co/rest/v1` |
| キー | publishable key（`sb_publishable_` で始まる。このリポジトリの `web/config.js` にある） |

publishable key は **公開してよいキー** です。ブラウザの JavaScript にそのまま書いて構いません。このキーでできるのは、集計値の読み取りだけです。書き込みや、ノードの情報を読むことはできません。

**外部サイトに書いてはいけないもの**: ノードのトークン（`x-node-token`）と管理トークン（`x-admin-token`）。これらはノード端末と管理者だけが使います。

## 2. まず1回呼んでみる

```sh
curl "https://vxhbjnbsrfopczqxqbhp.supabase.co/rest/v1/rpc/get_rooms" \
  -H "apikey: <publishable key>"
```

ヘッダを付けられない環境では、URL の末尾に `?apikey=<publishable key>` を付けても動きます。

## 3. ブラウザから呼ぶ

CORS はすべてのオリジンに許可されているので、どのドメインのページからでも `fetch` で直接呼べます。サーバを用意する必要はありません。

### 貼るだけで動く例

次の HTML を外部サイトのページに貼ると、教室ごとの混雑度を1分ごとに更新して表示します。`<publishable key>` を実際のキーに置き換えてください。

```html
<div id="crowd">読み込み中…</div>
<script>
  const BASE = "https://vxhbjnbsrfopczqxqbhp.supabase.co/rest/v1";
  const KEY = "<publishable key>";
  const LABEL = { empty: "空き", moderate: "やや混雑", crowded: "混雑" };

  async function update() {
    const el = document.getElementById("crowd");
    try {
      const res = await fetch(BASE + "/rpc/get_rooms", { headers: { apikey: KEY } });
      if (!res.ok) throw new Error("HTTP " + res.status);
      const { rooms } = await res.json();
      el.textContent = "";
      for (const r of rooms) {
        const p = document.createElement("p");
        const people = r.estimated_people == null ? "" : "（約" + Math.round(r.estimated_people) + "人）";
        p.textContent = r.name + "： " + (LABEL[r.level] ?? "不明") + people;
        el.append(p);
      }
    } catch (e) {
      el.textContent = "混雑度を取得できませんでした";
    }
  }
  update();
  setInterval(update, 60_000);
</script>
```

表示例:

```
デモ 3年A組： 不明
デモ 3年B組： 不明
教室1（試験）： やや混雑（約26人）
```

### 特定の教室だけ表示する

`get_rooms` は全教室を返すので、受け取ったあとに `id` で絞り込みます。

```js
const room = rooms.find((r) => r.id === "room-1");
```

## 4. 返ってくる値の読み方

`get_rooms` の応答（抜粋）:

```json
{
  "now": "2026-10-16T14:36:30+00:00",
  "rooms": [
    {
      "id": "room-1",
      "name": "教室1（試験）",
      "level": "moderate",
      "estimated_people": 21.0,
      "updated_at": "2026-10-16T14:36:00+00:00",
      "nodes": [{ "id": "room-1-02", "status": "online" }]
    }
  ]
}
```

| フィールド | 表示のしかた |
|---|---|
| `level` | `empty` → 空き、`moderate` → やや混雑、`crowded` → 混雑。**`null` は「不明」**（ノードが止まっていて推定できない）。`null` を「空き」と表示しないこと |
| `estimated_people` | 推定人数の目安。校正前は誤差が大きいので、「約○人」のように丸めて出す |
| `updated_at` | 最後に観測した時刻。古ければ（数分以上前なら）その旨を表示すると親切 |
| `nodes[].status` | `online` / `offline`。最終受信から3分を超えると `offline` |

### 時刻は UTC で返る

時刻はすべて UTC（`+00:00`）です。日本時間で表示するには、ブラウザで変換します。

```js
new Date(r.updated_at).toLocaleTimeString("ja-JP", { timeZone: "Asia/Tokyo", hour: "2-digit", minute: "2-digit" });
// → "23:36"
```

## 5. 推移（グラフ用のデータ）を取る

```js
const url = BASE + "/rpc/get_room_history?room_id=room-1"
  + "&from_ts=" + encodeURIComponent("2026-10-16T08:00:00+09:00")
  + "&to_ts=" + encodeURIComponent("2026-10-16T18:00:00+09:00");
const h = await (await fetch(url, { headers: { apikey: KEY } })).json();
// h.points: [{ observed_at, estimated_people, level, counts_by_rssi, nodes }, ...]（1分ごと、古い順）
// h.ground_truth: 実人数の入力値
```

- `from_ts` / `to_ts` を省くと「今日（日本時間）の 0:00〜24:00」
- 時刻を URL に入れるときは `encodeURIComponent` を通す（`+09:00` の `+` が空白に化けるため）
- 1日分で最大1,440点。グラフは Chart.js などにそのまま渡せる（`web/app.js` が実例）

## 6. 生のデータを取る（分析・連携向け）

テーブルを直接読めます。Excel や Python で分析したいときに便利です。

```sh
# 教室 room-1 の最新10件
curl "https://vxhbjnbsrfopczqxqbhp.supabase.co/rest/v1/observations?room_id=eq.room-1&order=observed_at.desc&limit=10" \
  -H "apikey: <publishable key>"

# CSV で受け取る
curl "https://vxhbjnbsrfopczqxqbhp.supabase.co/rest/v1/observations?select=observed_at,node_id,counts_by_rssi&room_id=eq.room-1" \
  -H "apikey: <publishable key>" -H "Accept: text/csv"
```

1回の応答は最大1,000行です。それより多いときは期間（`observed_at=gte.…&observed_at=lt.…`）や `offset` で分けて取ります。書き方の詳細は [API リファレンスの「テーブルを直接読む」](api-reference.md#テーブルを直接読む) にあります。

## 7. サーバから呼ぶ（任意）

ブラウザから直接呼べるので、通常はサーバは要りません。サーバ側で取得してキャッシュしたい場合の例です。

### Node.js（18 以上、標準の fetch）

```js
const res = await fetch("https://vxhbjnbsrfopczqxqbhp.supabase.co/rest/v1/rpc/get_rooms", {
  headers: { apikey: process.env.SUPABASE_KEY },
});
const { rooms } = await res.json();
```

### Python（標準ライブラリだけ）

```python
import json, urllib.request

req = urllib.request.Request(
    "https://vxhbjnbsrfopczqxqbhp.supabase.co/rest/v1/rpc/get_rooms",
    headers={"apikey": "<publishable key>"},
)
rooms = json.load(urllib.request.urlopen(req))["rooms"]
```

## 8. 守ってほしいこと

| 項目 | お願い |
|---|---|
| 更新間隔 | データは1分ごとにしか増えないので、**30〜60秒に1回** で十分。それより短くしても値は変わらない |
| アクセス数 | Supabase の無料プランで動いている。多くの人が開くページでは、自前のサーバで取得して数十秒キャッシュし、ブラウザにはそこから配ると安全 |
| 失敗時の表示 | 通信失敗や `level: null` のときは「不明」「取得できませんでした」と出し、前の値を混雑度として出し続けない |
| 書き込み | 外部サイトからは書き込まない。実人数の入力は管理者が本システムの Web ページから行う |

## 9. うまくいかないとき

| 症状 | 原因と対処 |
|---|---|
| HTTP 401 `No API key found in request` | `apikey` ヘッダ（または `?apikey=`）が付いていない |
| HTTP 401 `Invalid API key` | キーの写し間違い。`sb_publishable_` から最後まで写す |
| HTTP 404 `PGRST202` | 関数名・引数名の誤り（`get_rooms`、`get_room_history` の `room_id` / `from_ts` / `to_ts`） |
| `level` がずっと `null` | ノードが止まっている（`nodes[].status` が `offline`）。管理者に連絡 |
| 推移が空 | `room_id` の誤り（存在しない教室でもエラーにならず空で返る）、または期間内にデータがない |
| 時刻が9時間ずれる | UTC のまま表示している。「4. 返ってくる値の読み方」の方法で日本時間に変換する |
| 長く使っていないと応答しない | 無料プランは1週間アクセスがないと一時停止する。ノードが毎分送っている間は止まらない |

## プライバシー

API で読めるのは、1分ごとの端末数などの集計値だけです。スマートフォンの識別子（MAC アドレスやそのハッシュ）は、ノード端末の外に出していないので、API からは取得できません。
