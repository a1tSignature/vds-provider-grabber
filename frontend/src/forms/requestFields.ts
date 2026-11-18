
/** A trimmed string, or `undefined` for a blank input -- the DTO's `null` default, i.e. "absent". */
export function optionalText(value: string): string | undefined {
  const trimmed = value.trim();
  return trimmed === "" ? undefined : trimmed;
}

/** Pass invalid integers through so the server can reject them without losing the original input. */
export function wireInt(value: string): number | string {
  const trimmed = value.trim();
  return /^-?\d+$/.test(trimmed) ? Number.parseInt(trimmed, 10) : trimmed;
}

export function optionalWireInt(value: string): number | string | undefined {
  return value.trim() === "" ? undefined : wireInt(value);
}

/** Require every request key at the call site. Omit undefined optional values to use DTO defaults. */
export function withoutUndefined<T extends object>(body: {
  [K in keyof T]-?: {} extends Pick<T, K> ? T[K] | undefined : T[K];
}): T {
  const result: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(body)) {
    if (value !== undefined) result[key] = value;
  }
  return result as T;
}
