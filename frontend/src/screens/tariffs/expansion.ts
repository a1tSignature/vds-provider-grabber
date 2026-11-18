import type { TariffSearchItemDto } from "../../api/types";
import { serializeUrlState } from "../../lib/urlState";
import type { TariffFilterFieldsSchema, TariffTableFilters } from "./filters";

/**
 * The per-provider expansion request: every active filter the table carries (serialized through
 * the shared field schema, which excludes `page`/`sort`/`size` by construction, so the table's
 * own paging and ordering can never leak into it), plus the fixed parameters that turn it into a
 * request for one provider's other priced tariffs.
 */
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

/** The expansion lists the provider's other tariffs: the headline is excluded by id, wherever it falls. */
export function selectOtherTariffs(items: TariffSearchItemDto[], headlineTariffId: number): TariffSearchItemDto[] {
  return items.filter((item) => item.tariff.id !== headlineTariffId);
}

/** `matchingTariffCount` includes the headline; the "ещё N" counter does not. */
export function otherTariffCount(matchingTariffCount: number): number {
  return matchingTariffCount - 1;
}

/** "ещё N тарифов" with the Russian plural form N requires (1 тариф, 2 тарифа, 5 тарифов, 21 тариф). */
export function otherTariffsLabel(count: number): string {
  const lastTwo = count % 100;
  const last = count % 10;
  const noun =
    lastTwo >= 11 && lastTwo <= 14 ? "тарифов" : last === 1 ? "тариф" : last >= 2 && last <= 4 ? "тарифа" : "тарифов";
  return `ещё ${count} ${noun}`;
}

/** The expansion request caps at 100 rows; past that the block discloses it is showing only the first page. */
export function showsOnlyFirstPage(totalElements: number): boolean {
  return totalElements > 100;
}
