import axios from "axios";

import { useAuthStore } from "../store/authStore";

/**
 * Runtime configuration, injected by `public/config.js` (which the Docker image
 * templates from environment variables at container start).
 *
 * Vite bakes `import.meta.env.*` in at build time, which is fine when the host
 * builds the app (Vercel, Netlify, Cloudflare Pages) but means one prebuilt
 * container image cannot be pointed at a different backend. The runtime value
 * wins when present, with the build-time value as the fallback.
 */
declare global {
  interface Window {
    __LEDGER_CONFIG__?: {
      coreApiBaseUrl?: string;
      analyticsApiBaseUrl?: string;
    };
  }
}

function resolveBaseUrl(runtimeValue: string | undefined, buildTimeValue: string | undefined, fallback: string) {
  // A placeholder that envsubst never replaced must not become the base URL.
  if (runtimeValue && !runtimeValue.startsWith("${")) return runtimeValue;
  if (buildTimeValue) return buildTimeValue;
  return fallback;
}

export const coreApiBaseUrl = resolveBaseUrl(
  window.__LEDGER_CONFIG__?.coreApiBaseUrl,
  import.meta.env.VITE_CORE_API_BASE_URL,
  "http://localhost:8080/api"
);

export const analyticsApiBaseUrl = resolveBaseUrl(
  window.__LEDGER_CONFIG__?.analyticsApiBaseUrl,
  import.meta.env.VITE_ANALYTICS_API_BASE_URL,
  "http://localhost:8000/analytics"
);

/** Core API (Spring Boot) - auth, transactions, reconciliation, fraud, reports, connectors, audit. */
export const coreApi = axios.create({ baseURL: coreApiBaseUrl });

/** Analytics microservice (FastAPI) - cash-flow / KPI / anomaly reads. */
export const analyticsApi = axios.create({ baseURL: analyticsApiBaseUrl });

function attachAuthHeader(config: import("axios").InternalAxiosRequestConfig) {
  const token = useAuthStore.getState().token;
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
}

function logoutOn401(error: unknown) {
  const status = (error as { response?: { status?: number } })?.response?.status;
  if (status === 401) {
    useAuthStore.getState().logout();
  }
  return Promise.reject(error);
}

coreApi.interceptors.request.use(attachAuthHeader);
coreApi.interceptors.response.use((response) => response, logoutOn401);

// analytics-service now validates the same JWT as core-api, so its requests need
// the token too - without this every dashboard read would 401.
analyticsApi.interceptors.request.use(attachAuthHeader);
analyticsApi.interceptors.response.use((response) => response, logoutOn401);

/** Extracts a human-readable message from an Axios error, whichever service raised it. */
export function errorMessage(error: unknown, fallback: string): string {
  const response = (error as { response?: { status?: number; data?: unknown } })?.response;
  const data = response?.data as { error?: string; detail?: string; message?: string } | undefined;
  return data?.error ?? data?.detail ?? data?.message ?? fallback;
}
