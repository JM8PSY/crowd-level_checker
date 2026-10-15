"""API の結合テスト。テストごとに一時ファイルの SQLite を使う"""
from datetime import datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from app import db as dbmod
from app.config import JST, settings
from app.db import Calibration, Node, Room
from app.main import app
from app.schemas import ObservationIn, to_utc_naive
from app.service import hash_token, ingest

NODE_TOKEN = "node-secret"
ADMIN = "admin-secret"


@pytest.fixture()
def client(tmp_path, monkeypatch):
    engine = dbmod.make_engine(f"sqlite:///{tmp_path / 'test.db'}")
    monkeypatch.setattr(dbmod, "engine", engine)
    monkeypatch.setattr(dbmod.SessionLocal, "kw", {**dbmod.SessionLocal.kw, "bind": engine})
    monkeypatch.setattr(settings, "admin_token", ADMIN)
    dbmod.init_db(engine)
    with dbmod.SessionLocal() as s:
        s.add(Room(id="r1", name="教室1"))
        s.flush()  # リレーション未定義なので親を先に書く
        s.add(Node(id="r1-01", room_id="r1", token_hash=hash_token(NODE_TOKEN)))
        s.commit()
    with TestClient(app) as c:
        yield c


def payload(observed_at: datetime, c80: int = 20, **kw) -> dict:
    return {
        "node_id": "r1-01",
        "observed_at": observed_at.isoformat(),
        "window_sec": 60,
        "counts_by_rssi": {"-60": 2, "-70": 10, "-80": c80, "-90": 30, "all": 33},
        "counts_by_vendor": {"apple": 10, "google": 2, "microsoft": 1, "samsung": 1, "other": 4, "none": 5},
        "rssi_stats": {"median": -78, "p10": -91, "p90": -62},
        "battery": 100,
        "charging": True,
        "app_version": "0.1.0",
        **kw,
    }


def post(c, body, token=NODE_TOKEN):
    return c.post("/api/v1/observations", json=body, headers={"Authorization": f"Bearer {token}"})


def recent_minute() -> datetime:
    return datetime.now(JST).replace(second=0, microsecond=0) - timedelta(minutes=1)


def test_post_observation_and_duplicate(client):
    t = recent_minute()
    r = post(client, payload(t))
    assert r.status_code == 200 and r.json()["status"] == "created"
    r = post(client, payload(t))
    assert r.json()["status"] == "duplicate"


def test_auth_errors(client):
    t = recent_minute()
    assert client.post("/api/v1/observations", json=payload(t)).status_code == 401
    assert post(client, payload(t), token="wrong").status_code == 401
    assert post(client, {**payload(t), "node_id": "other"}).status_code == 403


def test_partial_is_ignored(client):
    r = post(client, payload(recent_minute(), partial=True))
    assert r.json()["status"] == "ignored_partial"
    h = client.get("/api/v1/rooms/r1/history").json()
    assert h["points"] == []


def test_rooms_default_estimate(client):
    post(client, payload(recent_minute(), c80=20))
    room = client.get("/api/v1/rooms").json()["rooms"][0]
    # 既定：-80 の端末数 × 1。閾値 15 / 30 なので「やや混雑」
    assert room["estimated_people"] == 20
    assert room["level"] == "moderate"
    assert room["calibration"]["calibrated"] is False
    assert room["nodes"][0]["status"] == "online"


def test_rooms_uses_calibration(client):
    with dbmod.SessionLocal() as s:
        s.add(Calibration(room_id="r1", feature="-70", coef=2, intercept=1, threshold_moderate=10, threshold_crowded=20))
        s.commit()
    post(client, payload(recent_minute()))
    room = client.get("/api/v1/rooms").json()["rooms"][0]
    assert room["estimated_people"] == 21  # 2 × 10 + 1
    assert room["level"] == "crowded"


def test_offline_node_has_no_estimate(client):
    old = datetime.now(JST) - timedelta(minutes=10)
    with dbmod.SessionLocal() as s:
        node = s.get(Node, "r1-01")
        ingest(s, node, ObservationIn.model_validate(payload(old)), received_at=to_utc_naive(old))
    room = client.get("/api/v1/rooms").json()["rooms"][0]
    assert room["nodes"][0]["status"] == "offline"
    assert room["level"] is None


def test_history_and_ground_truth(client):
    t = recent_minute()
    post(client, payload(t))
    body = {"room_id": "r1", "people": 18, "observed_at": t.isoformat()}
    assert client.post("/api/v1/ground-truth", json=body).status_code == 401
    assert client.post("/api/v1/ground-truth", json=body, headers={"Authorization": "Bearer x"}).status_code == 403
    r = client.post("/api/v1/ground-truth", json=body, headers={"Authorization": f"Bearer {ADMIN}"})
    assert r.status_code == 200

    h = client.get("/api/v1/rooms/r1/history", params={
        "from": (t - timedelta(hours=1)).isoformat(), "to": (t + timedelta(hours=1)).isoformat(),
    }).json()
    assert len(h["points"]) == 1
    assert h["points"][0]["estimated_people"] == 20
    assert datetime.fromisoformat(h["points"][0]["observed_at"]) == t
    assert h["ground_truth"][0]["people"] == 18


def test_static_index(client):
    r = client.get("/")
    assert r.status_code == 200 and "教室の混雑度" in r.text
