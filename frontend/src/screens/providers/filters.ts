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
import { browsableSystemStatuses } from "../../domain/systemStatusLabels";

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

function countryCodesField(): FieldCodec<string[]> {
  return {
    defaultValue: [],
    parse: (params, key) => [...new Set(params.getAll(key).map(normalizeCountryCode).filter((code): code is string => code !== undefined))],
    serialize: (value, key, params) => value.forEach((code) => params.append(key, code)),
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

function systemStatusField(available: readonly string[]): FieldCodec<string[]> {
  const defaultValue = [...available];
  return {
    defaultValue,
    parse(params, key) {
      const raw = params.getAll(key);
      if (raw.length === 0) return [...defaultValue];
      return [...new Set(raw.filter((value) => available.includes(value)))];
    },
    serialize(value, key, params) {
      if (value.length === defaultValue.length && value.every((item, index) => item === defaultValue[index])) return;
      value.forEach((item) => params.append(key, item));
    },
  };
}

export function buildProviderFiltersSchema(dictionaries: DictionariesDto) {
  const systemStatuses = browsableSystemStatuses(dictionaries.systemStatuses);
  return {
    q: stringField(),
    status: optionalEnumField(dictionaries.providerStatuses),
    registrationCountry: countryCodeField(),
    locationCountry: countryCodesField(),
    paymentMethod: repeatableEnumField(dictionaries.paymentMethods),
    hasActiveTariffs: booleanField(),
    systemStatus: systemStatusField(systemStatuses),
    page: intField(0, 0),
    size: pageSizeField(),
    sort: sortField(PROVIDER_SORT_FIELDS),
  };
}

export type ProviderFiltersSchema = ReturnType<typeof buildProviderFiltersSchema>;
