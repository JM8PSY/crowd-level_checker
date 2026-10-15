"""FastAPI アプリ。API と静的フロント（/）を配信する

起動: cd server && uvicorn app.main:app --reload
"""
import hmac
from contextlib import asynccontextmanager
from datetime import datetime, time, timedelta
from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException, Query
from fastapi.staticfiles import StaticFiles
from sqlalchemy.orm import Session

from .config import JST, settings
from .db import GroundTruth, Room, get_session, init_db, utcnow
from .schemas import GroundTruthIn, GroundTruthOut, ObservationAck, ObservationIn, to_jst, to_utc_naive
from .service import find_node_by_token, ingest, room_history, room_summaries

STATIC_DIR = Path(__file__).resolve().parent.parent / "static"


@asynccontextmanager
async def lifespan(_app: FastAPI):
    init_db()
    yield


app = FastAPI(title="BLE教室混雑度 API", version="0.1.0", lifespan=lifespan)


def bearer_token(authorization: str | None) -> str:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "Authorization: Bearer <token> が必要です")
    return authorization.removeprefix("Bearer ").strip()


def require_admin(authorization: str | None = Header(default=None)) -> None:
    if not settings.admin_token:
        raise HTTPException(503, "サーバに ADMIN_TOKEN が設定されていません")
    if not hmac.compare_digest(bearer_token(authorization), settings.admin_token):
        raise HTTPException(403, "管理トークンが違います")


@app.post("/api/v1/observations", response_model=ObservationAck)
def post_observation(
    obs: ObservationIn,
    authorization: str | None = Header(default=None),
    session: Session = Depends(get_session),
):
    node = find_node_by_token(session, bearer_token(authorization))
    if node is None:
        raise HTTPException(401, "ノードトークンが無効です")
    if node.id != obs.node_id:
        raise HTTPException(403, f"トークンはノード {node.id} のものです（node_id={obs.node_id}）")
    if obs.partial:
        # 部分的な窓は保存しない。再送させないよう 200 で返す
        return ObservationAck(status="ignored_partial", node_id=node.id, observed_at=obs.observed_at)
    status = ingest(session, node, obs)
    return ObservationAck(status=status, node_id=node.id, observed_at=obs.observed_at)


@app.post("/api/v1/ground-truth", response_model=GroundTruthOut, dependencies=[Depends(require_admin)])
def post_ground_truth(gt: GroundTruthIn, session: Session = Depends(get_session)):
    if session.get(Room, gt.room_id) is None:
        raise HTTPException(404, f"教室 {gt.room_id} がありません")
    row = GroundTruth(
        room_id=gt.room_id,
        observed_at=to_utc_naive(gt.observed_at) if gt.observed_at else utcnow(),
        people=gt.people,
        note=gt.note,
    )
    session.add(row)
    session.commit()
    return GroundTruthOut(
        id=row.id, room_id=row.room_id, observed_at=to_jst(row.observed_at), people=row.people, note=row.note
    )


@app.get("/api/v1/rooms")
def get_rooms(session: Session = Depends(get_session)):
    return {"now": to_jst(utcnow()), "rooms": room_summaries(session)}


@app.get("/api/v1/rooms/{room_id}/history")
def get_history(
    room_id: str,
    from_: datetime | None = Query(default=None, alias="from"),
    to: datetime | None = None,
    session: Session = Depends(get_session),
):
    if session.get(Room, room_id) is None:
        raise HTTPException(404, f"教室 {room_id} がありません")
    # 既定は「今日（日本時間）の 0:00 から明日 0:00 まで」
    today = datetime.now(JST).date()
    start = from_ or datetime.combine(today, time.min, tzinfo=JST)
    end = to or (datetime.combine(today, time.min, tzinfo=JST) + timedelta(days=1))
    return room_history(session, room_id, to_utc_naive(start), to_utc_naive(end))


# API より後にマウントする（/api が静的ファイルに隠れないように）
app.mount("/", StaticFiles(directory=STATIC_DIR, html=True), name="static")
