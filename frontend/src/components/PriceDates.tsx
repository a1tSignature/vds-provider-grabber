import { formatDate } from "../lib/format";

interface PriceDatesProps {
  effectiveFrom: string;
  lastConfirmedAt: string;
}

/** Neutral: no colour, icon or label derived from age -- that judgement belongs to the viewer. */
export function PriceDates({ effectiveFrom, lastConfirmedAt }: PriceDatesProps) {
  return (
    <span className="price-dates">
      <span title={effectiveFrom}>цена с {formatDate(effectiveFrom)}</span>
      {", "}
      <span title={lastConfirmedAt}>проверено {formatDate(lastConfirmedAt)}</span>
    </span>
  );
}
