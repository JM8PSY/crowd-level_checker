"""DB 接続とテーブル定義（SQLAlchemy 2.0）

日時はすべて UTC の naive datetime で保存する（SQLite はタイムゾーンを持たないため）。
API の入出力で日本時間との変換を行う。
"""
from datetime import datetime, timezone

from sqlalchemy import (
    JSON,
    Boolean,
    DateTime,
    Float,
    ForeignKey,
    Integer,
    String,
    UniqueConstraint,
    create_engine,
    event,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, sessionmaker

from .config import settings


def utcnow() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


class Base(DeclarativeBase):
    pass


class Room(Base):
    __tablename__ = "rooms"

    id: Mapped[str] = mapped_column(String, primary_key=True)
    name: Mapped[str] = mapped_column(String)


class Node(Base):
    __tablename__ = "nodes"

    id: Mapped[str] = mapped_column(String, primary_key=True)
    room_id: Mapped[str] = mapped_column(ForeignKey("rooms.id"), index=True)
    # トークンは SHA-256 の16進文字列で保存し、平文は持たない
    token_hash: Mapped[str] = mapped_column(String, unique=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    # 最終受信時刻（再送分を受けた時刻も含む）。offline 判定に使う
    last_seen_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    battery: Mapped[int | None] = mapped_column(Integer, nullable=True)
    charging: Mapped[bool | None] = mapped_column(Boolean, nullable=True)
    app_version: Mapped[str | None] = mapped_column(String, nullable=True)


class Observation(Base):
    __tablename__ = "observations"
    # 再送で同じ窓が2回届いても1件にする
    __table_args__ = (UniqueConstraint("node_id", "observed_at"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    node_id: Mapped[str] = mapped_column(ForeignKey("nodes.id"), index=True)
    # ノードの所属教室を受信時点で写しておく（ノードを別教室へ移しても過去のデータは元の教室のまま）
    room_id: Mapped[str] = mapped_column(ForeignKey("rooms.id"), index=True)
    observed_at: Mapped[datetime] = mapped_column(DateTime, index=True)
    received_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    window_sec: Mapped[int] = mapped_column(Integer)
    counts_by_rssi: Mapped[dict] = mapped_column(JSON)
    counts_by_vendor: Mapped[dict] = mapped_column(JSON)
    rssi_stats: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    adv_count: Mapped[int | None] = mapped_column(Integer, nullable=True)
    battery: Mapped[int | None] = mapped_column(Integer, nullable=True)
    charging: Mapped[bool | None] = mapped_column(Boolean, nullable=True)
    app_version: Mapped[str | None] = mapped_column(String, nullable=True)


class GroundTruth(Base):
    __tablename__ = "ground_truth"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    room_id: Mapped[str] = mapped_column(ForeignKey("rooms.id"), index=True)
    observed_at: Mapped[datetime] = mapped_column(DateTime, index=True)
    people: Mapped[int] = mapped_column(Integer)
    note: Mapped[str | None] = mapped_column(String, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


class Calibration(Base):
    """教室ごとの推定式と段階の閾値。同じ教室の最新の行を使う"""

    __tablename__ = "calibrations"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    room_id: Mapped[str] = mapped_column(ForeignKey("rooms.id"), index=True)
    # counts_by_rssi のキー（"-60" / "-70" / "-80" / "-90" / "all"）
    feature: Mapped[str] = mapped_column(String)
    coef: Mapped[float] = mapped_column(Float)
    intercept: Mapped[float] = mapped_column(Float)
    # 推定人数がこれ以上なら「やや混雑」
    threshold_moderate: Mapped[float] = mapped_column(Float)
    # 推定人数がこれ以上なら「混雑」
    threshold_crowded: Mapped[float] = mapped_column(Float)
    note: Mapped[str | None] = mapped_column(String, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


def make_engine(url: str):
    engine = create_engine(url, connect_args={"check_same_thread": False} if url.startswith("sqlite") else {})
    if url.startswith("sqlite"):
        @event.listens_for(engine, "connect")
        def _fk_on(dbapi_conn, _):
            dbapi_conn.execute("PRAGMA foreign_keys=ON")
    return engine


engine = make_engine(settings.database_url)
SessionLocal = sessionmaker(bind=engine, expire_on_commit=False)


def init_db(bind=None) -> None:
    Base.metadata.create_all(bind or engine)


def get_session():
    with SessionLocal() as session:
        yield session
