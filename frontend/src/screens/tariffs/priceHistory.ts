import type { PriceHistoryItemDto } from "../../api/types";
import { compareDecimalStrings } from "../../lib/decimalCompare";

export type HistoryMarker = "up" | "down" | undefined;

/**
 * One marker per row, aligned to `items` (newest `effectiveFrom` first). A row is compared only
 * against the next-older row of the same billing period: a currency change there, or no such row
 * at all (the oldest row for its period), leaves the row unmarked rather than reaching past it
 * for an older match -- colour is never the only signal, so an unmarked row renders with none.
 */
export function computeHistoryMarkers(items: PriceHistoryItemDto[]): HistoryMarker[] {
  return items.map((current, index) => {
    for (let older = index + 1; older < items.length; older++) {
      const candidate = items[older]!;
      if (candidate.billingPeriod !== current.billingPeriod) continue;
      if (candidate.currency !== current.currency) return undefined;
      const comparison = compareDecimalStrings(current.amount, candidate.amount);
      if (comparison > 0) return "up";
      if (comparison < 0) return "down";
      return undefined;
    }
    return undefined;
  });
}

/** A `<input type="date">` value (`YYYY-MM-DD`) to the explicit-offset instant the API requires. */
export function dateInputToIsoZ(value: string): string | undefined {
  return value === "" ? undefined : `${value}T00:00:00Z`;
}

/**
 * The upper bound for an inclusive "по" date: the backend filters `effectiveFrom < to`, so the
 * selected day is kept by sending the following midnight.
 */
export function endDateInputToIsoZ(value: string): string | undefined {
  if (value === "") return undefined;
  const nextDay = new Date(`${value}T00:00:00Z`);
  nextDay.setUTCDate(nextDay.getUTCDate() + 1);
  return `${nextDay.toISOString().slice(0, 10)}T00:00:00Z`;
}
