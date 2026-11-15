import { useApiMutation } from "../../api/hooks";
import type { LegalFindingDto, LegalInfoDto, ProviderEnrichmentDto, ValueFindingDto } from "../../api/types";
import { EvidenceList } from "../../components/EvidenceList";
import { OriginBadge } from "../../components/OriginBadge";
import { enrichmentOutcomeText, isAutoFieldOrigin, isManualOrigin } from "../../domain/provenanceLabels";
import { FormStatus } from "../../forms/FormParts";
import { useForm } from "../../forms/useForm";
import { formatDateTime } from "../../lib/format";
import type { LegalFieldsRequest } from "./requests";

const NO_VALUES = {};

export function LegalFieldValue({
  providerId,
  field,
  value,
  origin,
  finding,
  canEdit,
  onChanged,
}: {
  providerId: number;
  field: string;
  value: string | undefined;
  origin: string | undefined;
  finding: LegalFindingDto | undefined;
  canEdit: boolean;
  onChanged: () => void;
}) {
  return (
    <>
      {value ?? "нет данных"} <OriginBadge origin={origin} />
      {canEdit && isAutoFieldOrigin(origin) && (
        <LegalFieldAction providerId={providerId} field={field} action="confirm" label="Подтвердить" onDone={onChanged} />
      )}
      {canEdit && isManualOrigin(origin) && (
        <LegalFieldAction
          providerId={providerId}
          field={field}
          action="release"
          label="Вернуть автоматике"
          onDone={onChanged}
        />
      )}
      {finding !== undefined && <LegalFindingDetails finding={finding} currentValue={value} />}
    </>
  );
}

function LegalFieldAction({
  providerId,
  field,
  action,
  label,
  onDone,
}: {
  providerId: number;
  field: string;
  action: "confirm" | "release";
  label: string;
  onDone: () => void;
}) {
  const form = useForm(NO_VALUES);
  const { mutate } = useApiMutation<LegalFieldsRequest, LegalInfoDto>(
    "POST",
    `/api/v1/providers/${providerId}/legal-info/${action}`,
  );

  function run() {
    void form.submit(async () => {
      await mutate({ fields: [field] });
      onDone();
    });
  }

  return (
    <span className="provenance-action">
      <button type="button" onClick={run} disabled={form.state.submitting}>
        {label}
      </button>
      <FormStatus error={form.state.error} onRetry={() => void form.retry()} />
    </span>
  );
}

function LegalFindingDetails({ finding, currentValue }: { finding: LegalFindingDto; currentValue: string | undefined }) {
  return (
    <details className="provenance-details">
      <summary>Основание</summary>
      {finding.value !== currentValue && <p>Автосбор нашёл: {finding.value}</p>}
      {finding.basis.length > 0 && <p>Признаки: {finding.basis.join("; ")}</p>}
      {finding.alternatives.length > 0 && <p>Другие варианты: {finding.alternatives.join("; ")}</p>}
      <EvidenceList evidence={finding.evidence} />
    </details>
  );
}

export function ValueFindingDetails({ finding }: { finding: ValueFindingDto | undefined }) {
  if (finding === undefined || finding.evidence.length === 0) return null;
  return (
    <details className="provenance-details">
      <summary>Основание</summary>
      {finding.pages > 0 && <p>Упоминаний на страницах сайта: {finding.pages}</p>}
      <EvidenceList evidence={finding.evidence} />
    </details>
  );
}

export function EnrichmentStatusLine({ enrichment }: { enrichment: ProviderEnrichmentDto | undefined }) {
  return (
    <p className="enrichment-status">
      Автосбор с сайта:{" "}
      {enrichment === undefined
        ? "не выполнялся"
        : `${formatDateTime(enrichment.lastAttemptAt)} — ${enrichmentOutcomeText(enrichment.lastOutcome)}`}
    </p>
  );
}
