import { describe, expect, it } from "vitest";
import { parseUrlState, serializeUrlState } from "../../lib/urlState";
import { buildTariffTableFiltersSchema, normalizeCountryCode } from "./filters";

const dictionaries = {
  paymentMethods: ["METHOD_A", "METHOD_B"],
  billingPeriods: [],
  diskTypes: ["DISK_A", "DISK_B"],
  virtualizationTypes: [],
  providerStatuses: [],
  systemStatuses: [],
  confidenceLevels: [],
  legalDataSources: [],
  priceDataSources: [],
};

const schema = buildTariffTableFiltersSchema(dictionaries);

describe("tariff table filter state", () => {
  it("round-trips every field", () => {
    const state = {
      country: "DE",
      minVcpu: 2,
      minRamMb: 1024,
      minDiskGb: 20,
      diskType: "DISK_A" as const,
      maxPriceUsd: "9.99",
      paymentMethod: "METHOD_B" as const,
      unmeteredTraffic: true,
      activeOnly: false,
      inStock: true,
      sort: { field: "name" as const, descending: true },
      page: 3,
      size: 50,
    };
    const search = serializeUrlState(schema, state);
    expect(parseUrlState(schema, search)).toEqual(state);
  });

  it("omits every field at its default, including activeOnly=true", () => {
    const search = serializeUrlState(schema, {
      country: undefined,
      minVcpu: 0,
      minRamMb: 0,
      minDiskGb: 0,
      diskType: undefined,
      maxPriceUsd: "",
      paymentMethod: undefined,
      unmeteredTraffic: undefined,
      activeOnly: true,
      inStock: undefined,
      sort: undefined,
      page: 0,
      size: 20,
    });
    expect(search).toBe("");
  });

  it("falls back to no filter for a non-positive minVcpu/minRamMb/minDiskGb", () => {
    const state = parseUrlState(schema, "minVcpu=0&minRamMb=-5");
    expect(state.minVcpu).toBe(0);
    expect(state.minRamMb).toBe(0);
  });

  it("treats only an explicit activeOnly=false as narrowing", () => {
    expect(parseUrlState(schema, "").activeOnly).toBe(true);
    expect(parseUrlState(schema, "activeOnly=false").activeOnly).toBe(false);
    expect(parseUrlState(schema, "activeOnly=true").activeOnly).toBe(true);
  });
});

describe("normalizeCountryCode", () => {
  it("upper-cases a valid two-letter code", () => {
    expect(normalizeCountryCode("de")).toBe("DE");
  });

  it("is undefined for anything other than two letters", () => {
    expect(normalizeCountryCode("D")).toBeUndefined();
    expect(normalizeCountryCode("DEU")).toBeUndefined();
    expect(normalizeCountryCode("")).toBeUndefined();
  });
});
