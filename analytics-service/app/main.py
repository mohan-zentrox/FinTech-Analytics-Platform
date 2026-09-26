"""
Project Ledger - Analytics microservice (FastAPI + Pandas).

Reads transactions from the same Postgres database as core-api through a
dedicated read-only role and exposes pandas-computed analytics for the
frontend dashboards (Recharts). See docs/ARCHITECTURE.md.

Every /analytics/** route requires the same HS256 bearer token core-api issues
(app/core/security.py); /health is deliberately open so a platform health check
does not need a credential.
"""
import logging

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.core.config import get_settings
from app.routers import anomalies, cash_flow, kpis

log = logging.getLogger(__name__)

app = FastAPI(
    title="Project Ledger - Analytics Service",
    description="Pandas-powered analytics over the shared ledger database (read-only).",
    version="0.2.0",
)

settings = get_settings()

if not settings.analytics_require_auth:
    log.warning(
        "ANALYTICS_REQUIRE_AUTH is false: /analytics/** is serving ledger data "
        "without authentication. Never do this outside local debugging."
    )

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    # False, not True: auth is a bearer token, never a cookie, and a browser
    # rejects `Access-Control-Allow-Origin: *` outright when credentials are
    # allowed - so the previous allow_credentials=True silently broke the
    # default wildcard CORS_ALLOW_ORIGINS.
    allow_credentials=False,
    allow_methods=["GET", "OPTIONS"],
    # Authorization must be permitted now that the routes require a bearer token.
    allow_headers=["Authorization", "Content-Type"],
)

app.include_router(cash_flow.router)
app.include_router(kpis.router)
app.include_router(anomalies.router)


@app.get("/health", tags=["ops"])
def health():
    """Liveness probe. Unauthenticated by design, and reveals nothing about the ledger."""
    return {"status": "ok", "authRequired": settings.analytics_require_auth}
