import type { PropsWithChildren } from "react";
import { Navigate } from "react-router-dom";

import type { Role } from "../../store/authStore";
import { useAuthStore } from "../../store/authStore";

/**
 * Redirects to /login when there is no authenticated session, and to the
 * dashboard when the session's role is not permitted on this route.
 *
 * Client-side gating only - every endpoint is independently enforced by
 * @PreAuthorize on the backend. This exists so a viewer does not navigate into a
 * screen that can only show them 403s.
 */
export default function ProtectedRoute({
  children,
  roles,
}: PropsWithChildren<{ roles?: Role[] }>) {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const role = useAuthStore((s) => s.role);

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  if (roles && (role === null || !roles.includes(role))) {
    return <Navigate to="/dashboard" replace />;
  }

  return <>{children}</>;
}
