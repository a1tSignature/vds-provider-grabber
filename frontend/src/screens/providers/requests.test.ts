import { describe, expect, it } from "vitest";
import type { LegalInfoDto, LocationDto, ProviderDto } from "../../api/types";
import {
  buildLegalInfoRequest,
  buildLocationRequest,
  buildPaymentMethodsRequest,
  buildProviderRequest,
  legalInfoFormValues,
  locationFormValues,
  paymentMethodRows,
  providerFormValues,
} from "./requests";

const READ_ONLY_KEYS = ["id", "createdAt", "updatedAt", "providerId", "systemStatus"];

const provider: ProviderDto = {
  id: 7,
  slug: "hoster",
  name: "Hoster",
  websiteUrl: "https://hoster.example",
  description: "VPS",
  status: "STATUS_A",
  systemStatus: "SYSTEM_A",
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-02T00:00:00Z",
};

const legalInfo: LegalInfoDto = {
  providerId: 7,
  legalEntityName: "Hoster LLC",
  registrationCountry: "DE",
  registrationNumber: "HRB 1",
  legalAddress: "Berlin",
  dataSource: "SOURCE_A",
  confidence: "LEVEL_A",
  sourceUrl: "https://registry.example",
  verifiedAt: "2026-02-03T04:05:06Z",
  updatedAt: "2026-02-03T04:05:06Z",
};

const location: LocationDto = {
  id: 3,
  providerId: 7,
  countryCode: "NL",
  region: "North Holland",
  city: "Amsterdam",
  datacenterName: "AMS1",
  latitude: "52.3700",
  longitude: "4.8900",
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

function expectNoReadOnlyKeys(body: object) {
  for (const key of READ_ONLY_KEYS) expect(body).not.toHaveProperty(key);
}

describe("buildProviderRequest", () => {
  it("carries every loaded field and exactly the write DTO's keys, never systemStatus", () => {
    const body = buildProviderRequest(providerFormValues(provider));
    expect(body).toEqual({
      slug: "hoster",
      name: "Hoster",
      websiteUrl: "https://hoster.example",
      description: "VPS",
      status: "STATUS_A",
    });
    expectNoReadOnlyKeys(body);
  });

  it("omits an empty description", () => {
    const body = buildProviderRequest({ ...providerFormValues(provider), description: "  " });
    expect(Object.keys(body).sort()).toEqual(["name", "slug", "status", "websiteUrl"]);
  });
});

describe("buildLegalInfoRequest", () => {
  it("round-trips every loaded field, verifiedAt included", () => {
    const body = buildLegalInfoRequest(legalInfoFormValues(legalInfo));
    expect(body).toEqual({
      legalEntityName: "Hoster LLC",
      registrationCountry: "DE",
      registrationNumber: "HRB 1",
      legalAddress: "Berlin",
      dataSource: "SOURCE_A",
      confidence: "LEVEL_A",
      sourceUrl: "https://registry.example",
      verifiedAt: "2026-02-03T04:05:06Z",
    });
    expectNoReadOnlyKeys(body);
  });

  it("sends an edited verifiedAt with Z and omits empty optional fields", () => {
    const body = buildLegalInfoRequest({
      ...legalInfoFormValues(),
      dataSource: "SOURCE_A",
      confidence: "LEVEL_A",
      verifiedAt: "2026-05-01T12:00",
    });
    expect(body).toEqual({ dataSource: "SOURCE_A", confidence: "LEVEL_A", verifiedAt: "2026-05-01T12:00:00Z" });
  });
});

describe("buildPaymentMethodsRequest", () => {
  it("sends the whole set as an array of {method, note?}", () => {
    const rows = paymentMethodRows([{ method: "METHOD_A", note: "EUR only" }, { method: "METHOD_B" }]);
    expect(buildPaymentMethodsRequest(rows)).toEqual([{ method: "METHOD_A", note: "EUR only" }, { method: "METHOD_B" }]);
  });

  it("sends an empty array to clear the set", () => {
    expect(buildPaymentMethodsRequest([])).toEqual([]);
  });
});

describe("buildLocationRequest", () => {
  it("carries every loaded field with coordinates still strings", () => {
    const body = buildLocationRequest(locationFormValues(location));
    expect(body).toEqual({
      countryCode: "NL",
      region: "North Holland",
      city: "Amsterdam",
      datacenterName: "AMS1",
      latitude: "52.3700",
      longitude: "4.8900",
    });
    expect(typeof body.latitude).toBe("string");
    expectNoReadOnlyKeys(body);
  });

  it("omits empty optional fields", () => {
    expect(buildLocationRequest({ ...locationFormValues(), countryCode: "DE" })).toEqual({ countryCode: "DE" });
  });
});
