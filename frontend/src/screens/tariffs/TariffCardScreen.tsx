import { useEffect, useMemo, useRef, useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { useApiMutation, useApiQuery } from "../../api/hooks";
import type { PriceHistoryItemDto, TariffDetailDto } from "../../api/types";
import { PriceDates } from "../../components/PriceDates";
import { ErrorView, Loading } from "../../components/StateViews";
import { SystemStatusBadge } from "../../components/SystemStatusBadge";
import { DictionarySelect } from "../../dictionaries/DictionarySelect";
import { DestructiveAction } from "../../forms/DestructiveAction";
import { useCanEdit } from "../../forms/FormParts";
import { formatDateTime, formatMoney } from "../../lib/format";
import { PriceCloseForm, PriceWriteForm, TariffLocationsEditor } from "./TariffEditors";
import { computeHistoryMarkers, dateInputToIsoZ, endDateInputToIsoZ, type HistoryMarker } from "./priceHistory";

export function TariffCardScreen() {
  const { id } = useParams<{ id: string }>();
  const { data, error, loading, refetch } = useApiQuery<TariffDetailDto>(`/api/v1/tariffs/${id}`);
  const { mutate: deleteTariff } = useApiMutation<void, void>("DELETE", `/api/v1/tariffs/${id}`);
  const navigate = useNavigate();
  const editor = useCanEdit();
  const [editingLocations, setEditingLocations] = useState(false);
  const [historyVersion, setHistoryVersion] = useState(0);

  // A refetch after a write keeps the previous card on screen, so the write forms (and the
  // notice they show) are not unmounted by the loading state.
  const lastData = useRef<TariffDetailDto | undefined>(undefined);
  if (data !== undefined) lastData.current = data;
  const shown = data ?? (loading && lastData.current?.tariff.id === Number.parseInt(id ?? "", 10) ? lastData.current : undefined);

  function refreshAfterWrite() {
    refetch();
    setHistoryVersion((version) => version + 1);
  }

  if (shown === undefined) {
    if (loading) return <Loading />;
    return <ErrorView error={error} onRetry={refetch} />;
  }

  const { tariff, provider, locations, currentPrices } = shown;

  return (
    <div className="tariff-card">
      <h1>{tariff.name}</h1>
      <p>
        <Link to={`/providers/${provider.id}`}>{provider.name}</Link> <SystemStatusBadge systemStatus={provider.systemStatus} />
      </p>
      {tariff.url !== undefined && (
        <p>
          <a href={tariff.url} target="_blank" rel="noopener noreferrer">
            {tariff.url}
          </a>
        </p>
      )}

      {editor && (
        <div className="editor-actions">
          <Link to={`/tariffs/${tariff.id}/edit`}>Редактировать</Link>
          <DestructiveAction
            label="Удалить тариф"
            confirmText={`Удалить тариф «${tariff.name}»? Это удалит тариф и всю его историю цен.`}
            run={() => deleteTariff()}
            onDone={() => navigate(`/providers/${provider.id}`)}
          />
        </div>
      )}

      <section>
        <h2>Характеристики</h2>
        <dl>
          <dt>vCPU</dt>
          <dd>{tariff.vcpuCount}</dd>
          {tariff.cpuModel !== undefined && (
            <>
              <dt>Модель CPU</dt>
              <dd>{tariff.cpuModel}</dd>
            </>
          )}
          <dt>RAM</dt>
          <dd>{tariff.ramMb} MB</dd>
          <dt>Диск</dt>
          <dd>
            {tariff.diskGb} GB {tariff.diskType}
          </dd>
          {tariff.bandwidthMbps !== undefined && (
            <>
              <dt>Канал</dt>
              <dd>{tariff.bandwidthMbps} Мбит/с</dd>
            </>
          )}
          <dt>Трафик</dt>
          <dd>{tariff.trafficLimitGb !== undefined ? `${tariff.trafficLimitGb} ГБ` : "безлимитный"}</dd>
          <dt>IPv4</dt>
          <dd>{tariff.ipv4Count}</dd>
          <dt>IPv6</dt>
          <dd>{tariff.ipv6Supported === undefined ? "нет данных" : tariff.ipv6Supported ? "поддерживается" : "не поддерживается"}</dd>
          <dt>Виртуализация</dt>
          <dd>{tariff.virtualization}</dd>
          <dt>Статус</dt>
          <dd>
            {tariff.isActive ? "активен" : "неактивен"}, {tariff.inStock ? "в наличии" : "нет в наличии"}
          </dd>
        </dl>
      </section>

      <section>
        <h2>
          Локации{" "}
          {editor && !editingLocations && (
            <button type="button" className="section-edit" onClick={() => setEditingLocations(true)}>
              Изменить
            </button>
          )}
        </h2>
        {editingLocations ? (
          <TariffLocationsEditor
            tariffId={tariff.id}
            providerId={provider.id}
            linked={locations}
            onSaved={() => {
              setEditingLocations(false);
              refetch();
            }}
            onCancel={() => setEditingLocations(false)}
          />
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
              </li>
            ))}
          </ul>
        )}
      </section>

      <section>
        <h2>Текущие цены</h2>
        {currentPrices.length === 0 ? (
          <p className="muted">нет данных</p>
        ) : (
          <ul className="price-list">
            {currentPrices.map((price) => (
              <li key={price.id}>
                {price.billingPeriod}: {formatMoney(price.amount)} {price.currency} ={" "}
                {price.amountUsd !== undefined ? `$${formatMoney(price.amountUsd)}` : "—"}
                <PriceDates effectiveFrom={price.effectiveFrom} lastConfirmedAt={price.lastConfirmedAt} />
              </li>
            ))}
          </ul>
        )}
      </section>

      {editor && (
        <section className="price-editors">
          <PriceWriteForm tariffId={tariff.id} onSaved={refreshAfterWrite} />
          <PriceCloseForm tariffId={tariff.id} onSaved={refreshAfterWrite} />
        </section>
      )}

      <PriceHistorySection tariffId={tariff.id} version={historyVersion} />
    </div>
  );
}

/** `version` changes after a price write or close, which refetches the history with its filters kept. */
function PriceHistorySection({ tariffId, version }: { tariffId: number; version: number }) {
  const [billingPeriod, setBillingPeriod] = useState("");
  const [fromInput, setFromInput] = useState("");
  const [toInput, setToInput] = useState("");

  const path = useMemo(() => {
    const params = new URLSearchParams();
    if (billingPeriod !== "") params.set("billingPeriod", billingPeriod);
    const from = dateInputToIsoZ(fromInput);
    if (from !== undefined) params.set("from", from);
    const to = endDateInputToIsoZ(toInput);
    if (to !== undefined) params.set("to", to);
    const search = params.toString();
    return `/api/v1/tariffs/${tariffId}/price-history${search ? `?${search}` : ""}`;
  }, [tariffId, billingPeriod, fromInput, toInput]);

  const { data, error, loading, refetch } = useApiQuery<PriceHistoryItemDto[]>(path);
  const seenVersion = useRef(version);
  useEffect(() => {
    if (version === seenVersion.current) return;
    seenVersion.current = version;
    refetch();
  }, [version, refetch]);
  const markers = useMemo(() => computeHistoryMarkers(data ?? []), [data]);

  const fromFieldError = error?.kind === "validation" ? error.details.find((d) => d.field === "from") : undefined;
  const toFieldError = error?.kind === "validation" ? error.details.find((d) => d.field === "to") : undefined;
  const otherError = error !== undefined && fromFieldError === undefined && toFieldError === undefined;

  return (
    <section>
      <h2>История цен</h2>
      <div className="filters">
        <DictionarySelect
          dictionary="billingPeriods"
          value={billingPeriod}
          onChange={setBillingPeriod}
          placeholder="Все периоды"
        />
        <label>
          С
          <input type="date" value={fromInput} onChange={(e) => setFromInput(e.target.value)} />
        </label>
        {fromFieldError !== undefined && <span className="field-error">{fromFieldError.message}</span>}
        <label>
          По
          <input type="date" value={toInput} onChange={(e) => setToInput(e.target.value)} />
        </label>
        {toFieldError !== undefined && <span className="field-error">{toFieldError.message}</span>}
      </div>

      {loading && <Loading />}
      {!loading && otherError && <ErrorView error={error} onRetry={refetch} />}
      {!loading && !otherError && data !== undefined && data.length === 0 && <p className="muted">Нет данных</p>}
      {!loading && !otherError && data !== undefined && data.length > 0 && (
        <div className="table-scroll">
          <table>
            <thead>
              <tr>
                <th>Период</th>
                <th className="num">Сумма</th>
                <th className="num">$</th>
                <th className="num">Курс</th>
                <th>Действует с</th>
                <th>Действует по</th>
                <th>Последняя проверка</th>
                <th>Источник</th>
              </tr>
            </thead>
            <tbody>
              {data.map((item, index) => (
                <HistoryRow key={item.id} item={item} marker={markers[index]} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function HistoryRow({ item, marker }: { item: PriceHistoryItemDto; marker: HistoryMarker }) {
  const markerClass = marker === "up" ? "history-marker--up" : marker === "down" ? "history-marker--dn" : undefined;
  const glyph = marker === "up" ? "↑" : marker === "down" ? "↓" : "";
  return (
    <tr>
      <td>{item.billingPeriod}</td>
      <td className={`num${markerClass ? ` ${markerClass}` : ""}`}>
        {glyph} {formatMoney(item.amount)} {item.currency}
      </td>
      <td className="num">{item.amountUsd !== undefined ? formatMoney(item.amountUsd) : "—"}</td>
      <td className="num">
        {item.usdRate !== undefined ? (
          <>
            {formatMoney(item.usdRate)}
            {item.usdRateAt !== undefined && (
              <div className="muted" title={item.usdRateAt}>
                {formatDateTime(item.usdRateAt)}
              </div>
            )}
          </>
        ) : (
          "—"
        )}
      </td>
      <td title={item.effectiveFrom}>{formatDateTime(item.effectiveFrom)}</td>
      <td title={item.effectiveTo}>{item.effectiveTo !== undefined ? formatDateTime(item.effectiveTo) : "—"}</td>
      <td title={item.lastConfirmedAt}>{formatDateTime(item.lastConfirmedAt)}</td>
      <td>{item.dataSource}</td>
    </tr>
  );
}
