"""
Runtime configuration for the analytics microservice, sourced from
environment variables (see .env.example at the repo root -
ANALYTICS_DB_* / JWT_SECRET).

This service connects to the SAME Postgres database as core-api, but through
a dedicated read-only role (`ledger_readonly`, created by core-api's
V2__analytics_readonly_role.sql Flyway migration) so a bug here can never
mutate ledger data - see docs/ARCHITECTURE.md.
"""
from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict

# HS256 requires a key of at least this many bytes; core-api enforces the same
# floor in JwtService, and the two must be configured with the same secret.
MIN_JWT_SECRET_BYTES = 32


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    db_host: str = "localhost"
    db_port: int = 5432
    db_name: str = "ledger"
    analytics_db_user: str = "ledger_readonly"
    analytics_db_password: str = "ledger_readonly_password"

    # Full SQLAlchemy URL; wins over the db_* fields above when set. Needed for
    # managed Postgres that requires TLS, e.g.
    # postgresql+psycopg2://user:pw@host/db?sslmode=require
    database_url_override: str = ""

    cors_allow_origins: str = "*"

    # Must match core-api's JWT_SECRET: this service validates the tokens core-api
    # issues rather than having a login of its own.
    jwt_secret: str = "change-this-local-dev-only-secret-please-0123456789abcdef"

    # Only ever set false for local debugging. When false, /analytics/** is an
    # unauthenticated read of ledger data.
    analytics_require_auth: bool = True

    @property
    def database_url(self) -> str:
        if self.database_url_override:
            return self.database_url_override
        return (
            f"postgresql+psycopg2://{self.analytics_db_user}:{self.analytics_db_password}"
            f"@{self.db_host}:{self.db_port}/{self.db_name}"
        )

    @property
    def cors_origins(self) -> list[str]:
        return [o.strip() for o in self.cors_allow_origins.split(",") if o.strip()]


@lru_cache
def get_settings() -> Settings:
    return Settings()
