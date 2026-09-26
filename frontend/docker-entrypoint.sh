#!/bin/sh
# Rewrites the static runtime config from environment variables, so a single
# prebuilt frontend image can be pointed at any core-api / analytics-service
# without rebuilding the bundle.
set -eu

CONFIG_FILE=/usr/share/nginx/html/config.js

cat > "$CONFIG_FILE" <<EOF
window.__LEDGER_CONFIG__ = {
  coreApiBaseUrl: "${CORE_API_BASE_URL:-}",
  analyticsApiBaseUrl: "${ANALYTICS_API_BASE_URL:-}",
};
EOF

echo "Wrote runtime config: coreApiBaseUrl=${CORE_API_BASE_URL:-<build-time default>} analyticsApiBaseUrl=${ANALYTICS_API_BASE_URL:-<build-time default>}"

exec "$@"
