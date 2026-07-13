export function formatCurrency(amount: number, currency = "USD"): string {
  return new Intl.NumberFormat("en-US", { style: "currency", currency }).format(amount);
}

export function formatDate(isoDate: string): string {
  if (!isoDate) return "";
  const d = new Date(isoDate);
  if (Number.isNaN(d.getTime())) return isoDate;
  // postedDate is a plain (timezone-less) LocalDate from the backend, parsed
  // by the Date constructor as UTC midnight - render in UTC too, so the
  // displayed calendar day never shifts based on the viewer's local timezone.
  return d.toLocaleDateString("en-US", { year: "numeric", month: "short", day: "numeric", timeZone: "UTC" });
}
