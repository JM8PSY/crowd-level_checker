"""マイグレーションの SQL を手元の Postgres（pgserver）で検証する

  uv pip install -r supabase/tests/requirements.txt
  python -m pytest supabase/tests

Supabase と同じく anon / authenticated ロールを作り、PostgREST と同じ形で request.headers を渡して RPC を呼ぶ。
"""
import json
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pgserver
import psycopg
import pytest

MIGRATIONS = sorted((Path(__file__).resolve().parent.parent / "migrations").glob("*.sql"))
JST = timezone(timedelta(hours=9))


@pytest.fixture(scope="session")
def server(tmp_path_factory):
    srv = pgserver.get_server(tmp_path_factory.mktemp("pgdata"), cleanup_mode="stop")
    yield srv


@pytest.fixture()
def db(server):
    """テストごとに新しいデータベースを作り、マイグレーションを流す"""
    name = "t_" + uuid.uuid4().hex[:12]
    admin_uri = server.get_uri()
    with psycopg.connect(admin_uri, autocommit=True) as c:
        c.execute(f"create database {name}")
        for role in ("anon", "authenticated"):
            c.execute(f"do $$ begin create role {role} nologin; exception when duplicate_object then null; end $$")
    uri = admin_uri.rsplit("/", 1)[0] + "/" + name
    with psycopg.connect(uri, autocommit=True) as c:
        # Supabase の既定に合わせ、public のテーブルと関数を anon にも許可しておく（RLS と revoke で絞れることを確かめる）
        c.execute("grant usage on schema public to anon, authenticated")
        c.execute("alter default privileges in schema public grant all on tables to anon, authenticated")
        c.execute("alter default privileges in schema public grant all on functions to anon, authenticated")
        for m in MIGRATIONS:
            c.execute(m.read_text(encoding="utf-8"))
        c.execute("select private.add_room('r1', '教室1')")
        token = c.execute("select private.add_node('r1-01', 'r1')").fetchone()[0]
        c.execute("select private.set_admin_token('admin-secret')")
    yield {"uri": uri, "token": token}
    with psycopg.connect(admin_uri, autocommit=True) as c:
        c.execute(f"drop database {name} with (force)")


def call(db, sql, params=(), headers=None, role="anon"):
    """PostgREST と同じく、ロールを切り替えて request.headers を設定してから実行する"""
    with psycopg.connect(db["uri"]) as c:
        c.execute(f"set local role {role}")
        c.execute("select set_config('request.headers', %s, true)", (json.dumps(headers or {}),))
        row = c.execute(sql, params).fetchone()
        c.commit()
        return row[0] if row else None


def admin(db, sql, params=()):
    with psycopg.connect(db["uri"], autocommit=True) as c:
        cur = c.execute(sql, params)
        return cur.fetchone() if cur.description else None


def obs(t: datetime, c80=20, **kw):
    return {
        "node_id": "r1-01",
        "observed_at": t.isoformat(),
        "window_sec": 60,
        "counts_by_rssi": {"-60": 2, "-70": 10, "-80": c80, "-90": 30, "all": 33},
        "counts_by_vendor": {"apple": 10, "google": 2, "microsoft": 1, "samsung": 1, "other": 4, "none": 5},
        "rssi_stats": {"median": -78, "p10": -91, "p90": -62},
        "battery": 100,
        "charging": True,
        "app_version": "0.1.0",
        **kw,
    }


def submit(db, payload, token=None):
    return call(db, "select public.submit_observations(%s::jsonb)", (json.dumps(payload),),
                {"x-node-token": token if token is not None else db["token"]})


def recent() -> datetime:
    return datetime.now(JST).replace(second=0, microsecond=0) - timedelta(minutes=1)


def test_submit_and_duplicate(db):
    t = recent()
    assert submit(db, obs(t)) == {"created": 1, "duplicate": 0, "ignored_partial": 0}
    # 配列でまとめて送れる。既にある窓は重複として数える
    r = submit(db, [obs(t), obs(t - timedelta(minutes=1)), obs(t - timedelta(minutes=2), partial=True)])
    assert r == {"created": 1, "duplicate": 1, "ignored_partial": 1}
    assert admin(db, "select count(*) from public.observations")[0] == 2


def test_extra_keys_not_stored(db):
    submit(db, obs(recent(), devices=[{"h": "abc", "max_rssi": -60, "n": 3, "cid": 76}], adv_count=99))
    row = admin(db, "select adv_count, counts_by_rssi from public.observations")
    assert row[0] == 99


def test_auth_errors(db):
    t = recent()
    with pytest.raises(psycopg.Error, match="x-node-token"):
        call(db, "select public.submit_observations(%s::jsonb)", (json.dumps(obs(t)),), {})
    with pytest.raises(psycopg.Error, match="無効"):
        submit(db, obs(t), token="wrong")
    with pytest.raises(psycopg.Error, match="r1-01"):
        submit(db, {**obs(t), "node_id": "other"})
    with pytest.raises(psycopg.Error, match="observed_at"):
        submit(db, {**obs(t), "observed_at": "きのう"})


def test_anon_cannot_write_or_read_nodes(db):
    with pytest.raises(psycopg.Error):
        call(db, "insert into public.rooms values ('x', 'x') returning id")
    # nodes は RLS で行が見えない（トークンのハッシュを公開しない）
    assert call(db, "select count(*) from public.nodes") == 0
    assert call(db, "select count(*) from public.rooms where id = 'r1'") == 1
    with pytest.raises(psycopg.Error):
        call(db, "select private.add_node('x', 'r1')")
    with pytest.raises(psycopg.Error):
        call(db, "select private.hash_token('x')")


def test_get_rooms_default_estimate(db):
    submit(db, obs(recent(), c80=20))
    room = call(db, "select public.get_rooms()")["rooms"][0]
    # 既定：-80 の端末数 × 1。閾値 15 / 30 なので「やや混雑」
    assert room["estimated_people"] == 20
    assert room["level"] == "moderate"
    assert room["calibration"]["calibrated"] is False
    assert room["nodes"][0]["status"] == "online"
    assert room["nodes"][0]["battery"] == 100


def test_get_rooms_uses_latest_calibration(db):
    admin(db, "select private.set_calibration('r1', '-70', 1, 0, 100, 200)")
    admin(db, "select private.set_calibration('r1', '-70', 2, 1, 10, 20, 'メモ')")
    submit(db, obs(recent()))
    room = call(db, "select public.get_rooms()")["rooms"][0]
    assert room["estimated_people"] == 21  # 2 × 10 + 1
    assert room["level"] == "crowded"
    assert room["calibration"]["calibrated"] is True


def test_offline_node_has_no_estimate(db):
    submit(db, obs(recent()))
    admin(db, "update public.nodes set last_seen_at = now() - interval '10 minutes'")
    room = call(db, "select public.get_rooms()")["rooms"][0]
    assert room["nodes"][0]["status"] == "offline"
    assert room["level"] is None and room["estimated_people"] is None


def test_room_without_nodes(db):
    admin(db, "select private.add_room('r2', '教室2')")
    rooms = call(db, "select public.get_rooms()")["rooms"]
    # 表示順（sort_order）→ ID 順。r1 / r2 は sort_order 0、休憩室は 1 / 2
    assert [r["id"] for r in rooms] == ["r1", "r2", "rest-3f", "rest-2f"]
    assert rooms[1]["nodes"] == [] and rooms[1]["level"] is None


def test_history_averages_nodes(db):
    token2 = admin(db, "select private.add_node('r1-02', 'r1')")[0]
    t = recent()
    submit(db, obs(t, c80=20))
    submit(db, {**obs(t, c80=30), "node_id": "r1-02"}, token=token2)
    submit(db, obs(t - timedelta(minutes=1), c80=10))
    h = call(db, "select public.get_room_history('r1', %s, %s)", (t - timedelta(hours=1), t + timedelta(hours=1)))
    assert [p["estimated_people"] for p in h["points"]] == [10, 25]
    assert h["points"][1]["nodes"] == 2
    assert h["points"][1]["counts_by_rssi"]["-80"] == 25
    assert datetime.fromisoformat(h["points"][1]["observed_at"]) == t


def test_history_default_is_today(db):
    submit(db, obs(recent()))
    submit(db, obs(recent() - timedelta(days=2)))
    h = call(db, "select public.get_room_history('r1')")
    assert len(h["points"]) == 1


def test_ground_truth(db):
    t = recent()
    sql = "select public.submit_ground_truth(%s, %s, %s, %s)"
    with pytest.raises(psycopg.Error, match="管理トークン"):
        call(db, sql, ("r1", 18, t, None), {"x-admin-token": "x"})
    with pytest.raises(psycopg.Error, match="教室"):
        call(db, sql, ("nope", 18, t, None), {"x-admin-token": "admin-secret"})
    r = call(db, sql, ("r1", 18, t, "授業中"), {"x-admin-token": "admin-secret"})
    assert r["people"] == 18
    h = call(db, "select public.get_room_history('r1', %s, %s)", (t - timedelta(hours=1), t + timedelta(hours=1)))
    assert h["ground_truth"][0]["people"] == 18


# ---- 推定の式をノードから受け取る（20261017000000_estimator_from_node.sql）----

def test_estimator_from_node_is_used(db):
    # -60 の端末数 2 × 0.5 + 1 = 2
    submit(db, obs(recent(), estimator={"feature": "-60", "coef": 0.5, "intercept": 1}))
    room = call(db, "select public.get_rooms()")["rooms"][0]
    assert room["estimated_people"] == 2
    assert room["level"] == "empty"
    assert room["nodes"][0]["estimator"] == {"feature": "-60", "coef": 0.5, "intercept": 1}


def test_estimator_intercept_defaults_to_zero_and_extra_keys_dropped(db):
    submit(db, obs(recent(), estimator={"feature": "-70", "coef": 2, "note": "x"}))
    row = admin(db, "select estimator from public.observations")
    assert row[0] == {"feature": "-70", "coef": 2, "intercept": 0}
    assert call(db, "select public.get_rooms()")["rooms"][0]["estimated_people"] == 20  # 10 × 2


def test_invalid_estimator_is_rejected(db):
    for bad in ({"feature": "-50", "coef": 1}, {"feature": "-60"}, {"feature": "-60", "coef": "1"}, "x"):
        with pytest.raises(psycopg.Error, match="estimator"):
            submit(db, obs(recent(), estimator=bad))
    assert admin(db, "select count(*) from public.observations")[0] == 0


def test_history_uses_each_observations_estimator(db):
    token2 = admin(db, "select private.add_node('r1-02', 'r1')")[0]
    t = recent()
    # ノード1は自分の式（-60 × 0.5 → 1）、ノード2は式なし（既定 -80 × 1 → 30）。平均 15.5
    submit(db, obs(t, estimator={"feature": "-60", "coef": 0.5}))
    submit(db, {**obs(t, c80=30), "node_id": "r1-02"}, token=token2)
    # 式を変えても、過去の点はそのときの式のまま
    submit(db, obs(t - timedelta(minutes=1), estimator={"feature": "-80", "coef": 1}))
    h = call(db, "select public.get_room_history('r1', %s, %s)", (t - timedelta(hours=1), t + timedelta(hours=1)))
    assert [p["estimated_people"] for p in h["points"]] == [20, 15.5]


# ---- 部屋の切り替え（20261017010000_selectable_rooms.sql）----

def test_rest_rooms_exist_with_floor(db):
    rooms = {r["id"]: r for r in call(db, "select public.get_rooms()")["rooms"]}
    assert rooms["rest-3f"]["name"] == "3F休憩室" and rooms["rest-3f"]["floor"] == "3F"
    assert rooms["rest-2f"]["name"] == "2F休憩室" and rooms["rest-2f"]["floor"] == "2F"


def test_room_id_in_payload_moves_node(db):
    t = recent()
    assert submit(db, obs(t, room_id="rest-2f"))["created"] == 1
    assert admin(db, "select room_id from public.observations")[0] == "rest-2f"
    assert admin(db, "select room_id from public.nodes where id = 'r1-01'")[0] == "rest-2f"
    rooms = {r["id"]: r for r in call(db, "select public.get_rooms()")["rooms"]}
    assert rooms["rest-2f"]["estimated_people"] == 20
    assert [n["id"] for n in rooms["rest-2f"]["nodes"]] == ["r1-01"]
    assert rooms["r1"]["nodes"] == [] and rooms["r1"]["level"] is None


def test_room_id_omitted_uses_current_room(db):
    submit(db, obs(recent() - timedelta(minutes=1), room_id="rest-3f"))
    submit(db, obs(recent()))
    assert admin(db, "select array_agg(room_id order by observed_at) from public.observations")[0] == ["rest-3f", "rest-3f"]


def test_resend_of_older_window_does_not_move_node_back(db):
    t = recent()
    submit(db, obs(t, room_id="rest-2f"))
    # 3F にいたころの古い窓が遅れて届いても、ノードは 2F のまま
    submit(db, obs(t - timedelta(minutes=5), room_id="rest-3f"))
    assert admin(db, "select room_id from public.nodes where id = 'r1-01'")[0] == "rest-2f"


def test_unknown_or_inactive_room_is_rejected(db):
    with pytest.raises(psycopg.Error, match="部屋"):
        submit(db, obs(recent(), room_id="rest-9f"))
    admin(db, "select private.set_room('rest-2f', '2F休憩室', '2F', 2, false)")
    with pytest.raises(psycopg.Error, match="部屋"):
        submit(db, obs(recent(), room_id="rest-2f"))
    assert "rest-2f" not in [r["id"] for r in call(db, "select public.get_rooms()")["rooms"]]


def test_get_rooms_filter(db):
    rooms = call(db, "select public.get_rooms(%s)", ("rest-3f",))["rooms"]
    assert [r["id"] for r in rooms] == ["rest-3f"]
    assert call(db, "select public.get_rooms(%s)", ("nope",))["rooms"] == []


def test_inactive_room_history_still_readable(db):
    t = recent()
    submit(db, obs(t))
    admin(db, "select private.set_room('r1', '教室1', null, 0, false)")
    h = call(db, "select public.get_room_history('r1', %s, %s)", (t - timedelta(hours=1), t + timedelta(hours=1)))
    assert len(h["points"]) == 1


# ---- ノードの監視（20261017020000_node_monitoring.sql）----

def test_diag_is_stored(db):
    submit(db, obs(recent(), diag={"screen_on": False, "queued": 3, "no_result_restarts": 2}))
    assert admin(db, "select diag from public.observations")[0] == {"screen_on": False, "queued": 3, "no_result_restarts": 2}
    submit(db, obs(recent() - timedelta(minutes=1), diag="x"))
    assert admin(db, "select count(*) from public.observations where diag is null")[0] == 1


def test_node_report_text(db):
    admin(db, "select private.set_room('rest-3f', '3F休憩室', '3F', 1)")
    token2 = admin(db, "select private.add_node('n-2', 'rest-3f')")[0]
    now = datetime.now(JST).replace(second=30, microsecond=0)
    base = now.replace(second=0) - timedelta(minutes=11)
    # r1-01: 10窓のうち8窓、最後の窓に画面 OFF
    for i in range(8):
        submit(db, obs(base + timedelta(minutes=i), room_id="rest-3f",
                       diag={"screen_on": i < 7, "queued": 0, "no_result_restarts": i}))
    # n-2: 窓なし・最終受信は1時間前（停止）
    submit(db, {**obs(base - timedelta(hours=1), room_id="rest-3f"), "node_id": "n-2"}, token=token2)
    admin(db, "update public.nodes set last_seen_at = now() - interval '1 hour' where id = 'n-2'")
    text = admin(db, "select private.node_report_text(%s)", (now,))[0]
    lines = text.splitlines()
    assert lines[0].startswith("**ノード状況**")
    assert "🔴 **n-2**｜3F休憩室" in text and "**停止**" in text
    assert "🟢 **r1-01**｜3F休憩室｜電池 100%（充電中）" in text
    assert "窓 8/10（**欠け 2**）" in text
    assert "画面 **OFF**｜未送信 0｜無受信による再起動 7回" in text
    assert "窓 0/10（**欠け 10**）" in text


def test_send_node_report_without_webhook_does_nothing(db):
    admin(db, "select private.send_node_report()")
