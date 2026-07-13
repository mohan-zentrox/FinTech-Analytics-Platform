"""
Runtime configuration for the analytics microservice, sourced from
environment variables (see .env.example at the repo root -
ANALYTICS_DB_* / DATABASE_URL).

This service connects to the SAME Postgres database as core-api, but through
a dedicated read-only role (`ledger_readonly`, created by
core-api's V1__init.sql Flyway migration) so a bug here can never mutate
ledger data - see docs/ARCHITECTURE.md.
"""
from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    db_host: str = "localhost"
    db_port: int = 5432
    db_name: str = "ledger"
    analytics_db_user: str = "ledger_readonly"
    analytics_db_password: str = "ledger_readonly_password"

    cors_allow_origins: str = "*"

    @property
    def database_url(self) -> str:
        return (
            f"postgresql+psycopg2://{self.analytics_db_user}:{self.analytics_db_password}"
            f"@{self.db_host}:{self.db_port}/{self.db_name}"
        )


@lru_cache
def get_settings() -> Settings:
    return Settings()
