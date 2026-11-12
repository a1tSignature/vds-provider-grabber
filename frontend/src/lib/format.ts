/**
 * Money, FX rates and coordinates travel as decimal strings end to end: rounding either through
 * a JS float would silently corrupt a price. The only transformation allowed here is trimming
 * trailing zeros after the decimal point, done with pure string operations.
 */
export function formatMoney(raw: string | null | undefined): string {
  if (raw === null || raw === undefined) return "—";
  if (!raw.includes(".")) return raw;
  const trimmed = raw.replace(/0+$/, "").replace(/\.$/, "");
  return trimmed === "" ? "0" : trimmed;
}

function pad(value: number): string {
  return value < 10 ? `0${value}` : String(value);
}

function datePart(date: Date): string {
  return `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())}`;
}

/** `YYYY-MM-DD HH:mm UTC`, from an ISO-8601 instant. */
export function formatDateTime(iso: string): string {
  const date = new Date(iso);
  return `${datePart(date)} ${pad(date.getUTCHours())}:${pad(date.getUTCMinutes())} UTC`;
}

/** `YYYY-MM-DD`, from an ISO-8601 instant. */
export function formatDate(iso: string): string {
  return datePart(new Date(iso));
}
