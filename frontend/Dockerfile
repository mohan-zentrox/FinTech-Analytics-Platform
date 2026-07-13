# --- Build stage ---
FROM node:20-alpine AS build
WORKDIR /build

ARG VITE_CORE_API_BASE_URL=http://localhost:8080/api
ARG VITE_ANALYTICS_API_BASE_URL=http://localhost:8000/analytics
ENV VITE_CORE_API_BASE_URL=$VITE_CORE_API_BASE_URL
ENV VITE_ANALYTICS_API_BASE_URL=$VITE_ANALYTICS_API_BASE_URL

COPY package.json ./
RUN npm install
COPY . .
RUN npm run build

# --- Runtime stage: static files served by nginx ---
FROM nginx:1.27-alpine
COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
