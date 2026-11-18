import { formatDate } from "../lib/format";

interface PriceDatesProps {
  effectiveFrom: string;
  lastConfirmedAt: string;
}

/** Date display stays neutral regardless of price age. */
export function PriceDates({ effectiveFrom, lastConfirmedAt }: PriceDatesProps) {
  return (
    <span className="price-dates">
      <span title={effectiveFrom}>цена с {formatDate(effectiveFrom)}</span>
      {", "}
      <span title={lastConfirmedAt}>проверено {formatDate(lastConfirmedAt)}</span>
    </span>
  );
}
