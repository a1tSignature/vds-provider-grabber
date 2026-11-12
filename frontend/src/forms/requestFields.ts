/** Pure helpers request builders share: they turn form strings into the wire shape, nothing more. */

/** A trimmed string, or `undefined` for a blank input -- the DTO's `null` default, i.e. "absent". */
export function optionalText(value: string): string | undefined {
  const trimmed = value.trim();
  return trimmed === "" ? undefined : trimmed;
}

/**
 * An integer field. A value that is not a plain integer is passed through as typed, so the
 * backend rejects it rather than the client silently truncating `1.5` to `1`.
 */
export function wireInt(value: string): number | string {
  const trimmed = value.trim();
  return /^-?\d+$/.test(trimmed) ? Number.parseInt(trimmed, 10) : trimmed;
}

export function optionalWireInt(value: string): number | string | undefined {
  return value.trim() === "" ? undefined : wireInt(value);
}

/**
 * Builds a request body of exactly `T`'s keys: every key must be listed (`-?`), only optional ones
 * may be `undefined`, and those are dropped, because an omitted optional key means the DTO default.
 */
export function withoutUndefined<T extends object>(body: {
  [K in keyof T]-?: {} extends Pick<T, K> ? T[K] | undefined : T[K];
}): T {
  const result: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(body)) {
    if (value !== undefined) result[key] = value;
  }
  return result as T;
}
