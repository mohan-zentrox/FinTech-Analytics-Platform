import { describe, expect, it } from "vitest";

import { formatCurrency, formatDate } from "./format";

describe("formatCurrency", () => {
  it("formats a positive USD amount", () => {
    expect(formatCurrency(1234.5)).toBe("$1,234.50");
  });

  it("formats a negative amount with a leading minus sign", () => {
    expect(formatCurrency(-99.9)).toBe("-$99.90");
  });

  it("supports other ISO currency codes", () => {
    expect(formatCurrency(10, "EUR")).toBe("€10.00");
  });
});

describe("formatDate", () => {
  it("formats an ISO date string", () => {
    expect(formatDate("2026-01-15")).toBe("Jan 15, 2026");
  });

  it("returns an empty string for an empty input", () => {
    expect(formatDate("")).toBe("");
  });

  it("falls back to the raw string for unparseable input", () => {
    expect(formatDate("not-a-date")).toBe("not-a-date");
  });
});
