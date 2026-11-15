/**
 * Type mirrors of `src/main/kotlin/org/a1tsign/vds/api/dto/*.kt`. Money, FX rates and
 * coordinates stay decimal strings end to end -- never `number` -- so nothing here can round
 * them through a JS float.
 *
 * One of the two modules allowed a bare enum-literal string (`MONTHLY`): the backend's default
 * billing period, used by read screens that need "the" current price rather than every period.
 */
export const MONTHLY = "MONTHLY";

export interface LoginResponse {
  token: string;
  expiresAt: string;
  username: string;
  roles: string[];
}

export interface DictionariesDto {
  paymentMethods: string[];
  billingPeriods: string[];
  diskTypes: string[];
  virtualizationTypes: string[];
  providerStatuses: string[];
  systemStatuses: string[];
  confidenceLevels: string[];
  legalDataSources: string[];
  priceDataSources: string[];
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface ProviderDto {
  id: number;
  slug: string;
  name: string;
  websiteUrl: string;
  description?: string;
  status: string;
  systemStatus: string;
  createdAt: string;
  updatedAt: string;
}

export interface ProviderSummaryDto {
  id: number;
  slug: string;
  name: string;
  websiteUrl: string;
  status: string;
  systemStatus: string;
  registrationCountry?: string;
  legalEntityName?: string;
  legalEntityNameOrigin?: string;
  registrationCountryOrigin?: string;
  paymentMethods: string[];
  locationCountryCodes: string[];
  activeTariffCount: number;
  minMonthlyPriceUsd?: string;
  /** The cheapest current ANNUAL price divided by 12; both minimums count only active, in-stock tariffs. */
  minAnnualPriceUsdPerMonth?: string;
  /** ISO-8601; absent when the provider was never attempted. */
  enrichmentLastAttemptAt?: string;
}

export interface LegalInfoDto {
  providerId: number;
  legalEntityName?: string;
  registrationCountry?: string;
  registrationNumber?: string;
  legalAddress?: string;
  dataSource: string;
  confidence: string;
  sourceUrl?: string;
  verifiedAt?: string;
  updatedAt: string;
  /** Absent: nobody claimed the field, automation may fill it. */
  legalEntityNameOrigin?: string;
  registrationCountryOrigin?: string;
}

export interface ProviderPaymentMethodDto {
  method: string;
  note?: string;
  origin: string;
}

export interface LocationDto {
  id: number;
  providerId: number;
  countryCode: string;
  region?: string;
  city?: string;
  datacenterName?: string;
  latitude?: string;
  longitude?: string;
  createdAt: string;
  updatedAt: string;
  origin: string;
}

export interface TariffDto {
  id: number;
  providerId: number;
  externalId?: string;
  name: string;
  url?: string;
  vcpuCount: number;
  cpuModel?: string;
  ramMb: number;
  diskGb: number;
  diskType: string;
  bandwidthMbps?: number;
  /** Absent means unmetered, never `0`. */
  trafficLimitGb?: number;
  ipv4Count: number;
  ipv6Supported?: boolean;
  virtualization: string;
  isActive: boolean;
  inStock: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface PriceDto {
  id: number;
  tariffId: number;
  billingPeriod: string;
  amount: string;
  currency: string;
  amountUsd?: string;
  usdRate?: string;
  usdRateAt?: string;
  effectiveFrom: string;
  effectiveTo?: string;
  dataSource: string;
  recordedBy?: number;
  createdAt: string;
  lastConfirmedAt: string;
  warnings: string[];
}

export interface TariffWithPricesDto {
  tariff: TariffDto;
  prices: PriceDto[];
}

export interface ProviderDetailDto {
  provider: ProviderDto;
  legalInfo?: LegalInfoDto;
  paymentMethods: ProviderPaymentMethodDto[];
  locations: LocationDto[];
  tariffs: TariffWithPricesDto[];
  /** Absent when enrichment never ran for this provider. */
  enrichment?: ProviderEnrichmentDto;
}

/** Third-party text: render as plain text only. `url` is not guaranteed to be http(s). */
export interface EvidenceItemDto {
  url: string;
  kind: string;
  snippet: string;
}

export interface LegalFindingDto {
  value: string;
  status: string;
  basis: string[];
  alternatives: string[];
  evidence: EvidenceItemDto[];
}

/** For a location `value` is a country code. */
export interface ValueFindingDto {
  value: string;
  confidence: string;
  pages: number;
  evidence: EvidenceItemDto[];
}

/** Every finding of the latest successful enrichment, including ones that were not written. */
export interface EnrichmentEvidenceDto {
  legalEntityName?: LegalFindingDto;
  registrationCountry?: LegalFindingDto;
  paymentMethods: ValueFindingDto[];
  locations: ValueFindingDto[];
}

export interface ProviderEnrichmentDto {
  lastAttemptAt: string;
  lastOutcome: string;
  lastMessage?: string;
  lastPagesFetched: number;
  lastSuccessAt?: string;
  evidence?: EnrichmentEvidenceDto;
}

export interface TariffSearchItemDto {
  tariff: TariffDto;
  provider: ProviderDto;
  currentPrice?: PriceDto;
}

export interface ProviderCheapestTariffDto {
  provider: ProviderDto;
  cheapestTariff: TariffDto;
  cheapestPrice: PriceDto;
  matchingTariffCount: number;
}

export interface TariffDetailDto {
  tariff: TariffDto;
  provider: ProviderDto;
  locations: LocationDto[];
  currentPrices: PriceDto[];
}

export interface PriceHistoryItemDto {
  id: number;
  billingPeriod: string;
  amount: string;
  currency: string;
  amountUsd?: string;
  usdRate?: string;
  usdRateAt?: string;
  effectiveFrom: string;
  effectiveTo?: string;
  dataSource: string;
  lastConfirmedAt: string;
}
