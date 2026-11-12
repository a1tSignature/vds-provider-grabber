import { describe, expect, it } from "vitest";
import { formatDate, formatDateTime, formatMoney, websiteHost } from "./format";

describe("formatMoney", () => {
  it("renders an em dash for an absent value, never 0", () => {
    expect(formatMoney(null)).toBe("—");
    expect(formatMoney(undefined)).toBe("—");
  });

  it("trims trailing zeros", () => {
    expect(formatMoney("4.5000")).toBe("4.5");
    expect(formatMoney("100.00")).toBe("100");
    expect(formatMoney("100.50")).toBe("100.5");
  });

  it("leaves an integer-looking value untouched", () => {
    expect(formatMoney("100")).toBe("100");
  });

  it("keeps a real zero amount as 0, not an em dash", () => {
    expect(formatMoney("0.00")).toBe("0");
  });
});

describe("timestamp formatting", () => {
  it("formats the full UTC timestamp", () => {
    expect(formatDateTime("2026-03-05T07:08:00Z")).toBe("2026-03-05 07:08 UTC");
  });

  it("formats the date-only variant", () => {
    expect(formatDate("2026-03-05T07:08:00Z")).toBe("2026-03-05");
  });
});

describe("websiteHost", () => {
  it("keeps the host only, without a leading www", () => {
    expect(websiteHost("https://www.hostzilla.example/vps?x=1")).toBe("hostzilla.example");
    expect(websiteHost("http://cloud.example:8443/")).toBe("cloud.example:8443");
  });

  it("returns an unparsable value unchanged", () => {
    expect(websiteHost("not a url")).toBe("not a url");
  });
});
