"""端末内ログ（JSON Lines）を Supabase に取り込む。標準ライブラリだけで動く

  python tools/import_jsonl.py <ノードID> <ノードのトークン> logs/2026-10-08.jsonl [...]

接続先は supabase/.env の SUPABASE_URL / SUPABASE_PUBLISHABLE_KEY（環境変数でも可）。
ノードと同じ submit_observations を呼ぶので、部分的な窓は保存されず、取り込み済みの窓は重複として飛ばされる。
devices（端末ごとの要約）はサーバに送らない。
"""
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

BATCH = 200
ENV_FILE = Path(__file__).resolve().parent.parent / "supabase" / ".env"


def load_env() -> dict:
    env = {}
    if ENV_FILE.exists():
        for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip()
    env.update({k: v for k, v in os.environ.items() if k.startswith("SUPABASE_")})
    return env


def post(url: str, key: str, token: str, items: list) -> dict:
    req = urllib.request.Request(
        f"{url}/rest/v1/rpc/submit_observations",
        data=json.dumps({"payload": items}).encode("utf-8"),
        headers={"Content-Type": "application/json", "apikey": key, "x-node-token": token},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as res:
            return json.load(res)
    except urllib.error.HTTPError as e:
        sys.exit(f"HTTP {e.code}: {e.read().decode('utf-8', 'replace')}")


def main() -> None:
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    node_id, token, files = sys.argv[1], sys.argv[2], sys.argv[3:]
    env = load_env()
    url, key = env.get("SUPABASE_URL"), env.get("SUPABASE_PUBLISHABLE_KEY")
    if not url or not key:
        sys.exit("SUPABASE_URL と SUPABASE_PUBLISHABLE_KEY を supabase/.env か環境変数に設定してください")

    items, total = [], {"created": 0, "duplicate": 0, "ignored_partial": 0}
    skipped = 0

    def flush():
        if items:
            for k, v in post(url, key, token, items).items():
                total[k] += v
            items.clear()

    for path in files:
        with open(path, encoding="utf-8") as f:
            for lineno, line in enumerate(f, 1):
                if not line.strip():
                    continue
                try:
                    d = json.loads(line)
                except json.JSONDecodeError:
                    print(f"{path}:{lineno} 読めない行を飛ばします", file=sys.stderr)
                    skipped += 1
                    continue
                d.pop("devices", None)  # 端末ごとの要約は送らない
                d["node_id"] = node_id  # ログの node_id が違っても指定のノードとして入れる
                items.append(d)
                if len(items) >= BATCH:
                    flush()
    flush()
    print(f"新規 {total['created']} 件、重複 {total['duplicate']} 件、部分的な窓 {total['ignored_partial']} 件、読めない行 {skipped} 件")


if __name__ == "__main__":
    main()
