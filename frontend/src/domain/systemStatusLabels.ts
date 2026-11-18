/** SystemStatus is a catalog relationship, so the dictionaries API omits it. */
const LABELS: Record<string, string> = {
  CANDIDATE: "кандидат",
  REJECTED: "отклонён",
};

/** Returns null for a relationship with no badge and the raw code for an unknown relationship. */
export function systemStatusLabel(systemStatus: string): string | null {
  if (systemStatus === "CONFIGURED") return null;
  return LABELS[systemStatus] ?? systemStatus;
}

const FILTER_LABELS: Record<string, string> = {
  CONFIGURED: "настроенные",
  CANDIDATE: "кандидаты",
  REJECTED: "отклонённые",
};

const BROWSABLE_SYSTEM_STATUSES = ["CONFIGURED", "CANDIDATE"] as const;

export function browsableSystemStatuses(available: readonly string[]): string[] {
  return BROWSABLE_SYSTEM_STATUSES.filter((status) => available.includes(status));
}

export function systemStatusFilterLabel(systemStatus: string): string {
  return FILTER_LABELS[systemStatus] ?? systemStatus;
}
