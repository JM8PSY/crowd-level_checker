"""API と CLI（取り込み・デモデータ）で共有する処理"""
import hashlib
import secrets
from collections import defaultdict
from datetime import datetime

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from .config import OFFLINE_AFTER
from .db import GroundTruth, Node, Observation, Room, utcnow
from .estimate import LEVEL_LABELS, estimate_people, feature_value, level_of, params_for
from .schemas import ObservationIn, to_jst, to_utc_naive


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def new_token() -> str:
    return secrets.token_urlsafe(24)


def find_node_by_token(session: Session, token: str) -> Node | None:
    return session.scalars(select(Node).where(Node.token_hash == hash_token(token))).first()


def ingest(session: Session, node: Node, obs: ObservationIn, received_at: datetime | None = None) -> str:
    """観測値を1件保存する。同じ (node_id, observed_at) が既にあれば "duplicate" を返す"""
    received_at = received_at or utcnow()
    observed_at = to_utc_naive(obs.observed_at)

    # 最終受信はノードの生死判定に使うので、重複でも更新する
    if node.last_seen_at is None or received_at > node.last_seen_at:
        node.last_seen_at = received_at
        node.battery = obs.battery
        node.charging = obs.charging
        node.app_version = obs.app_version

    exists = session.scalars(
        select(Observation.id).where(Observation.node_id == node.id, Observation.observed_at == observed_at)
    ).first()
    if exists is not None:
        session.commit()
        return "duplicate"

    session.add(
        Observation(
            node_id=node.id,
            room_id=node.room_id,
            observed_at=observed_at,
            received_at=received_at,
            window_sec=obs.window_sec,
            counts_by_rssi=obs.counts_by_rssi,
            counts_by_vendor=obs.counts_by_vendor,
            rssi_stats=obs.rssi_stats.model_dump() if obs.rssi_stats else None,
            adv_count=obs.adv_count,
            battery=obs.battery,
            charging=obs.charging,
            app_version=obs.app_version,
        )
    )
    try:
        session.commit()
    except IntegrityError:  # 同時に同じ窓が届いた場合
        session.rollback()
        return "duplicate"
    return "created"


def node_status(node: Node, now: datetime) -> str:
    if node.last_seen_at is None or now - node.last_seen_at > OFFLINE_AFTER:
        return "offline"
    return "online"


def room_summaries(session: Session, now: datetime | None = None) -> list[dict]:
    """各教室の最新の混雑度とノードの状態"""
    now = now or utcnow()
    result = []
    for room in session.scalars(select(Room).order_by(Room.id)):
        p = params_for(session, room.id)
        nodes = session.scalars(select(Node).where(Node.room_id == room.id).order_by(Node.id)).all()
        node_out = []
        estimates = []
        latest_observed = None
        for n in nodes:
            status = node_status(n, now)
            last = session.scalars(
                select(Observation)
                .where(Observation.node_id == n.id)
                .order_by(Observation.observed_at.desc())
                .limit(1)
            ).first()
            node_out.append(
                {
                    "id": n.id,
                    "status": status,
                    "last_seen_at": to_jst(n.last_seen_at),
                    "last_observed_at": to_jst(last.observed_at) if last else None,
                    "battery": n.battery,
                    "charging": n.charging,
                    "app_version": n.app_version,
                }
            )
            if last is None:
                continue
            if latest_observed is None or last.observed_at > latest_observed:
                latest_observed = last.observed_at
            # 推定には online のノードの最新値だけを使う
            fv = feature_value(last.counts_by_rssi, p.feature)
            if status == "online" and fv is not None:
                estimates.append(estimate_people(fv, p))

        people = sum(estimates) / len(estimates) if estimates else None
        level = level_of(people, p) if people is not None else None
        result.append(
            {
                "id": room.id,
                "name": room.name,
                "level": level,
                "level_label": LEVEL_LABELS.get(level, "不明"),
                "estimated_people": round(people, 1) if people is not None else None,
                "updated_at": to_jst(latest_observed),
                "calibration": {
                    "feature": p.feature,
                    "coef": p.coef,
                    "intercept": p.intercept,
                    "threshold_moderate": p.threshold_moderate,
                    "threshold_crowded": p.threshold_crowded,
                    "calibrated": p.calibrated,
                },
                "nodes": node_out,
            }
        )
    return result


def room_history(session: Session, room_id: str, start_utc: datetime, end_utc: datetime) -> dict:
    """観測時刻ごとの推定人数（複数ノードは平均）と、生の集計値・実人数"""
    p = params_for(session, room_id)
    rows = session.scalars(
        select(Observation)
        .where(Observation.room_id == room_id, Observation.observed_at >= start_utc, Observation.observed_at < end_utc)
        .order_by(Observation.observed_at)
    ).all()

    by_time: dict[datetime, list[Observation]] = defaultdict(list)
    for r in rows:
        by_time[r.observed_at].append(r)

    points = []
    for t, obs in by_time.items():
        counts: dict[str, float] = {}
        for key in ("-60", "-70", "-80", "-90", "all"):
            vals = [o.counts_by_rssi[key] for o in obs if key in o.counts_by_rssi]
            if vals:
                counts[key] = round(sum(vals) / len(vals), 1)
        fv = counts.get(p.feature)
        people = estimate_people(fv, p) if fv is not None else None
        points.append(
            {
                "observed_at": to_jst(t),
                "estimated_people": round(people, 1) if people is not None else None,
                "level": level_of(people, p) if people is not None else None,
                "counts_by_rssi": counts,
                "nodes": len(obs),
            }
        )

    truths = session.scalars(
        select(GroundTruth)
        .where(GroundTruth.room_id == room_id, GroundTruth.observed_at >= start_utc, GroundTruth.observed_at < end_utc)
        .order_by(GroundTruth.observed_at)
    ).all()
    return {
        "room_id": room_id,
        "from": to_jst(start_utc),
        "to": to_jst(end_utc),
        "feature": p.feature,
        "calibrated": p.calibrated,
        "threshold_moderate": p.threshold_moderate,
        "threshold_crowded": p.threshold_crowded,
        "points": points,
        "ground_truth": [
            {"observed_at": to_jst(g.observed_at), "people": g.people, "note": g.note} for g in truths
        ],
    }
