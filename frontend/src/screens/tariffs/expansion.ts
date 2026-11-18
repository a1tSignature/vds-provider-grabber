import type { TariffSearchItemDto } from "../../api/types";
import { serializeUrlState } from "../../lib/urlState";
import type { TariffFilterFieldsSchema, TariffTableFilters } from "./filters";

/** Use the shared filters and the expansion's own paging and ordering to fetch other priced tariffs for one provider. */
export function buildExpansionQuery(
  fieldsSchema: TariffFilterFieldsSchema,
  filters: TariffTableFilters,
  providerId: number,
): string {
  const params = new URLSearchParams(serializeUrlState(fieldsSchema, filters));
  params.set("providerId", String(providerId));
  params.set("includeAnnual", "true");
  params.set("hasPrice", "true");
  params.set("sort", "priceUsd");
  params.set("size", "100");
  return params.toString();
}

export function selectOtherTariffs(items: TariffSearchItemDto[], headlineTariffId: number): TariffSearchItemDto[] {
  return items.filter((item) => item.tariff.id !== headlineTariffId);
}

/** `matchingTariffCount` includes the headline; the "ещё N" counter does not. */
export function otherTariffCount(matchingTariffCount: number): number {
  return matchingTariffCount - 1;
}

export function otherTariffsLabel(count: number): string {
  const lastTwo = count % 100;
  const last = count % 10;
  const noun =
    lastTwo >= 11 && lastTwo <= 14 ? "тарифов" : last === 1 ? "тариф" : last >= 2 && last <= 4 ? "тарифа" : "тарифов";
  return `ещё ${count} ${noun}`;
}

export function showsOnlyFirstPage(totalElements: number): boolean {
  return totalElements > 100;
}
