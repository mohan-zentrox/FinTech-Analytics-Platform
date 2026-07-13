import type { PropsWithChildren } from "react";
import { Navigate } from "react-router-dom";

import { useAuthStore } from "../../store/authStore";

/** Redirects to /login when there is no authenticated session. */
export default function ProtectedRoute({ children }: PropsWithChildren) {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  return <>{children}</>;
}
