import { create } from "zustand";

export type Role = "ADMIN" | "ANALYST" | "VIEWER";

export interface AuthState {
  token: string | null;
  username: string | null;
  role: Role | null;
  isAuthenticated: boolean;
  login: (token: string, username: string, role: Role) => void;
  logout: () => void;
}

const STORAGE_KEY = "ledger.auth";

function loadPersisted(): Pick<AuthState, "token" | "username" | "role"> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return { token: null, username: null, role: null };
    const parsed = JSON.parse(raw);
    return { token: parsed.token ?? null, username: parsed.username ?? null, role: parsed.role ?? null };
  } catch {
    return { token: null, username: null, role: null };
  }
}

export const useAuthStore = create<AuthState>((set) => ({
  ...loadPersisted(),
  isAuthenticated: loadPersisted().token !== null,

  login: (token, username, role) => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ token, username, role }));
    set({ token, username, role, isAuthenticated: true });
  },

  logout: () => {
    localStorage.removeItem(STORAGE_KEY);
    set({ token: null, username: null, role: null, isAuthenticated: false });
  },
}));
