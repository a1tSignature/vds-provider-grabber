export function ProviderStatusBadge({ status }: { status: string }) {
  if (status !== "ACTIVE") return <>{status}</>;
  return <span className="provider-status-badge">Активный</span>;
}
