"""API の入出力スキーマ"""
from datetime import datetime, timezone

from pydantic import BaseModel, ConfigDict, Field

from .config import JST


class RssiStats(BaseModel):
    median: float | None = None
    p10: float | None = None
    p90: float | None = None


class ObservationIn(BaseModel):
    # partial、devices などの余分なキーは無視する（devices は受け取っても保存しない）
    model_config = ConfigDict(extra="ignore")

    node_id: str
    observed_at: datetime
    window_sec: int = Field(gt=0)
    counts_by_rssi: dict[str, int]
    counts_by_vendor: dict[str, int]
    rssi_stats: RssiStats | None = None
    adv_count: int | None = None
    battery: int | None = None
    charging: bool | None = None
    app_version: str | None = None
    partial: bool | None = None


class ObservationAck(BaseModel):
    status: str  # "created" | "duplicate" | "ignored_partial"
    node_id: str
    observed_at: datetime


class GroundTruthIn(BaseModel):
    room_id: str
    # 省略時はサーバの受信時刻
    observed_at: datetime | None = None
    people: int = Field(ge=0)
    note: str | None = None


class GroundTruthOut(BaseModel):
    id: int
    room_id: str
    observed_at: datetime
    people: int
    note: str | None


def to_jst(dt_utc_naive: datetime | None) -> datetime | None:
    """DB の UTC naive datetime を日本時間の aware datetime にする"""
    if dt_utc_naive is None:
        return None
    return dt_utc_naive.replace(tzinfo=timezone.utc).astimezone(JST)


def to_utc_naive(dt: datetime) -> datetime:
    """入力の datetime を UTC naive にする。タイムゾーンなしは日本時間とみなす"""
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=JST)
    return dt.astimezone(timezone.utc).replace(tzinfo=None)
