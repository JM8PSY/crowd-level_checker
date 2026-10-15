"""管理コマンド

  python -m app.cli add-room room-3A "3年A組"
  python -m app.cli add-node room-3A-01 room-3A          # トークンを表示する（再表示はできない）
  python -m app.cli rotate-token room-3A-01
  python -m app.cli set-calibration room-3A --feature=-80 --coef 0.8 --intercept 0 --moderate 15 --crowded 30
  python -m app.cli list
  python -m app.cli import-jsonl room-3A-01 logs/2026-10-08.jsonl   # 端末内ログを取り込む
  python -m app.cli seed-demo                            # 動作確認用のダミーデータ
"""
import argparse
import json
import random
import sys
from datetime import datetime, timedelta

from pydantic import ValidationError
from sqlalchemy import delete, func, select

from .config import JST
from .db import Calibration, GroundTruth, Node, Observation, Room, SessionLocal, init_db
from .estimate import FEATURES
from .schemas import ObservationIn, to_utc_naive
from .service import hash_token, ingest, new_token


def cmd_add_room(a):
    with SessionLocal() as s:
        if s.get(Room, a.room_id):
            sys.exit(f"教室 {a.room_id} は既にあります")
        s.add(Room(id=a.room_id, name=a.name))
        s.commit()
    print(f"教室 {a.room_id}（{a.name}）を追加しました")


def cmd_add_node(a):
    with SessionLocal() as s:
        if s.get(Room, a.room_id) is None:
            sys.exit(f"教室 {a.room_id} がありません。先に add-room してください")
        if s.get(Node, a.node_id):
            sys.exit(f"ノード {a.node_id} は既にあります（トークンの再発行は rotate-token）")
        token = new_token()
        s.add(Node(id=a.node_id, room_id=a.room_id, token_hash=hash_token(token)))
        s.commit()
    print(f"ノード {a.node_id} を教室 {a.room_id} に追加しました")
    print(f"トークン（この場でしか表示しません。Android の設定画面に入力）: {token}")


def cmd_rotate_token(a):
    with SessionLocal() as s:
        node = s.get(Node, a.node_id)
        if node is None:
            sys.exit(f"ノード {a.node_id} がありません")
        token = new_token()
        node.token_hash = hash_token(token)
        s.commit()
    print(f"ノード {a.node_id} の新しいトークン: {token}")


def cmd_set_calibration(a):
    if a.feature not in FEATURES:
        sys.exit(f"feature は {FEATURES} のどれか")
    with SessionLocal() as s:
        if s.get(Room, a.room_id) is None:
            sys.exit(f"教室 {a.room_id} がありません")
        s.add(
            Calibration(
                room_id=a.room_id,
                feature=a.feature,
                coef=a.coef,
                intercept=a.intercept,
                threshold_moderate=a.moderate,
                threshold_crowded=a.crowded,
                note=a.note,
            )
        )
        s.commit()
    print(f"教室 {a.room_id} の校正値を登録しました")


def cmd_list(_a):
    with SessionLocal() as s:
        for room in s.scalars(select(Room).order_by(Room.id)):
            n_obs = s.scalar(select(func.count()).select_from(Observation).where(Observation.room_id == room.id))
            print(f"{room.id}  {room.name}  観測 {n_obs} 件")
            for n in s.scalars(select(Node).where(Node.room_id == room.id).order_by(Node.id)):
                print(f"  - {n.id}  最終受信(UTC) {n.last_seen_at}")


def cmd_import_jsonl(a):
    """端末内ログ（JSON Lines）を取り込む。部分的な窓は飛ばす。devices は保存しない"""
    created = dup = skipped = 0
    with SessionLocal() as s:
        node = s.get(Node, a.node_id)
        if node is None:
            sys.exit(f"ノード {a.node_id} がありません")
        for path in a.files:
            with open(path, encoding="utf-8") as f:
                for lineno, line in enumerate(f, 1):
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        data = json.loads(line)
                        data["node_id"] = node.id  # ログの node_id が違っても指定のノードとして入れる
                        obs = ObservationIn.model_validate(data)
                    except (json.JSONDecodeError, ValidationError) as e:
                        print(f"{path}:{lineno} 読めない行を飛ばします: {e}", file=sys.stderr)
                        skipped += 1
                        continue
                    if obs.partial:
                        skipped += 1
                        continue
                    # 取り込みはノードの生死に影響させない（last_seen_at は元に戻す）
                    last_seen = node.last_seen_at
                    status = ingest(s, node, obs, received_at=to_utc_naive(obs.observed_at))
                    node.last_seen_at = last_seen
                    s.commit()
                    if status == "created":
                        created += 1
                    else:
                        dup += 1
    print(f"取り込み: 新規 {created} 件、重複 {dup} 件、飛ばした行 {skipped} 件")


# ---- デモデータ ----

DEMO_ROOMS = [
    ("demo-3A", "デモ 3年A組", ["demo-3A-01"], 32),
    ("demo-3B", "デモ 3年B組", ["demo-3B-01", "demo-3B-02"], 20),
]

# 時限（開始時, 分, 長さ分）
PERIODS = [(8, 50, 50), (9, 50, 50), (10, 50, 50), (11, 50, 50), (13, 20, 50), (14, 20, 50), (15, 20, 50)]


def demo_people(t_jst: datetime, capacity: int, rng: random.Random) -> int:
    minutes = t_jst.hour * 60 + t_jst.minute
    base = 0.0
    for h, m, length in PERIODS:
        start = h * 60 + m
        if start - 10 <= minutes < start:  # 授業前に集まってくる
            base = max(base, (minutes - (start - 10)) / 10 * 0.9)
        elif start <= minutes < start + length:
            base = max(base, 0.95)
        elif start + length <= minutes < start + length + 10:  # 休み時間は減る
            base = max(base, 0.4)
    if 12 * 60 + 40 <= minutes < 13 * 60 + 10:  # 昼休み
        base = max(base, 0.5)
    return max(0, round(capacity * base + rng.gauss(0, 1.5)))


def demo_counts(people: int, rng: random.Random) -> dict:
    # 1人あたり平均 1.3 台の BLE 端末＋教室外・据え置き機器のノイズ
    devices = [rng.gauss(-72, 9) for _ in range(max(0, int(people * 1.3 + rng.gauss(0, 1))))]
    devices += [rng.gauss(-90, 5) for _ in range(rng.randint(6, 14))]
    counts = {k: sum(1 for d in devices if d >= int(k)) for k in ("-60", "-70", "-80", "-90")}
    counts["all"] = len(devices)
    srt = sorted(devices)
    stats = {
        "median": round(srt[len(srt) // 2]),
        "p10": round(srt[int(len(srt) * 0.1)]),
        "p90": round(srt[min(len(srt) - 1, int(len(srt) * 0.9))]),
    }
    vendor = {"apple": 0, "google": 0, "microsoft": 0, "samsung": 0, "other": 0, "none": 0}
    for _ in devices:
        vendor[rng.choices(list(vendor), weights=[45, 8, 6, 5, 16, 20])[0]] += 1
    return {"counts_by_rssi": counts, "counts_by_vendor": vendor, "rssi_stats": stats}


def cmd_seed_demo(a):
    """今日（日本時間）の 8:00 から現在までのダミー観測と、30分ごとの実人数を作る"""
    rng = random.Random(a.seed)
    now_jst = datetime.now(JST).replace(second=0, microsecond=0)
    start = now_jst.replace(hour=8, minute=0)
    if now_jst < start:  # 朝8時前なら前日分を作る
        start -= timedelta(days=1)
        now_jst = start.replace(hour=18)

    tokens = {}
    with SessionLocal() as s:
        for room_id, name, node_ids, cap in DEMO_ROOMS:
            # 何度でも作り直せるよう、デモ教室のデータを消してから作る
            s.execute(delete(Observation).where(Observation.room_id == room_id))
            s.execute(delete(GroundTruth).where(GroundTruth.room_id == room_id))
            s.execute(delete(Calibration).where(Calibration.room_id == room_id))
            s.execute(delete(Node).where(Node.room_id == room_id))
            s.merge(Room(id=room_id, name=name))
            s.flush()
            nodes = []
            for nid in node_ids:
                tokens[nid] = new_token()
                node = Node(id=nid, room_id=room_id, token_hash=hash_token(tokens[nid]))
                s.add(node)
                nodes.append(node)
            s.commit()

            t = start
            while t <= now_jst:
                people = demo_people(t, cap, rng)
                for i, node in enumerate(nodes):
                    # 2台目のノードは10分前に止まった想定（offline 表示の確認用）
                    if i == 1 and t > now_jst - timedelta(minutes=10):
                        continue
                    obs = ObservationIn(
                        node_id=node.id,
                        observed_at=t,
                        window_sec=60,
                        battery=100,
                        charging=True,
                        app_version="demo",
                        **demo_counts(people, rng),
                    )
                    ingest(s, node, obs, received_at=to_utc_naive(t + timedelta(seconds=1)))
                if t.minute % 30 == 5:
                    s.add(GroundTruth(room_id=room_id, observed_at=to_utc_naive(t), people=people, note="demo"))
                t += timedelta(minutes=1)
            s.commit()
    print(f"デモデータを作りました（{start:%m/%d %H:%M}〜{now_jst:%H:%M}、日本時間）")
    for nid, tok in tokens.items():
        print(f"  {nid} のトークン: {tok}")


def main(argv=None):
    p = argparse.ArgumentParser(prog="python -m app.cli", description="混雑度 API の管理コマンド")
    sub = p.add_subparsers(dest="cmd", required=True)

    sp = sub.add_parser("add-room", help="教室を追加")
    sp.add_argument("room_id")
    sp.add_argument("name")
    sp.set_defaults(func=cmd_add_room)

    sp = sub.add_parser("add-node", help="ノードを追加してトークンを発行")
    sp.add_argument("node_id")
    sp.add_argument("room_id")
    sp.set_defaults(func=cmd_add_node)

    sp = sub.add_parser("rotate-token", help="ノードのトークンを再発行")
    sp.add_argument("node_id")
    sp.set_defaults(func=cmd_rotate_token)

    sp = sub.add_parser("set-calibration", help="教室の推定式と閾値を登録")
    sp.add_argument("room_id")
    sp.add_argument("--feature", default="-80", help='counts_by_rssi のキー（"-80" など。--feature=-80 の形で書く）')
    sp.add_argument("--coef", type=float, required=True)
    sp.add_argument("--intercept", type=float, default=0.0)
    sp.add_argument("--moderate", type=float, required=True, help="この人数以上で「やや混雑」")
    sp.add_argument("--crowded", type=float, required=True, help="この人数以上で「混雑」")
    sp.add_argument("--note")
    sp.set_defaults(func=cmd_set_calibration)

    sp = sub.add_parser("list", help="教室とノードの一覧")
    sp.set_defaults(func=cmd_list)

    sp = sub.add_parser("import-jsonl", help="端末内ログ（JSON Lines）を取り込む")
    sp.add_argument("node_id")
    sp.add_argument("files", nargs="+")
    sp.set_defaults(func=cmd_import_jsonl)

    sp = sub.add_parser("seed-demo", help="demo-3A / demo-3B にダミーデータを作る（何度でも作り直せる）")
    sp.add_argument("--seed", type=int, default=1)
    sp.set_defaults(func=cmd_seed_demo)

    a = p.parse_args(argv)
    init_db()
    a.func(a)


if __name__ == "__main__":
    main()
