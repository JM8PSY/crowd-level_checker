"""設定。環境変数か server/.env から読む"""
from datetime import timedelta, timezone

from pydantic_settings import BaseSettings, SettingsConfigDict

# 表示・「今日」の判定に使うタイムゾーン（日本時間固定。Windows で tzdata を不要にするため固定オフセット）
JST = timezone(timedelta(hours=9), "JST")

# 最終受信からこの時間を超えたノードは offline
OFFLINE_AFTER = timedelta(minutes=3)


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str = "sqlite:///./crowd.db"
    # 空なら管理用エンドポイントは使えない（503）
    admin_token: str = ""


settings = Settings()
