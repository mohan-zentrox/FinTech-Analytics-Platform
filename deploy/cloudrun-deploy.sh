#!/usr/bin/env bash
#
# Google Cloud Run deployment (the always-free path with the most headroom:
# 2M requests + 180k vCPU-seconds + 360k GiB-seconds per month, per account,
# in us-east1 / us-west1 / us-central1 only).
#
# Prerequisites:
#   - gcloud CLI, authenticated: gcloud auth login && gcloud config set project <id>
#   - A free Postgres (Neon or Supabase) and its connection details
#   - Secrets created in Secret Manager (this script reads them, never echoes them)
#
# Usage:
#   export PROJECT_ID=my-project REGION=us-central1
#   ./deploy/cloudrun-deploy.sh
set -euo pipefail

PROJECT_ID="${PROJECT_ID:?set PROJECT_ID}"
# Always-free is limited to these three regions; anything else is billable.
REGION="${REGION:-us-central1}"
case "$REGION" in
  us-east1|us-west1|us-central1) ;;
  *) echo "WARNING: $REGION is outside Cloud Run's always-free regions (us-east1, us-west1, us-central1)." >&2 ;;
esac

CORE_API_IMAGE="${REGION}-docker.pkg.dev/${PROJECT_ID}/ledger/core-api:latest"
ANALYTICS_IMAGE="${REGION}-docker.pkg.dev/${PROJECT_ID}/ledger/analytics-service:latest"

echo "==> Enabling required APIs"
gcloud services enable run.googleapis.com artifactregistry.googleapis.com \
  cloudbuild.googleapis.com secretmanager.googleapis.com --project "$PROJECT_ID"

echo "==> Ensuring the Artifact Registry repository exists"
gcloud artifacts repositories describe ledger --location "$REGION" --project "$PROJECT_ID" >/dev/null 2>&1 \
  || gcloud artifacts repositories create ledger \
       --repository-format=docker --location "$REGION" --project "$PROJECT_ID"

echo "==> Building images with Cloud Build"
gcloud builds submit core-api --tag "$CORE_API_IMAGE" --project "$PROJECT_ID"
gcloud builds submit analytics-service --tag "$ANALYTICS_IMAGE" --project "$PROJECT_ID"

# --min-instances=0 is what keeps this inside the free tier: the container scales to
# zero when idle and only consumes vCPU-seconds while serving a request.
# --cpu-boost shortens the JVM cold start that scaling to zero implies.
echo "==> Deploying core-api"
gcloud run deploy ledger-core-api \
  --image "$CORE_API_IMAGE" \
  --region "$REGION" --project "$PROJECT_ID" \
  --allow-unauthenticated \
  --min-instances=0 --max-instances=2 \
  --memory=512Mi --cpu=1 --cpu-boost \
  --timeout=60s \
  --set-env-vars="DB_POOL_MAX=3,REPORTS_SCHEDULE_ENABLED=false,CONNECTORS_SANDBOX=true,JAVA_OPTS=-XX:MaxRAMPercentage=65 -XX:+UseSerialGC -Xss512k" \
  --set-secrets="DATABASE_URL=ledger-database-url:latest,DB_USER=ledger-db-user:latest,DB_PASSWORD=ledger-db-password:latest,JWT_SECRET=ledger-jwt-secret:latest,CONNECTOR_ENCRYPTION_KEY=ledger-connector-key:latest,ANALYTICS_DB_PASSWORD=ledger-analytics-db-password:latest"

echo "==> Deploying analytics-service"
gcloud run deploy ledger-analytics-service \
  --image "$ANALYTICS_IMAGE" \
  --region "$REGION" --project "$PROJECT_ID" \
  --allow-unauthenticated \
  --min-instances=0 --max-instances=2 \
  --memory=512Mi --cpu=1 \
  --set-env-vars="ANALYTICS_REQUIRE_AUTH=true" \
  --set-secrets="DB_HOST=ledger-db-host:latest,DB_NAME=ledger-db-name:latest,ANALYTICS_DB_USER=ledger-analytics-db-user:latest,ANALYTICS_DB_PASSWORD=ledger-analytics-db-password:latest,JWT_SECRET=ledger-jwt-secret:latest"

CORE_API_URL=$(gcloud run services describe ledger-core-api --region "$REGION" --project "$PROJECT_ID" --format='value(status.url)')
ANALYTICS_URL=$(gcloud run services describe ledger-analytics-service --region "$REGION" --project "$PROJECT_ID" --format='value(status.url)')

echo
echo "core-api:           ${CORE_API_URL}/api"
echo "analytics-service:  ${ANALYTICS_URL}/analytics"
echo
echo "Next steps:"
echo "  1. Set CORS_ALLOW_ORIGINS on ledger-analytics-service to your frontend origin."
echo "  2. Build the frontend with:"
echo "       VITE_CORE_API_BASE_URL=${CORE_API_URL}/api \\"
echo "       VITE_ANALYTICS_API_BASE_URL=${ANALYTICS_URL}/analytics npm run build"
echo "     and deploy frontend/dist to Cloudflare Pages / Vercel / Netlify."
echo "  3. Both services MUST share the same ledger-jwt-secret value - analytics"
echo "     verifies the tokens core-api signs."
