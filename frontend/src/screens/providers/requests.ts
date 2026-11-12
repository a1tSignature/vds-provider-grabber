import type { LegalInfoDto, LocationDto, ProviderDto, ProviderPaymentMethodDto } from "../../api/types";
import { dateTimeInputToIsoZ, isoToDateTimeInput } from "../../forms/dateTime";
import { optionalText, withoutUndefined } from "../../forms/requestFields";

/*
 * Mirrors of the Kotlin write DTOs. The backend rejects any unknown key with a detail-less `400`,
 * so every body is assembled field by field from form state -- never spread from a read DTO.
 */

/** `ProviderWriteRequest` without `systemStatus`, which this UI never sends (absent = unchanged). */
export interface ProviderWriteRequest {
  slug: string;
  name: string;
  websiteUrl: string;
  description?: string;
  status: string;
}

export interface LegalInfoWriteRequest {
  legalEntityName?: string;
  registrationCountry?: string;
  registrationNumber?: string;
  legalAddress?: string;
  dataSource: string;
  confidence: string;
  sourceUrl?: string;
  verifiedAt?: string;
}

export interface PaymentMethodWriteRequest {
  method: string;
  note?: string;
}

export interface LocationWriteRequest {
  countryCode: string;
  region?: string;
  city?: string;
  datacenterName?: string;
  latitude?: string;
  longitude?: string;
}

export interface ProviderFormValues {
  slug: string;
  name: string;
  websiteUrl: string;
  description: string;
  status: string;
}

export const PROVIDER_REQUIRED = ["slug", "name", "websiteUrl", "status"] as const;

export function providerFormValues(provider?: ProviderDto): ProviderFormValues {
  return {
    slug: provider?.slug ?? "",
    name: provider?.name ?? "",
    websiteUrl: provider?.websiteUrl ?? "",
    description: provider?.description ?? "",
    status: provider?.status ?? "",
  };
}

export function buildProviderRequest(values: ProviderFormValues): ProviderWriteRequest {
  return withoutUndefined<ProviderWriteRequest>({
    slug: values.slug.trim(),
    name: values.name.trim(),
    websiteUrl: values.websiteUrl.trim(),
    description: optionalText(values.description),
    status: values.status,
  });
}

export interface LegalInfoFormValues {
  legalEntityName: string;
  registrationCountry: string;
  registrationNumber: string;
  legalAddress: string;
  dataSource: string;
  confidence: string;
  sourceUrl: string;
  verifiedAt: string;
}

export const LEGAL_INFO_REQUIRED = ["dataSource", "confidence"] as const;

export function legalInfoFormValues(legalInfo?: LegalInfoDto): LegalInfoFormValues {
  return {
    legalEntityName: legalInfo?.legalEntityName ?? "",
    registrationCountry: legalInfo?.registrationCountry ?? "",
    registrationNumber: legalInfo?.registrationNumber ?? "",
    legalAddress: legalInfo?.legalAddress ?? "",
    dataSource: legalInfo?.dataSource ?? "",
    confidence: legalInfo?.confidence ?? "",
    sourceUrl: legalInfo?.sourceUrl ?? "",
    verifiedAt: isoToDateTimeInput(legalInfo?.verifiedAt),
  };
}

export function buildLegalInfoRequest(values: LegalInfoFormValues): LegalInfoWriteRequest {
  return withoutUndefined<LegalInfoWriteRequest>({
    legalEntityName: optionalText(values.legalEntityName),
    registrationCountry: optionalText(values.registrationCountry),
    registrationNumber: optionalText(values.registrationNumber),
    legalAddress: optionalText(values.legalAddress),
    dataSource: values.dataSource,
    confidence: values.confidence,
    sourceUrl: optionalText(values.sourceUrl),
    verifiedAt: dateTimeInputToIsoZ(values.verifiedAt),
  });
}

export interface PaymentMethodRow {
  method: string;
  note: string;
}

export function paymentMethodRows(methods: ProviderPaymentMethodDto[]): PaymentMethodRow[] {
  return methods.map((method) => ({ method: method.method, note: method.note ?? "" }));
}

/** The whole set, one entry per method: the `PUT` replaces every method the provider had. */
export function buildPaymentMethodsRequest(rows: PaymentMethodRow[]): PaymentMethodWriteRequest[] {
  return rows.map((row) =>
    withoutUndefined<PaymentMethodWriteRequest>({ method: row.method, note: optionalText(row.note) }),
  );
}

export interface LocationFormValues {
  countryCode: string;
  region: string;
  city: string;
  datacenterName: string;
  latitude: string;
  longitude: string;
}

export const LOCATION_REQUIRED = ["countryCode"] as const;

export function locationFormValues(location?: LocationDto): LocationFormValues {
  return {
    countryCode: location?.countryCode ?? "",
    region: location?.region ?? "",
    city: location?.city ?? "",
    datacenterName: location?.datacenterName ?? "",
    latitude: location?.latitude ?? "",
    longitude: location?.longitude ?? "",
  };
}

/** Coordinates stay the typed decimal strings: a JSON number is a `400`. */
export function buildLocationRequest(values: LocationFormValues): LocationWriteRequest {
  return withoutUndefined<LocationWriteRequest>({
    countryCode: values.countryCode.trim(),
    region: optionalText(values.region),
    city: optionalText(values.city),
    datacenterName: optionalText(values.datacenterName),
    latitude: optionalText(values.latitude),
    longitude: optionalText(values.longitude),
  });
}
