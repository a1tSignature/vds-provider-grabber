import { useEffect, useState } from "react";

/**
 * One URL query parameter, keyed by its own name so a codec can read every occurrence (needed for
 * a repeatable parameter) rather than just the first. `serialize` appends 0+ entries to `params`;
 * appending nothing is how a default value is omitted from the URL.
 *
 * `parse`/`serialize` are declared with method syntax, not as arrow-typed properties: TypeScript
 * checks method parameters bivariantly, which is what lets `FieldCodec<string>` stand in for
 * `FieldCodec<unknown>` in the heterogeneous schema object below -- the strict (contravariant)
 * check a property-typed function would get here is a false positive, since nothing ever calls a
 * codec with another codec's value.
 */
export interface FieldCodec<T> {
  defaultValue: T;
  parse(params: URLSearchParams, key: string): T;
  serialize(value: T, key: string, params: URLSearchParams): void;
}

export function stringField(defaultValue = ""): FieldCodec<string> {
  return {
    defaultValue,
    parse(params, key) {
      return params.get(key) ?? defaultValue;
    },
    serialize(value, key, params) {
      if (value !== defaultValue && value !== "") params.set(key, value);
    },
  };
}

export function enumField<T extends string>(allowed: readonly T[], defaultValue: T): FieldCodec<T> {
  return {
    defaultValue,
    parse(params, key) {
      const raw = params.get(key);
      return raw !== null && (allowed as readonly string[]).includes(raw) ? (raw as T) : defaultValue;
    },
    serialize(value, key, params) {
      if (value !== defaultValue) params.set(key, value);
    },
  };
}

export function optionalEnumField<T extends string>(allowed: readonly T[]): FieldCodec<T | undefined> {
  return {
    defaultValue: undefined,
    parse(params, key) {
      const raw = params.get(key);
      return raw !== null && (allowed as readonly string[]).includes(raw) ? (raw as T) : undefined;
    },
    serialize(value, key, params) {
      if (value !== undefined) params.set(key, value);
    },
  };
}

/** Preserves occurrence order; a value repeated in the URL is kept once. */
export function repeatableEnumField<T extends string>(allowed: readonly T[]): FieldCodec<T[]> {
  return {
    defaultValue: [],
    parse(params, key) {
      const seen = new Set<T>();
      const result: T[] = [];
      for (const raw of params.getAll(key)) {
        if ((allowed as readonly string[]).includes(raw) && !seen.has(raw as T)) {
          seen.add(raw as T);
          result.push(raw as T);
        }
      }
      return result;
    },
    serialize(value, key, params) {
      for (const item of value) params.append(key, item);
    },
  };
}

export function intField(defaultValue: number, min?: number, max?: number): FieldCodec<number> {
  return {
    defaultValue,
    parse(params, key) {
      const raw = params.get(key);
      if (raw === null) return defaultValue;
      const value = Number.parseInt(raw, 10);
      if (Number.isNaN(value)) return defaultValue;
      if (min !== undefined && value < min) return defaultValue;
      if (max !== undefined && value > max) return defaultValue;
      return value;
    },
    serialize(value, key, params) {
      if (value !== defaultValue) params.set(key, String(value));
    },
  };
}

export function booleanField(): FieldCodec<boolean | undefined> {
  return {
    defaultValue: undefined,
    parse(params, key) {
      const raw = params.get(key);
      return raw === "true" ? true : raw === "false" ? false : undefined;
    },
    serialize(value, key, params) {
      if (value !== undefined) params.set(key, String(value));
    },
  };
}

export interface SortValue<F extends string> {
  field: F;
  descending: boolean;
}

export function sortField<F extends string>(allowedFields: readonly F[]): FieldCodec<SortValue<F> | undefined> {
  return {
    defaultValue: undefined,
    parse(params, key) {
      const raw = params.get(key);
      if (raw === null) return undefined;
      const [field, direction] = raw.split(",");
      if (field === undefined || !(allowedFields as readonly string[]).includes(field)) return undefined;
      if (direction !== undefined && direction !== "asc" && direction !== "desc") return undefined;
      return { field: field as F, descending: direction === "desc" };
    },
    serialize(value, key, params) {
      if (value !== undefined) params.set(key, `${value.field},${value.descending ? "desc" : "asc"}`);
    },
  };
}

type SchemaOf<S> = { [K in keyof S]: S[K] extends FieldCodec<infer T> ? T : never };

/** Parses every field in `schema` out of `search` (with or without its leading `?`). */
export function parseUrlState<S extends Record<string, FieldCodec<unknown>>>(schema: S, search: string): SchemaOf<S> {
  const params = new URLSearchParams(search);
  const result = {} as SchemaOf<S>;
  for (const key of Object.keys(schema) as Array<keyof S>) {
    result[key] = schema[key]!.parse(params, key as string) as SchemaOf<S>[typeof key];
  }
  return result;
}

/** Serializes `state`, omitting any field whose codec says it is at its default. */
export function serializeUrlState<S extends Record<string, FieldCodec<unknown>>>(schema: S, state: SchemaOf<S>): string {
  const params = new URLSearchParams();
  for (const key of Object.keys(schema) as Array<keyof S>) {
    schema[key]!.serialize(state[key], key as string, params);
  }
  return params.toString();
}

/** Delays reacting to a fast-changing value (a text input) until it stops changing for `delayMs`. */
export function useDebouncedValue<T>(value: T, delayMs = 300): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}
