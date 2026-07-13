import { Navigate, Route, Routes } from "react-router-dom";

import Navbar from "./components/Layout/Navbar";
import ProtectedRoute from "./components/Layout/ProtectedRoute";
import Dashboard from "./pages/Dashboard/Dashboard";
import Login from "./pages/Login/Login";
import Reconciliation from "./pages/Reconciliation/Reconciliation";
import TransactionExplorer from "./pages/TransactionExplorer/TransactionExplorer";

export default function App() {
  return (
    <div className="min-h-screen bg-slate-50">
      <Navbar />
      <main className="mx-auto max-w-7xl p-6">
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route
            path="/dashboard"
            element={
              <ProtectedRoute>
                <Dashboard />
              </ProtectedRoute>
            }
          />
          <Route
            path="/transactions"
            element={
              <ProtectedRoute>
                <TransactionExplorer />
              </ProtectedRoute>
            }
          />
          <Route
            path="/reconciliation"
            element={
              <ProtectedRoute>
                <Reconciliation />
              </ProtectedRoute>
            }
          />
          <Route path="/" element={<Navigate to="/dashboard" replace />} />
          <Route path="*" element={<Navigate to="/dashboard" replace />} />
        </Routes>
      </main>
    </div>
  );
}
