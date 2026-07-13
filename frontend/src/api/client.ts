import axios from "axios";

import { useAuthStore } from "../store/authStore";

/** Core API (Spring Boot) - auth, transactions, reconciliation, audit logs. */
export const coreApi = axios.create({
  baseURL: import.meta.env.VITE_CORE_API_BASE_URL ?? "http://localhost:8080/api",
});

/** Analytics microservice (FastAPI) - cash-flow / KPI charts. */
export const analyticsApi = axios.create({
  baseURL: import.meta.env.VITE_ANALYTICS_API_BASE_URL ?? "http://localhost:8000/analytics",
});

function attachAuthHeader(config: import("axios").InternalAxiosRequestConfig) {
  const token = useAuthStore.getState().token;
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
}

coreApi.interceptors.request.use(attachAuthHeader);

coreApi.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      useAuthStore.getState().logout();
    }
    return Promise.reject(error);
  }
);
