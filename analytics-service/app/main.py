"""
Project Ledger - Analytics microservice (FastAPI + Pandas).

Reads transactions from the same Postgres database as core-api through a
dedicated read-only role and exposes pandas-computed analytics for the
frontend dashboards (Recharts). See docs/ARCHITECTURE.md.
"""
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.core.config import get_settings
from app.routers import cash_flow, kpis

app = FastAPI(
    title="Project Ledger - Analytics Service",
    description="Pandas-powered analytics over the shared ledger database (read-only).",
    version="0.1.0",
)

settings = get_settings()
app.add_middleware(
    CORSMiddleware,
    allow_origins=[o.strip() for o in settings.cors_allow_origins.split(",")],
    allow_credentials=True,
    allow_methods=["GET"],
    allow_headers=["*"],
)

app.include_router(cash_flow.router)
app.include_router(kpis.router)


@app.get("/health")
def health():
    return {"status": "ok"}
