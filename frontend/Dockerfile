# --- Build stage ---
FROM node:20-alpine AS build
WORKDIR /build

ARG VITE_CORE_API_BASE_URL=http://localhost:8080/api
ARG VITE_ANALYTICS_API_BASE_URL=http://localhost:8000/analytics
ENV VITE_CORE_API_BASE_URL=$VITE_CORE_API_BASE_URL
ENV VITE_ANALYTICS_API_BASE_URL=$VITE_ANALYTICS_API_BASE_URL

# Copy the lockfile too and use `npm ci`, so the image is built from exactly the
# dependency versions the repo pins rather than whatever resolves at build time.
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# --- Runtime stage: static files served by nginx ---
FROM nginx:1.27-alpine
COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY docker-entrypoint.sh /docker-entrypoint.d/99-ledger-config.sh
RUN chmod +x /docker-entrypoint.d/99-ledger-config.sh

# Optional runtime overrides of the baked-in API URLs; see docker-entrypoint.sh.
ENV CORE_API_BASE_URL=""
ENV ANALYTICS_API_BASE_URL=""

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=5s --start-period=5s --retries=3   CMD wget -qO- http://127.0.0.1/ > /dev/null || exit 1
