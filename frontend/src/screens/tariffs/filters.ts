import type { DictionariesDto } from "../../api/types";
import {
  booleanField,
  intField,
  optionalEnumField,
  type parseUrlState,
  sortField,
  stringField,
  type FieldCodec,
} from "../../lib/urlState";

export const TARIFF_SORT_FIELDS = ["priceUsd", "name"] as const;
export type TariffSortField = (typeof TARIFF_SORT_FIELDS)[number];

const PAGE_SIZES = [20, 50, 100] as const;

/** Upper-cases and validates a two-letter country code; `undefined` for anything else, including empty. */
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

/** `activeOnly` defaults to `true` server-side, so only an explicit `false` ever needs to appear in the URL. */
function activeOnlyField(): FieldCodec<boolean> {
  return {
    defaultValue: true,
    parse: (params, key) => params.get(key) !== "false",
    serialize: (value, key, params) => {
      if (!value) params.set(key, "false");
    },
  };
}

/**
 * The filters `/tariffs/by-provider` (the table) and the per-provider expansion request share --
 * everything except page, sort and size, which the expansion request fixes on its own.
 */
export function buildTariffFilterFieldsSchema(dictionaries: DictionariesDto) {
  return {
    country: countryCodeField(),
    minVcpu: intField(0, 1),
    minRamMb: intField(0, 1),
    minDiskGb: intField(0, 1),
    diskType: optionalEnumField(dictionaries.diskTypes),
    maxPriceUsd: stringField(),
    paymentMethod: optionalEnumField(dictionaries.paymentMethods),
    unmeteredTraffic: booleanField(),
    activeOnly: activeOnlyField(),
    inStock: booleanField(),
  };
}

export type TariffFilterFieldsSchema = ReturnType<typeof buildTariffFilterFieldsSchema>;

export function buildTariffTableFiltersSchema(dictionaries: DictionariesDto) {
  return {
    ...buildTariffFilterFieldsSchema(dictionaries),
    sort: sortField(TARIFF_SORT_FIELDS),
    page: intField(0, 0),
    size: pageSizeField(),
  };
}

export type TariffTableFiltersSchema = ReturnType<typeof buildTariffTableFiltersSchema>;
export type TariffTableFilters = ReturnType<typeof parseUrlState<TariffTableFiltersSchema>>;
