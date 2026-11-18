import type { TariffDto } from "../../api/types";
import { dateTimeInputToIsoZ } from "../../forms/dateTime";
import { optionalText, optionalWireInt, wireInt, withoutUndefined } from "../../forms/requestFields";

/* Build the write body explicitly. The backend rejects unknown fields with a detail-less 400. See `wireInt` for integer inputs. */

export interface TariffWriteRequest {
  externalId?: string;
  name: string;
  url?: string;
  vcpuCount: number | string;
  cpuModel?: string;
  ramMb: number | string;
  diskGb: number | string;
  diskType: string;
  bandwidthMbps?: number | string;
  /** Absent means unmetered; an empty input is never sent as `0`. */
  trafficLimitGb?: number | string;
  ipv4Count: number | string;
  ipv6Supported?: boolean;
  virtualization: string;
  isActive: boolean;
  inStock: boolean;
}

/** `PriceWriteRequest` without `dataSource`: absent means the manual source, the only one this UI writes. */
export interface PriceWriteRequest {
  billingPeriod: string;
  amount: string;
  currency: string;
  effectiveFrom?: string;
}

export interface PriceCloseRequest {
  billingPeriod: string;
  effectiveTo?: string;
}

export interface TariffFormValues {
  externalId: string;
  name: string;
  url: string;
  vcpuCount: string;
  cpuModel: string;
  ramMb: string;
  diskGb: string;
  diskType: string;
  bandwidthMbps: string;
  trafficLimitGb: string;
  ipv4Count: string;
  /** `""` = not specified, otherwise `"true"`/`"false"`. */
  ipv6Supported: string;
  virtualization: string;
  isActive: boolean;
  inStock: boolean;
}

export const TARIFF_REQUIRED = ["name", "vcpuCount", "ramMb", "diskGb", "diskType", "ipv4Count", "virtualization"] as const;

function intText(value: number | undefined): string {
  return value === undefined ? "" : String(value);
}

export function tariffFormValues(tariff?: TariffDto): TariffFormValues {
  return {
    externalId: tariff?.externalId ?? "",
    name: tariff?.name ?? "",
    url: tariff?.url ?? "",
    vcpuCount: intText(tariff?.vcpuCount),
    cpuModel: tariff?.cpuModel ?? "",
    ramMb: intText(tariff?.ramMb),
    diskGb: intText(tariff?.diskGb),
    diskType: tariff?.diskType ?? "",
    bandwidthMbps: intText(tariff?.bandwidthMbps),
    trafficLimitGb: intText(tariff?.trafficLimitGb),
    ipv4Count: intText(tariff?.ipv4Count ?? 1),
    ipv6Supported: tariff?.ipv6Supported === undefined ? "" : String(tariff.ipv6Supported),
    virtualization: tariff?.virtualization ?? "",
    isActive: tariff?.isActive ?? true,
    inStock: tariff?.inStock ?? true,
  };
}

/** Send both flags explicitly because the API defaults omissions to true, which can reactivate or restock a tariff. */
export function buildTariffRequest(values: TariffFormValues): TariffWriteRequest {
  return withoutUndefined<TariffWriteRequest>({
    externalId: optionalText(values.externalId),
    name: values.name.trim(),
    url: optionalText(values.url),
    vcpuCount: wireInt(values.vcpuCount),
    cpuModel: optionalText(values.cpuModel),
    ramMb: wireInt(values.ramMb),
    diskGb: wireInt(values.diskGb),
    diskType: values.diskType,
    bandwidthMbps: optionalWireInt(values.bandwidthMbps),
    trafficLimitGb: optionalWireInt(values.trafficLimitGb),
    ipv4Count: wireInt(values.ipv4Count),
    ipv6Supported: values.ipv6Supported === "" ? undefined : values.ipv6Supported === "true",
    virtualization: values.virtualization,
    isActive: values.isActive,
    inStock: values.inStock,
  });
}

/** Ids of the provider's own locations; the `PUT` replaces every link the tariff had. */
export function buildTariffLocationsRequest(selected: ReadonlySet<number>): number[] {
  return [...selected].sort((a, b) => a - b);
}

export interface PriceFormValues {
  billingPeriod: string;
  amount: string;
  currency: string;
  effectiveFrom: string;
}

export const PRICE_REQUIRED = ["billingPeriod", "amount", "currency"] as const;

export function buildPriceRequest(values: PriceFormValues): PriceWriteRequest {
  return withoutUndefined<PriceWriteRequest>({
    billingPeriod: values.billingPeriod,
    amount: values.amount.trim(),
    currency: values.currency.trim(),
    effectiveFrom: dateTimeInputToIsoZ(values.effectiveFrom),
  });
}

export interface PriceCloseFormValues {
  billingPeriod: string;
  effectiveTo: string;
}

export const PRICE_CLOSE_REQUIRED = ["billingPeriod"] as const;

export function buildPriceCloseRequest(values: PriceCloseFormValues): PriceCloseRequest {
  return withoutUndefined<PriceCloseRequest>({
    billingPeriod: values.billingPeriod,
    effectiveTo: dateTimeInputToIsoZ(values.effectiveTo),
  });
}

/** A 201 appends a price and closes the previous row. A 200 only advances lastConfirmedAt on a matching price. */
export function priceWriteNotice(status: number): string {
  return status === 201 ? "новая цена записана" : "цена не изменилась, отметка «проверено» обновлена";
}
