import { describe, expect, it } from "vitest";
import type { ProviderDto, TariffDto, TariffSearchItemDto } from "../../api/types";
import { parseUrlState } from "../../lib/urlState";
import {
  buildExpansionQuery,
  otherTariffCount,
  otherTariffsLabel,
  selectOtherTariffs,
  showsOnlyFirstPage,
} from "./expansion";
import { buildTariffFilterFieldsSchema, buildTariffTableFiltersSchema } from "./filters";

const dictionaries = {
  paymentMethods: ["METHOD_A"],
  billingPeriods: [],
  diskTypes: ["DISK_A"],
  virtualizationTypes: [],
  providerStatuses: [],
  systemStatuses: [],
  confidenceLevels: [],
  legalDataSources: [],
  priceDataSources: [],
};

function tariff(id: number): TariffDto {
  return {
    id,
    providerId: 1,
    name: `tariff ${id}`,
    vcpuCount: 1,
    ramMb: 1024,
    diskGb: 20,
    diskType: "DISK_A",
    ipv4Count: 1,
    virtualization: "VIRT",
    isActive: true,
    inStock: true,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
  };
}

const provider: ProviderDto = {
  id: 1,
  slug: "provider",
  name: "Provider",
  websiteUrl: "https://provider.example",
  status: "STATUS",
  systemStatus: "STATUS",
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

function searchItem(id: number): TariffSearchItemDto {
  return { tariff: tariff(id), provider };
}

describe("buildExpansionQuery", () => {
  it("carries every active filter, adds the fixed parameters, and does not leak table page/sort/size", () => {
    const fieldsSchema = buildTariffFilterFieldsSchema(dictionaries);
    const tableSchema = buildTariffTableFiltersSchema(dictionaries);
    const filters = parseUrlState(
      tableSchema,
      "country=DE&minVcpu=2&minRamMb=2048&minDiskGb=40&diskType=DISK_A&maxPriceUsd=9.99&paymentMethod=METHOD_A" +
        "&unmeteredTraffic=false&activeOnly=false&inStock=true&page=3&sort=name,desc&size=50",
    );

    const search = buildExpansionQuery(fieldsSchema, filters, 42);
    const params = new URLSearchParams(search);

    expect(params.get("country")).toBe("DE");
    expect(params.get("minVcpu")).toBe("2");
    expect(params.get("minRamMb")).toBe("2048");
    expect(params.get("minDiskGb")).toBe("40");
    expect(params.get("diskType")).toBe("DISK_A");
    expect(params.get("maxPriceUsd")).toBe("9.99");
    expect(params.get("paymentMethod")).toBe("METHOD_A");
    expect(params.get("unmeteredTraffic")).toBe("false");
    expect(params.get("activeOnly")).toBe("false");
    expect(params.get("inStock")).toBe("true");
    expect(params.get("providerId")).toBe("42");
    expect(params.get("includeAnnual")).toBe("true");
    expect(params.get("hasPrice")).toBe("true");
    // The fixed sort/size win: the table's own page=3, sort=name,desc and size=50 never reach here.
    expect(params.get("sort")).toBe("priceUsd");
    expect(params.get("size")).toBe("100");
    expect(params.has("page")).toBe(false);
  });
});

describe("selectOtherTariffs", () => {
  it("drops the headline by id even when it is not first", () => {
    const items = [searchItem(5), searchItem(7), searchItem(9)];
    expect(selectOtherTariffs(items, 7).map((item) => item.tariff.id)).toEqual([5, 9]);
  });

  it("keeps everything when the headline id is not present", () => {
    const items = [searchItem(5), searchItem(9)];
    expect(selectOtherTariffs(items, 1).map((item) => item.tariff.id)).toEqual([5, 9]);
  });
});

describe("otherTariffCount", () => {
  it("is matchingTariffCount minus the headline", () => {
    expect(otherTariffCount(4)).toBe(3);
  });

  it("is zero when the headline is the only match, which hides the counter", () => {
    expect(otherTariffCount(1)).toBe(0);
  });
});

describe("otherTariffsLabel", () => {
  it.each([
    [1, "ещё 1 тариф"],
    [2, "ещё 2 тарифа"],
    [4, "ещё 4 тарифа"],
    [5, "ещё 5 тарифов"],
    [11, "ещё 11 тарифов"],
    [14, "ещё 14 тарифов"],
    [21, "ещё 21 тариф"],
    [22, "ещё 22 тарифа"],
    [111, "ещё 111 тарифов"],
  ])("%i → %s", (count, label) => {
    expect(otherTariffsLabel(count)).toBe(label);
  });
});

describe("showsOnlyFirstPage", () => {
  it("is false at or under the expansion page size", () => {
    expect(showsOnlyFirstPage(100)).toBe(false);
    expect(showsOnlyFirstPage(42)).toBe(false);
  });

  it("is true past it", () => {
    expect(showsOnlyFirstPage(101)).toBe(true);
  });
});
