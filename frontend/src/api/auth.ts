import { coreApi } from "./client";
import type { Role } from "../store/authStore";

export interface AuthResponse {
  token: string;
  tokenType: string;
  username: string;
  role: Role;
  expiresInMs: number;
}

export function login(username: string, password: string) {
  return coreApi.post<AuthResponse>("/auth/login", { username, password }).then((r) => r.data);
}

export function register(username: string, email: string, password: string, role: Role) {
  return coreApi.post<AuthResponse>("/auth/register", { username, email, password, role }).then((r) => r.data);
}
