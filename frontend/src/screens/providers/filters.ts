import type { DictionariesDto } from "../../api/types";
import {
  booleanField,
  intField,
  optionalEnumField,
  repeatableEnumField,
  sortField,
  stringField,
  type FieldCodec,
} from "../../lib/urlState";

export const PROVIDER_SORT_FIELDS = ["name", "slug", "status", "createdAt", "minMonthlyPriceUsd", "id"] as const;
export type ProviderSortField = (typeof PROVIDER_SORT_FIELDS)[number];

const PAGE_SIZES = [20, 50, 100] as const;

/**
 * Upper-cases and validates a two-letter country code; `undefined` for anything else (including
 * empty), matching the backend's own validation. Shared with the list screen's input handling, so
 * the same rule decides both what the URL accepts and when a keystroke commits.
 */
export function normalizeCountryCode(raw: string): string | undefined {
  const upper = raw.toUpperCase();
  return /^[A-Z]{2}$/.test(upper) ? upper : undefined;
}

function countryCodeField(): FieldCodec<string | undefined> {
  return {
    defaultValue: undefined,
    parse: (params, key) => {
      const raw = params.get(key);
      return raw === null ? undefined : normalizeCountryCode(raw);
    },
    serialize: (value, key, params) => {
      if (value !== undefined) params.set(key, value);
    },
  };
}

function pageSizeField(): FieldCodec<number> {
  return {
    defaultValue: 20,
    parse: (params, key) => {
      const raw = params.get(key);
      if (raw === null) return 20;
      const value = Number.parseInt(raw, 10);
      return (PAGE_SIZES as readonly number[]).includes(value) ? value : 20;
    },
    serialize: (value, key, params) => {
      if (value !== 20) params.set(key, String(value));
    },
  };
}

export function buildProviderFiltersSchema(dictionaries: DictionariesDto) {
  return {
    q: stringField(),
    status: optionalEnumField(dictionaries.providerStatuses),
    registrationCountry: countryCodeField(),
    locationCountry: countryCodeField(),
    paymentMethod: repeatableEnumField(dictionaries.paymentMethods),
    hasActiveTariffs: booleanField(),
    // Repeatable, sent to the API as is; none selected means the backend's default, CONFIGURED only.
    systemStatus: repeatableEnumField(dictionaries.systemStatuses),
    page: intField(0, 0),
    size: pageSizeField(),
    sort: sortField(PROVIDER_SORT_FIELDS),
  };
}

export type ProviderFiltersSchema = ReturnType<typeof buildProviderFiltersSchema>;
