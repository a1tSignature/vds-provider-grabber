import { systemStatusLabel } from "../domain/systemStatusLabels";

export function SystemStatusBadge({ systemStatus }: { systemStatus: string }) {
  const label = systemStatusLabel(systemStatus);
  if (label === null) return null;
  return <span className="system-status-badge">{label}</span>;
}
