import { originMarker } from "../domain/provenanceLabels";

const PRESUMED_TITLE = "Значение найдено автоматически по косвенным признакам и требует проверки";
const AUTO_TITLE = "Значение собрано автоматически с сайта провайдера";

export function OriginBadge({ origin, compact = false }: { origin: string | undefined; compact?: boolean }) {
  const marker = originMarker(origin);
  if (marker === null) return null;
  if (marker === "presumed") {
    return (
      <span className="origin-badge origin-presumed" title={PRESUMED_TITLE} aria-label="требует проверки">
        ⚠{compact ? "" : " требует проверки"}
      </span>
    );
  }
  return (
    <span className="origin-badge origin-auto" title={AUTO_TITLE}>
      авто
    </span>
  );
}
