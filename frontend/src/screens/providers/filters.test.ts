import { describe, expect, it } from "vitest";
import { parseUrlState, serializeUrlState } from "../../lib/urlState";
import { buildProviderFiltersSchema, normalizeCountryCode } from "./filters";

const dictionaries = {
  paymentMethods: ["ALPHA", "BETA", "GAMMA"],
  billingPeriods: [],
  diskTypes: [],
  virtualizationTypes: [],
  providerStatuses: ["FOO", "BAR"],
  systemStatuses: ["CONFIGURED", "CANDIDATE", "REJECTED"],
  confidenceLevels: [],
  legalDataSources: [],
  priceDataSources: [],
};

const schema = buildProviderFiltersSchema(dictionaries);

describe("provider list filter state", () => {
  it("round-trips every field, preserving repeatable filter order", () => {
    const state = {
      q: "ovh",
      status: "FOO" as const,
      registrationCountry: "DE",
      locationCountry: ["US", "DE"],
      paymentMethod: ["BETA", "GAMMA"],
      hasActiveTariffs: true,
      systemStatus: ["CANDIDATE"],
      page: 2,
      size: 50,
      sort: { field: "minMonthlyPriceUsd" as const, descending: true },
    };
    const search = serializeUrlState(schema, state);
    expect(parseUrlState(schema, search)).toEqual(state);
  });

  it("omits every field at its default", () => {
    const search = serializeUrlState(schema, {
      q: "",
      status: undefined,
      registrationCountry: undefined,
      locationCountry: [],
      paymentMethod: [],
      hasActiveTariffs: undefined,
      systemStatus: ["CONFIGURED", "CANDIDATE"],
      page: 0,
      size: 20,
      sort: undefined,
    });
    expect(search).toBe("");
  });

  it("does not double a repeated paymentMethod and keeps first-seen order", () => {
    const state = parseUrlState(schema, "paymentMethod=GAMMA&paymentMethod=ALPHA&paymentMethod=GAMMA");
    expect(state.paymentMethod).toEqual(["GAMMA", "ALPHA"]);
  });

  it("defaults to the public statuses and ignores rejected statuses from old URLs", () => {
    expect(parseUrlState(schema, "").systemStatus).toEqual(["CONFIGURED", "CANDIDATE"]);
    expect(parseUrlState(schema, "systemStatus=REJECTED&systemStatus=CANDIDATE").systemStatus).toEqual(["CANDIDATE"]);
  });

  it("normalizes and de-duplicates multiple location countries", () => {
    const state = parseUrlState(schema, "locationCountry=de&locationCountry=US&locationCountry=DE&locationCountry=usa");
    expect(state.locationCountry).toEqual(["DE", "US"]);
    expect(serializeUrlState(schema, state)).toBe("locationCountry=DE&locationCountry=US");
  });

  it("falls back to defaults for a country code outside the two-letter shape", () => {
    expect(parseUrlState(schema, "registrationCountry=deu").registrationCountry).toBeUndefined();
    expect(parseUrlState(schema, "registrationCountry=de").registrationCountry).toBe("DE");
  });

  it("falls back to the default page size for a value Pagination does not offer", () => {
    expect(parseUrlState(schema, "size=30").size).toBe(20);
  });
});

describe("normalizeCountryCode", () => {
  it("upper-cases a valid two-letter code", () => {
    expect(normalizeCountryCode("de")).toBe("DE");
  });

  it("is undefined for a single letter, not an error -- a partial code while typing is normal", () => {
    expect(normalizeCountryCode("D")).toBeUndefined();
  });

  it("is undefined for the wrong length or non-letters", () => {
    expect(normalizeCountryCode("DEU")).toBeUndefined();
    expect(normalizeCountryCode("D1")).toBeUndefined();
    expect(normalizeCountryCode("")).toBeUndefined();
  });
});
