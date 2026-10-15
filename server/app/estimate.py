"""推定人数と混雑段階の計算"""
from dataclasses import dataclass

from sqlalchemy import select
from sqlalchemy.orm import Session

from .db import Calibration

FEATURES = ("-60", "-70", "-80", "-90", "all")

LEVEL_LABELS = {"empty": "空き", "moderate": "やや混雑", "crowded": "混雑"}


@dataclass(frozen=True)
class Params:
    feature: str
    coef: float
    intercept: float
    threshold_moderate: float
    threshold_crowded: float
    # calibrations に行がなく既定値を使っているときは False
    calibrated: bool


# 校正前の既定値。閾値は仮の値なので、校正後に calibrations へ登録して置き換える
DEFAULT_PARAMS = Params(
    feature="-80", coef=1.0, intercept=0.0, threshold_moderate=15, threshold_crowded=30, calibrated=False
)


def params_for(session: Session, room_id: str) -> Params:
    row = session.scalars(
        select(Calibration)
        .where(Calibration.room_id == room_id)
        .order_by(Calibration.created_at.desc(), Calibration.id.desc())
        .limit(1)
    ).first()
    if row is None:
        return DEFAULT_PARAMS
    return Params(row.feature, row.coef, row.intercept, row.threshold_moderate, row.threshold_crowded, True)


def feature_value(counts_by_rssi: dict, feature: str) -> float | None:
    v = counts_by_rssi.get(feature)
    return float(v) if v is not None else None


def estimate_people(feature_val: float, p: Params) -> float:
    return max(0.0, p.coef * feature_val + p.intercept)


def level_of(people: float, p: Params) -> str:
    if people >= p.threshold_crowded:
        return "crowded"
    if people >= p.threshold_moderate:
        return "moderate"
    return "empty"
