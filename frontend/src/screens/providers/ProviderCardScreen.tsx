import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { useApiMutation, useApiQuery } from "../../api/hooks";
import type { ProviderDetailDto } from "../../api/types";
import { OriginBadge } from "../../components/OriginBadge";
import { PriceDates } from "../../components/PriceDates";
import { ErrorView, Loading } from "../../components/StateViews";
import { SystemStatusBadge } from "../../components/SystemStatusBadge";
import { LEGAL_FIELD, originMarker } from "../../domain/provenanceLabels";
import { DestructiveAction } from "../../forms/DestructiveAction";
import { useCanEdit } from "../../forms/FormParts";
import { formatDateTime, formatMoney } from "../../lib/format";
import { LegalInfoEditor, LocationsEditor, PaymentMethodsEditor } from "./ProviderEditors";
import { EnrichmentStatusLine, LegalFieldValue, ValueFindingDetails } from "./ProvenanceParts";

type Editing = "legal" | "payment" | "locations" | null;

export function ProviderCardScreen() {
  const { id } = useParams<{ id: string }>();
  const { data, error, loading, refetch } = useApiQuery<ProviderDetailDto>(`/api/v1/providers/${id}`);
  const { mutate: deleteProvider } = useApiMutation<void, void>("DELETE", `/api/v1/providers/${id}`);
  const navigate = useNavigate();
  const editor = useCanEdit();
  const [editing, setEditing] = useState<Editing>(null);

  function saved() {
    setEditing(null);
    refetch();
  }

  function editToggle(section: Exclude<Editing, null>) {
    // One editor at a time: opening another would discard what is typed in the open one.
    if (!editor || editing !== null) return null;
    return (
      <button type="button" className="section-edit" onClick={() => setEditing(section)}>
        Изменить
      </button>
    );
  }

  if (loading) return <Loading />;
  if (error !== undefined || data === undefined) return <ErrorView error={error} onRetry={refetch} />;

  const { provider, legalInfo, paymentMethods, locations, tariffs, enrichment } = data;
  const evidence = enrichment?.evidence;

  return (
    <div className="provider-card">
      <p className="eyebrow">КАРТОЧКА ПРОВАЙДЕРА</p>
      <h1>
        {provider.name} <SystemStatusBadge systemStatus={provider.systemStatus} />
      </h1>
      <p className="muted">{provider.slug}</p>
      <p>
        <a href={provider.websiteUrl} target="_blank" rel="noopener noreferrer">
          {provider.websiteUrl}
        </a>
      </p>
      <p>Статус: {provider.status}</p>
      {provider.description !== undefined && <p>{provider.description}</p>}
      <EnrichmentStatusLine enrichment={enrichment} />
      {editor && (
        <div className="editor-actions">
          <Link to={`/providers/${provider.id}/edit`}>Редактировать</Link>
          <Link to={`/providers/${provider.id}/tariffs/new`}>Добавить тариф</Link>
          <DestructiveAction
            label="Удалить провайдера"
            confirmText={`Удалить «${provider.name}»? Это удалит юридическую информацию, способы оплаты, локации, тарифы и всю историю цен.`}
            run={() => deleteProvider()}
            onDone={() => navigate("/providers")}
          />
        </div>
      )}

      <section>
        <h2>Юридическая информация {editToggle("legal")}</h2>
        {editing === "legal" ? (
          <LegalInfoEditor
            providerId={provider.id}
            legalInfo={legalInfo}
            onSaved={saved}
            onCancel={() => setEditing(null)}
          />
        ) : legalInfo === undefined ? (
          <p className="muted">нет данных</p>
        ) : (
          <dl>
            <dt>Юридическое название</dt>
            <dd>
              <LegalFieldValue
                providerId={provider.id}
                field={LEGAL_FIELD.legalEntityName}
                value={legalInfo.legalEntityName}
                origin={legalInfo.legalEntityNameOrigin}
                finding={evidence?.legalEntityName}
                canEdit={editor}
                onChanged={refetch}
              />
            </dd>
            <dt>Страна регистрации</dt>
            <dd>
              <LegalFieldValue
                providerId={provider.id}
                field={LEGAL_FIELD.registrationCountry}
                value={legalInfo.registrationCountry}
                origin={legalInfo.registrationCountryOrigin}
                finding={evidence?.registrationCountry}
                canEdit={editor}
                onChanged={refetch}
              />
            </dd>
            <dt>Регистрационный номер</dt>
            <dd>{legalInfo.registrationNumber ?? "нет данных"}</dd>
            <dt>Юридический адрес</dt>
            <dd>{legalInfo.legalAddress ?? "нет данных"}</dd>
            <dt>Источник данных</dt>
            <dd>{legalInfo.dataSource}</dd>
            <dt>Достоверность</dt>
            <dd>{legalInfo.confidence}</dd>
            <dt>Источник (ссылка)</dt>
            <dd>
              {legalInfo.sourceUrl !== undefined ? (
                <a href={legalInfo.sourceUrl} target="_blank" rel="noopener noreferrer">
                  {legalInfo.sourceUrl}
                </a>
              ) : (
                "нет данных"
              )}
            </dd>
            <dt>Проверено</dt>
            <dd>{legalInfo.verifiedAt !== undefined ? formatDateTime(legalInfo.verifiedAt) : "нет данных"}</dd>
          </dl>
        )}
      </section>

      <section>
        <h2>Способы оплаты {editToggle("payment")}</h2>
        {editing === "payment" ? (
          <PaymentMethodsEditor
            providerId={provider.id}
            paymentMethods={paymentMethods}
            onSaved={saved}
            onCancel={() => setEditing(null)}
          />
        ) : paymentMethods.length === 0 ? (
          <p className="muted">нет данных</p>
        ) : (
          <ul>
            {paymentMethods.map((pm) => (
              <li key={pm.method}>
                {pm.method}
                {pm.note !== undefined && ` — ${pm.note}`} <OriginBadge origin={pm.origin} />
                {originMarker(pm.origin) !== null && (
                  <ValueFindingDetails finding={evidence?.paymentMethods.find((f) => f.value === pm.method)} />
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section>
        <h2>Локации {editToggle("locations")}</h2>
        {editing === "locations" ? (
          <>
            <LocationsEditor providerId={provider.id} locations={locations} onSaved={refetch} />
            <button type="button" onClick={() => setEditing(null)}>
              Готово
            </button>
          </>
        ) : locations.length === 0 ? (
          <p className="muted">нет данных</p>
        ) : (
          <ul>
            {locations.map((location) => (
              <li key={location.id}>
                {location.countryCode}
                {location.region !== undefined && `, ${location.region}`}
                {location.city !== undefined && `, ${location.city}`}
                {location.datacenterName !== undefined && ` (${location.datacenterName})`}
                {location.latitude !== undefined && location.longitude !== undefined && (
                  <span className="muted">
                    {" "}
                    [{location.latitude}, {location.longitude}]
                  </span>
                )}{" "}
                <OriginBadge origin={location.origin} />
                {originMarker(location.origin) !== null && (
                  <ValueFindingDetails
                    finding={evidence?.locations.find((f) => f.value === location.countryCode)}
                  />
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section>
        <h2>Активные тарифы</h2>
        {tariffs.length === 0 ? (
          <p className="muted">нет активных тарифов</p>
        ) : (
          <ul className="tariff-list">
            {tariffs.map(({ tariff, prices }) => (
              <li key={tariff.id}>
                <Link to={`/tariffs/${tariff.id}`}>{tariff.name}</Link>
                <div className="muted">
                  {tariff.vcpuCount} vCPU, {tariff.ramMb} MB RAM, {tariff.diskGb} GB {tariff.diskType},{" "}
                  {tariff.virtualization}, {tariff.ipv4Count} IPv4
                  {tariff.bandwidthMbps !== undefined && `, ${tariff.bandwidthMbps} Мбит/с`}
                  {tariff.trafficLimitGb !== undefined
                    ? `, ${tariff.trafficLimitGb} ГБ трафика`
                    : ", безлимитный трафик"}
                </div>
                <ul className="price-list">
                  {prices.map((price) => (
                    <li key={price.id}>
                      {formatMoney(price.amount)} {price.currency} / {price.billingPeriod} ={" "}
                      {price.amountUsd !== undefined ? `$${formatMoney(price.amountUsd)}` : "—"}
                      <PriceDates effectiveFrom={price.effectiveFrom} lastConfirmedAt={price.lastConfirmedAt} />
                    </li>
                  ))}
                </ul>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
