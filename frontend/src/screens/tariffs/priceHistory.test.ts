import { describe, expect, it } from "vitest";
import type { PriceHistoryItemDto } from "../../api/types";
import { computeHistoryMarkers, dateInputToIsoZ, endDateInputToIsoZ } from "./priceHistory";

function row(partial: Partial<PriceHistoryItemDto>): PriceHistoryItemDto {
  return {
    id: 0,
    billingPeriod: "MONTHLY",
    amount: "10",
    currency: "USD",
    effectiveFrom: "2026-01-01T00:00:00Z",
    dataSource: "MANUAL",
    lastConfirmedAt: "2026-01-01T00:00:00Z",
    ...partial,
  };
}

describe("computeHistoryMarkers", () => {
  it("marks a rise and a fall against the next-older row of the same period", () => {
    const items = [row({ id: 1, amount: "12" }), row({ id: 2, amount: "10" }), row({ id: 3, amount: "15" })];
    expect(computeHistoryMarkers(items)).toEqual(["up", "down", undefined]);
  });

  it("skips to the next-older row of the same period across interleaved periods", () => {
    const items = [
      row({ id: 1, billingPeriod: "MONTHLY", amount: "12" }),
      row({ id: 2, billingPeriod: "ANNUAL", amount: "100" }),
      row({ id: 3, billingPeriod: "MONTHLY", amount: "10" }),
      row({ id: 4, billingPeriod: "ANNUAL", amount: "90" }),
    ];
    expect(computeHistoryMarkers(items)).toEqual(["up", "up", undefined, undefined]);
  });

  it("leaves a row unmarked when the next-older same-period row changed currency", () => {
    const items = [row({ id: 1, currency: "USD", amount: "10" }), row({ id: 2, currency: "EUR", amount: "20" })];
    expect(computeHistoryMarkers(items)).toEqual([undefined, undefined]);
  });

  it("leaves a single row unmarked", () => {
    expect(computeHistoryMarkers([row({ id: 1 })])).toEqual([undefined]);
  });

  it("leaves an unchanged amount unmarked", () => {
    const items = [row({ id: 1, amount: "10" }), row({ id: 2, amount: "10" })];
    expect(computeHistoryMarkers(items)).toEqual([undefined, undefined]);
  });
});

describe("dateInputToIsoZ", () => {
  it("appends a midnight UTC offset to a date input value", () => {
    expect(dateInputToIsoZ("2026-03-05")).toBe("2026-03-05T00:00:00Z");
  });

  it("is undefined for an empty input", () => {
    expect(dateInputToIsoZ("")).toBeUndefined();
  });
});

describe("endDateInputToIsoZ", () => {
  it("sends the following midnight so the selected day is included", () => {
    expect(endDateInputToIsoZ("2026-03-05")).toBe("2026-03-06T00:00:00Z");
  });

  it("rolls over month and year ends", () => {
    expect(endDateInputToIsoZ("2024-02-29")).toBe("2024-03-01T00:00:00Z");
    expect(endDateInputToIsoZ("2026-12-31")).toBe("2027-01-01T00:00:00Z");
  });

  it("is undefined for an empty input", () => {
    expect(endDateInputToIsoZ("")).toBeUndefined();
  });
});
