import { describe, expect, it } from "vitest";

import { formatBytes, formatDateTime } from "./format";

describe("formatBytes", () => {
  it("renders bytes, kilobytes and megabytes", () => {
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(20480)).toBe("20.0 KB");
    expect(formatBytes(5 * 1024 * 1024)).toBe("5.0 MB");
  });

  it("returns a dash for a missing size", () => {
    expect(formatBytes(null)).toBe("-");
    expect(formatBytes(undefined)).toBe("-");
  });

  it("renders zero as bytes rather than a dash", () => {
    // 0 is falsy; an `if (!bytes)` check here would wrongly report "-".
    expect(formatBytes(0)).toBe("0 B");
  });
});

describe("formatDateTime", () => {
  it("formats an ISO timestamp", () => {
    expect(formatDateTime("2026-06-02T10:30:00Z")).toMatch(/Jun 2, 2026/);
  });

  it("returns an empty string for missing input", () => {
    expect(formatDateTime(null)).toBe("");
    expect(formatDateTime(undefined)).toBe("");
    expect(formatDateTime("")).toBe("");
  });

  it("falls back to the raw string when unparseable", () => {
    expect(formatDateTime("not-a-timestamp")).toBe("not-a-timestamp");
  });
});
