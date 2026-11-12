/**
 * `<input type="datetime-local" step="1">` holds `YYYY-MM-DDTHH:mm[:ss]` with no zone. The UI shows
 * every timestamp in UTC, so the input is read as UTC too.
 */
export function isoToDateTimeInput(iso: string | undefined): string {
  return iso === undefined ? "" : iso.slice(0, 19);
}

/**
 * The input value as an instant with an explicit `Z`, which the API requires. Loaded values are
 * whole seconds, so with `step="1"` an untouched field round-trips unchanged.
 */
export function dateTimeInputToIsoZ(value: string): string | undefined {
  const trimmed = value.trim();
  if (trimmed === "") return undefined;
  return trimmed.length === 16 ? `${trimmed}:00Z` : `${trimmed}Z`;
}
