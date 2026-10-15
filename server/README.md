# API サーバ（FastAPI + SQLite）

ノードから観測値を受け取り、教室ごとの混雑度を推定して Web ページ（`/`）に表示します。

## ローカルで起動する（Windows / PowerShell）

```powershell
cd server
uv venv --python 3.12 .venv            # uv がなければ: py -3.12 -m venv .venv
uv pip install --python .venv\Scripts\python.exe -r requirements-dev.txt
copy .env.example .env                 # ADMIN_TOKEN を好きな文字列に変える

.venv\Scripts\python.exe -m app.cli seed-demo        # デモ教室とダミーデータ（何度でも作り直せる）
.venv\Scripts\python.exe -m uvicorn app.main:app --reload --port 8000
```

ブラウザで http://localhost:8000/ を開きます。API の一覧は http://localhost:8000/docs にあります。

デモデータは「今日 8:00 から seed-demo を実行した時刻まで」です。最後の観測から3分経つとノードは「停止」表示になり、推定も「不明」になります（正しい動作です）。表示を戻したいときは seed-demo をもう一度実行します。

テスト: `.venv\Scripts\python.exe -m pytest`

## 実際の教室とノードを登録する

```powershell
.venv\Scripts\python.exe -m app.cli add-room room-3A "3年A組"
.venv\Scripts\python.exe -m app.cli add-node room-3A-01 room-3A   # 表示されたトークンを控える（再表示不可）
.venv\Scripts\python.exe -m app.cli list
```

端末内ログ（JSON Lines）があれば、そのまま取り込んでグラフにできます。部分的な窓は飛ばし、`devices` は保存しません。

```powershell
adb pull /sdcard/Android/data/com.crowdlevel.node/files/logs/ logs
.venv\Scripts\python.exe -m app.cli import-jsonl room-3A-01 logs\2026-10-08.jsonl
```

## curl での確認

```sh
curl -X POST http://localhost:8000/api/v1/observations \
  -H "Authorization: Bearer <ノードのトークン>" -H "Content-Type: application/json" \
  -d '{"node_id":"room-3A-01","observed_at":"2026-10-15T10:15:00+09:00","window_sec":60,
       "counts_by_rssi":{"-60":4,"-70":11,"-80":23,"-90":31},
       "counts_by_vendor":{"apple":14,"google":3,"microsoft":2,"samsung":1,"other":5,"none":6},
       "rssi_stats":{"median":-78,"p10":-91,"p90":-62},"battery":100,"charging":true,"app_version":"0.1.0"}'

curl http://localhost:8000/api/v1/rooms
curl "http://localhost:8000/api/v1/rooms/room-3A/history?from=2026-10-15T00:00:00%2B09:00"

curl -X POST http://localhost:8000/api/v1/ground-truth \
  -H "Authorization: Bearer <ADMIN_TOKEN>" -H "Content-Type: application/json" \
  -d '{"room_id":"room-3A","people":25,"note":"授業中"}'
```

## API の仕様（要点）

| エンドポイント | 認証 | 内容 |
|---|---|---|
| `POST /api/v1/observations` | ノードトークン | 1窓分の集計値。同じ `(node_id, observed_at)` の再送は `"duplicate"` を返して保存しない（再送してよい）。`"partial": true` は保存せず `"ignored_partial"`。余分なキー（`devices` など）は捨てる |
| `POST /api/v1/ground-truth` | 管理トークン | 実人数。`observed_at` を省くとサーバの受信時刻 |
| `GET /api/v1/rooms` | なし | 教室ごとの段階・推定人数・ノード状態 |
| `GET /api/v1/rooms/{id}/history?from=&to=` | なし | 観測時刻ごとの推定人数と RSSI 閾値別端末数（複数ノードは平均）、実人数。既定は今日（日本時間）|

- 時刻はすべて ISO 8601。タイムゾーンなしは日本時間とみなす。応答は日本時間（`+09:00`）
- ノードは最終受信から3分を超えると `offline`。教室の推定は `online` のノードの最新値の平均で、`online` がなければ `level: null`（「不明」）
- 推定人数 = max(0, 係数 × 特徴量 + 切片)。特徴量は `counts_by_rssi` のキー（`-60`/`-70`/`-80`/`-90`/`all`）
- 校正前の既定は「`-80` の端末数 × 1 + 0」、閾値は やや混雑 15人 / 混雑 30人（仮の値）。校正値は `python -m app.cli set-calibration room-3A --feature=-80 --coef 0.8 --moderate 15 --crowded 30` で登録し、教室ごとの最新の行が使われる

## Docker

```sh
docker build -t crowd-api server
docker run -p 8000:8000 -v crowd-data:/data -e ADMIN_TOKEN=... crowd-api
docker run --rm -v crowd-data:/data crowd-api python -m app.cli add-room room-3A "3年A組"
```
