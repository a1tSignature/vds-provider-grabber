import { describe, expect, it } from "vitest";
import type { TariffDto } from "../../api/types";
import {
  buildPriceCloseRequest,
  buildPriceRequest,
  buildTariffLocationsRequest,
  buildTariffRequest,
  priceWriteNotice,
  tariffFormValues,
} from "./requests";

const tariff: TariffDto = {
  id: 11,
  providerId: 7,
  externalId: "ext-1",
  name: "VPS Starter",
  url: "https://hoster.example/vps",
  vcpuCount: 2,
  cpuModel: "EPYC",
  ramMb: 2048,
  diskGb: 40,
  diskType: "DISK_A",
  bandwidthMbps: 1000,
  trafficLimitGb: 2000,
  ipv4Count: 1,
  ipv6Supported: false,
  virtualization: "VIRT_A",
  isActive: false,
  inStock: false,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

const TARIFF_KEYS = [
  "bandwidthMbps",
  "cpuModel",
  "diskGb",
  "diskType",
  "externalId",
  "inStock",
  "ipv4Count",
  "ipv6Supported",
  "isActive",
  "name",
  "ramMb",
  "trafficLimitGb",
  "url",
  "vcpuCount",
  "virtualization",
];

describe("buildTariffRequest", () => {
  it("carries every loaded field with exactly the DTO's keys, keeping isActive/inStock false", () => {
    const body = buildTariffRequest(tariffFormValues(tariff));
    expect(Object.keys(body).sort()).toEqual(TARIFF_KEYS);
    expect(body).toMatchObject({
      vcpuCount: 2,
      ramMb: 2048,
      trafficLimitGb: 2000,
      ipv6Supported: false,
      isActive: false,
      inStock: false,
    });
  });

  it("omits an empty traffic limit (unmetered) instead of sending 0", () => {
    const body = buildTariffRequest({ ...tariffFormValues(tariff), trafficLimitGb: "" });
    expect(body).not.toHaveProperty("trafficLimitGb");
  });

  it("omits blank optional fields and an unspecified IPv6, but always sends isActive/inStock", () => {
    const body = buildTariffRequest({
      ...tariffFormValues(),
      name: "New",
      vcpuCount: "1",
      ramMb: "1024",
      diskGb: "20",
      diskType: "DISK_A",
      virtualization: "VIRT_A",
    });
    expect(body).toEqual({
      name: "New",
      vcpuCount: 1,
      ramMb: 1024,
      diskGb: 20,
      diskType: "DISK_A",
      ipv4Count: 1,
      virtualization: "VIRT_A",
      isActive: true,
      inStock: true,
    });
  });

  it("passes a non-integer through as typed rather than truncating it", () => {
    expect(buildTariffRequest({ ...tariffFormValues(tariff), ramMb: "1.5" }).ramMb).toBe("1.5");
  });
});

describe("buildTariffLocationsRequest", () => {
  it("is a plain sorted id array", () => {
    expect(buildTariffLocationsRequest(new Set([5, 2, 9]))).toEqual([2, 5, 9]);
  });
});

describe("buildPriceRequest", () => {
  it("passes amount through as the typed string and never sends dataSource", () => {
    const body = buildPriceRequest({ billingPeriod: "PERIOD_A", amount: "4.50", currency: "EUR", effectiveFrom: "" });
    expect(body).toEqual({ billingPeriod: "PERIOD_A", amount: "4.50", currency: "EUR" });
    expect(body).not.toHaveProperty("dataSource");
  });

  it("sends effectiveFrom with Z", () => {
    const body = buildPriceRequest({
      billingPeriod: "PERIOD_A",
      amount: "4.50",
      currency: "EUR",
      effectiveFrom: "2026-04-01T00:00",
    });
    expect(body.effectiveFrom).toBe("2026-04-01T00:00:00Z");
  });
});

describe("buildPriceCloseRequest", () => {
  it("is {billingPeriod} alone, or with effectiveTo ending in Z", () => {
    expect(buildPriceCloseRequest({ billingPeriod: "PERIOD_A", effectiveTo: "" })).toEqual({ billingPeriod: "PERIOD_A" });
    expect(buildPriceCloseRequest({ billingPeriod: "PERIOD_A", effectiveTo: "2026-04-01T10:00:30" })).toEqual({
      billingPeriod: "PERIOD_A",
      effectiveTo: "2026-04-01T10:00:30Z",
    });
  });
});

describe("priceWriteNotice", () => {
  it("maps 201 to a new row and 200 to a confirmed unchanged price", () => {
    expect(priceWriteNotice(201)).toBe("новая цена записана");
    expect(priceWriteNotice(200)).toBe("цена не изменилась, отметка «проверено» обновлена");
  });
});
