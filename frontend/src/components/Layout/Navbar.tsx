import { NavLink, useNavigate } from "react-router-dom";

import { useAuthStore } from "../../store/authStore";

const linkClass = ({ isActive }: { isActive: boolean }) =>
  `px-3 py-2 rounded-md text-sm font-medium ${
    isActive ? "bg-brand-600 text-white" : "text-slate-600 hover:bg-slate-100"
  }`;

export default function Navbar() {
  const { username, role, logout, isAuthenticated } = useAuthStore();
  const navigate = useNavigate();

  if (!isAuthenticated) return null;

  function handleLogout() {
    logout();
    navigate("/login");
  }

  return (
    <nav className="flex items-center justify-between border-b border-slate-200 bg-white px-6 py-3">
      <div className="flex items-center gap-6">
        <span className="text-lg font-semibold text-brand-700">Project Ledger</span>
        <NavLink to="/dashboard" className={linkClass}>
          Dashboard
        </NavLink>
        <NavLink to="/transactions" className={linkClass}>
          Transactions
        </NavLink>
        <NavLink to="/reconciliation" className={linkClass}>
          Reconciliation
        </NavLink>
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
