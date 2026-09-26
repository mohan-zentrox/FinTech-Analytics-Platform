FROM python:3.11-slim

WORKDIR /app

# curl is needed by the container healthcheck below.
RUN apt-get update     && apt-get install --no-install-recommends -y curl     && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app

RUN useradd --create-home ledger
USER ledger

EXPOSE 8000

# PORT is what Render / Cloud Run / Koyeb inject; 8000 keeps the compose contract.
ENV PORT=8000

HEALTHCHECK --interval=15s --timeout=5s --start-period=15s --retries=5   CMD curl -fsS "http://127.0.0.1:${PORT}/health" > /dev/null || exit 1

CMD ["sh", "-c", "exec uvicorn app.main:app --host 0.0.0.0 --port ${PORT}"]
