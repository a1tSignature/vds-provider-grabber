/** Interpret the zone-less datetime-local input as UTC to match the timestamp display. */
export function isoToDateTimeInput(iso: string | undefined): string {
  return iso === undefined ? "" : iso.slice(0, 19);
}

/** Send an explicit UTC offset as required by the API. Second precision preserves an untouched value on round trip. */
export function dateTimeInputToIsoZ(value: string): string | undefined {
  const trimmed = value.trim();
  if (trimmed === "") return undefined;
  return trimmed.length === 16 ? `${trimmed}:00Z` : `${trimmed}Z`;
}
