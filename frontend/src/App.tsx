import { Navigate, Route, Routes } from "react-router-dom";

import Navbar from "./components/Layout/Navbar";
import ProtectedRoute from "./components/Layout/ProtectedRoute";
import AuditLog from "./pages/AuditLog/AuditLog";
import Connectors from "./pages/Connectors/Connectors";
import Dashboard from "./pages/Dashboard/Dashboard";
import FraudAlerts from "./pages/FraudAlerts/FraudAlerts";
import Login from "./pages/Login/Login";
import Reconciliation from "./pages/Reconciliation/Reconciliation";
import Register from "./pages/Register/Register";
import Reports from "./pages/Reports/Reports";
import TransactionExplorer from "./pages/TransactionExplorer/TransactionExplorer";

export default function App() {
  return (
    <div className="min-h-screen bg-slate-50">
      <Navbar />
      <main className="mx-auto max-w-7xl p-6">
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/register" element={<Register />} />
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
              <ProtectedRoute roles={["ADMIN", "ANALYST"]}>
                <Reconciliation />
              </ProtectedRoute>
            }
          />
          <Route
            path="/fraud"
            element={
              <ProtectedRoute roles={["ADMIN", "ANALYST"]}>
                <FraudAlerts />
              </ProtectedRoute>
            }
          />
          <Route
            path="/reports"
            element={
              <ProtectedRoute>
                <Reports />
              </ProtectedRoute>
            }
          />
          {/* /connectors/callback is the OAuth redirect target; the Connectors
              page completes the code exchange when it sees ?code= in the URL. */}
          <Route
            path="/connectors"
            element={
              <ProtectedRoute roles={["ADMIN", "ANALYST"]}>
                <Connectors />
              </ProtectedRoute>
            }
          />
          <Route
            path="/connectors/callback"
            element={
              <ProtectedRoute roles={["ADMIN", "ANALYST"]}>
                <Connectors />
              </ProtectedRoute>
            }
          />
          <Route
            path="/audit"
            element={
              <ProtectedRoute roles={["ADMIN"]}>
                <AuditLog />
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
