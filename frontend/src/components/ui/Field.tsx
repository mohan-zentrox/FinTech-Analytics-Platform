import type { ReactNode } from "react";

/** Labelled form control wrapper, so every filter row on every page looks the same. */
export default function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="block text-sm">
      <span className="mb-1 block font-medium text-slate-700">{label}</span>
      {children}
    </label>
  );
}
