import type { PriceHistoryItemDto } from "../../api/types";
import { compareDecimalStrings } from "../../lib/decimalCompare";

export type HistoryMarker = "up" | "down" | undefined;

/**
 * Returns markers in input order, newest first. Compare with the next older row in the same billing period.
 * Leave the row unmarked if that row is missing or uses another currency.
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

/** The API uses an exclusive upper bound, so include the selected day by sending the following UTC midnight. */
export function endDateInputToIsoZ(value: string): string | undefined {
  if (value === "") return undefined;
  const nextDay = new Date(`${value}T00:00:00Z`);
  nextDay.setUTCDate(nextDay.getUTCDate() + 1);
  return `${nextDay.toISOString().slice(0, 10)}T00:00:00Z`;
}
