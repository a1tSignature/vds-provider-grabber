/**
 * Whitelisted for bare backend enum literals (with `api/types.ts` and `paymentMethods.tsx`):
 * `SystemStatus` has no dictionary entry a generic `DictionarySelect` could read instead, because
 * it is a relationship to a provider, not a provider-supplied fact.
 */
const LABELS: Record<string, string> = {
  CANDIDATE: "кандидат",
  REJECTED: "отклонён",
};

/**
 * `null` for the normal, unremarkable relationship that gets no badge at all; the raw value for
 * anything else not in [LABELS], so an added enum constant degrades instead of disappearing.
 */
export function systemStatusLabel(systemStatus: string): string | null {
  if (systemStatus === "CONFIGURED") return null;
  return LABELS[systemStatus] ?? systemStatus;
}
