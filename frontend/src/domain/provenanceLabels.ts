/**
 * Whitelisted for bare backend enum literals: field/row origins, enrichment outcomes and legal field
 * names are wire vocabularies with no dictionary entry, so the UI decides on them here and nowhere else.
 */

export type OriginMarker = "presumed" | "auto" | null;

/** Only a presumed value asks for review; a confirmed automatic value or row is merely tagged. */
export function originMarker(origin: string | undefined): OriginMarker {
  if (origin === "AUTO_PRESUMED") return "presumed";
  if (origin === "AUTO_CONFIRMED" || origin === "AUTO") return "auto";
  return null;
}

export function isAutoFieldOrigin(origin: string | undefined): boolean {
  return origin === "AUTO_PRESUMED" || origin === "AUTO_CONFIRMED";
}

export function isManualOrigin(origin: string | undefined): boolean {
  return origin === "MANUAL";
}

export const LEGAL_FIELD = {
  legalEntityName: "LEGAL_ENTITY_NAME",
  registrationCountry: "REGISTRATION_COUNTRY",
} as const;

/**
 * Origin for one legal field on a human `PUT …/legal-info`. The server reads an absent origin as
 * `MANUAL` whenever the field has a value, so an untouched automatic value must carry its origin
 * back explicitly or saving the form would claim it.
 */
export function legalOriginForWrite(
  sentValue: string | undefined,
  loadedValue: string | undefined,
  loadedOrigin: string | undefined,
): string | undefined {
  if (sentValue !== loadedValue) return "MANUAL";
  return loadedOrigin;
}

const OUTCOME_TEXT: Record<string, string> = {
  SUCCESS: "данные собраны",
  NOTHING_FOUND: "сайт просмотрен, данных не найдено",
  BLOCKED: "сайт недоступен для автоматического сбора (защита от ботов)",
  UNREACHABLE: "сайт не открылся (недоступен или не отвечает)",
  ROBOTS_DISALLOWED: "сайт запрещает автоматический сбор (robots.txt)",
  FAILED: "ошибка при сборе",
  TIMEOUT: "не уложились во время",
  DEFERRED: "отложено до следующего запуска",
  NOT_DUE: "ещё рано повторять",
  SKIPPED: "пропущено",
  NO_PROVIDER: "провайдер не найден",
  DISABLED: "автосбор выключен",
};

/** The raw value for an outcome not listed, so a new enum constant degrades instead of disappearing. */
export function enrichmentOutcomeText(outcome: string): string {
  return OUTCOME_TEXT[outcome] ?? outcome;
}
