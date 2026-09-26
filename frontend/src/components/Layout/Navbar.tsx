import { NavLink, useNavigate } from "react-router-dom";

import type { Role } from "../../store/authStore";
import { useAuthStore } from "../../store/authStore";

const linkClass = ({ isActive }: { isActive: boolean }) =>
  `px-3 py-2 rounded-md text-sm font-medium ${
    isActive ? "bg-brand-600 text-white" : "text-slate-600 hover:bg-slate-100"
  }`;

/**
 * Nav entries with the roles allowed to see them. This mirrors the @PreAuthorize
 * rules on the backend: hiding a link the caller would only get a 403 from is a
 * usability measure, not the access control itself.
 */
const NAV_ITEMS: { to: string; label: string; roles: Role[] }[] = [
  { to: "/dashboard", label: "Dashboard", roles: ["ADMIN", "ANALYST", "VIEWER"] },
  { to: "/transactions", label: "Transactions", roles: ["ADMIN", "ANALYST", "VIEWER"] },
  { to: "/reconciliation", label: "Reconciliation", roles: ["ADMIN", "ANALYST"] },
  { to: "/fraud", label: "Alerts", roles: ["ADMIN", "ANALYST"] },
  { to: "/reports", label: "Reports", roles: ["ADMIN", "ANALYST", "VIEWER"] },
  { to: "/connectors", label: "Connectors", roles: ["ADMIN", "ANALYST"] },
  { to: "/audit", label: "Audit", roles: ["ADMIN"] },
];

export default function Navbar() {
  const { username, role, logout, isAuthenticated } = useAuthStore();
  const navigate = useNavigate();

  if (!isAuthenticated) return null;

  function handleLogout() {
    logout();
    navigate("/login");
  }

  const visibleItems = NAV_ITEMS.filter((item) => role !== null && item.roles.includes(role));

  return (
    <nav className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-200 bg-white px-6 py-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="mr-4 text-lg font-semibold text-brand-700">Project Ledger</span>
        {visibleItems.map((item) => (
          <NavLink key={item.to} to={item.to} className={linkClass}>
            {item.label}
          </NavLink>
        ))}
      </div>
      <div className="flex items-center gap-4 text-sm text-slate-600">
        <span>
          {username} <span className="text-slate-400">({role})</span>
        </span>
        <button
          onClick={handleLogout}
          className="rounded-md border border-slate-300 px-3 py-1 hover:bg-slate-100"
        >
          Log out
        </button>
      </div>
    </nav>
  );
}
