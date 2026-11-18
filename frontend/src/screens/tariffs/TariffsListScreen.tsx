import { useEffect, useMemo, useState, type MouseEvent } from "react";
import { Link, useLocation, useNavigate } from "react-router";
import { useApiQuery } from "../../api/hooks";
import { ANNUAL, type PageResponse, type PriceDto, type ProviderCheapestTariffDto, type TariffSearchItemDto } from "../../api/types";
import { Pagination } from "../../components/Pagination";
import { Empty, ErrorView, Loading } from "../../components/StateViews";
import { useDictionaries } from "../../dictionaries/DictionariesContext";
import { DictionarySelect } from "../../dictionaries/DictionarySelect";
import { formatMoney } from "../../lib/format";
import { parseUrlState, serializeUrlState, useDebouncedValue } from "../../lib/urlState";
import { PriceDates } from "../../components/PriceDates";
import {
  buildExpansionQuery,
  otherTariffCount,
  otherTariffsLabel,
  selectOtherTariffs,
  showsOnlyFirstPage,
} from "./expansion";
import {
  buildTariffFilterFieldsSchema,
  buildTariffTableFiltersSchema,
  normalizeCountryCode,
  type TariffFilterFieldsSchema,
  type TariffSortField,
  type TariffTableFilters,
} from "./filters";

function stopRowToggle(event: MouseEvent) {
  event.stopPropagation();
}

/** Local text while typing a two-letter country code: only a complete, valid code ever commits. */
function useCountryFilterInput(
  value: string | undefined,
  onCommit: (next: string | undefined) => void,
): [string, (raw: string) => void] {
  const [local, setLocal] = useState(value ?? "");
  useEffect(() => setLocal(value ?? ""), [value]);
  function handleChange(raw: string) {
    const upper = raw.toUpperCase();
    setLocal(upper);
    if (upper === "") onCommit(undefined);
    else {
      const normalized = normalizeCountryCode(upper);
      if (normalized !== undefined) onCommit(normalized);
    }
  }
  return [local, handleChange];
}

/** Local text for a "0 means no filter" numeric field, debounced so a keystroke does not refetch. */
function useIntFilterInput(value: number, onCommit: (next: number) => void): [string, (raw: string) => void] {
  const [local, setLocal] = useState(value === 0 ? "" : String(value));
  useEffect(() => setLocal(value === 0 ? "" : String(value)), [value]);
  const debounced = useDebouncedValue(local, 300);
  useEffect(() => {
    if (debounced === (value === 0 ? "" : String(value))) return;
    const parsed = debounced === "" ? 0 : Number.parseInt(debounced, 10);
    onCommit(Number.isNaN(parsed) || parsed < 0 ? 0 : parsed);
  }, [debounced]);
  return [local, setLocal];
}

function TriStateSelect({
  value,
  onChange,
  placeholder,
  trueLabel,
  falseLabel,
}: {
  value: boolean | undefined;
  onChange: (value: boolean | undefined) => void;
  placeholder: string;
  trueLabel: string;
  falseLabel: string;
}) {
  return (
    <select
      value={value === undefined ? "" : String(value)}
      onChange={(event) => onChange(event.target.value === "" ? undefined : event.target.value === "true")}
    >
      <option value="">{placeholder}</option>
      <option value="true">{trueLabel}</option>
      <option value="false">{falseLabel}</option>
    </select>
  );
}

export function TariffsListScreen() {
  const dictionaries = useDictionaries();
  const schema = useMemo(() => buildTariffTableFiltersSchema(dictionaries), [dictionaries]);
  const fieldsSchema = useMemo(() => buildTariffFilterFieldsSchema(dictionaries), [dictionaries]);
  const location = useLocation();
  const navigate = useNavigate();

  const filters = useMemo(() => parseUrlState(schema, location.search), [schema, location.search]);
  const canonicalSearch = useMemo(() => serializeUrlState(schema, filters), [schema, filters]);

  useEffect(() => {
    const current = location.search.startsWith("?") ? location.search.slice(1) : location.search;
    if (current !== canonicalSearch) {
      navigate(`${location.pathname}${canonicalSearch ? `?${canonicalSearch}` : ""}`, { replace: true });
    }
  }, [canonicalSearch, location.pathname, location.search, navigate]);

  function updateFilters(partial: Partial<TariffTableFilters>) {
    const next = { ...filters, ...partial };
    navigate(`${location.pathname}?${serializeUrlState(schema, next)}`);
  }

  const [countryInput, handleCountryChange] = useCountryFilterInput(filters.country, (value) =>
    updateFilters({ country: value, page: 0 }),
  );
  const [minVcpuInput, setMinVcpuInput] = useIntFilterInput(filters.minVcpu, (value) =>
    updateFilters({ minVcpu: value, page: 0 }),
  );
  const [minRamMbInput, setMinRamMbInput] = useIntFilterInput(filters.minRamMb, (value) =>
    updateFilters({ minRamMb: value, page: 0 }),
  );
  const [minDiskGbInput, setMinDiskGbInput] = useIntFilterInput(filters.minDiskGb, (value) =>
    updateFilters({ minDiskGb: value, page: 0 }),
  );
  const [maxPriceInput, setMaxPriceInput] = useState(filters.maxPriceUsd);
  const debouncedMaxPrice = useDebouncedValue(maxPriceInput, 300);
  useEffect(() => setMaxPriceInput(filters.maxPriceUsd), [filters.maxPriceUsd]);
  useEffect(() => {
    if (debouncedMaxPrice !== filters.maxPriceUsd) updateFilters({ maxPriceUsd: debouncedMaxPrice, page: 0 });
  }, [debouncedMaxPrice]);

  // Open/closed state is not URL state and resets whenever the table's own query changes. Keyed on
  // the query and derived during render: resetting it in an effect would let already-open panels
  // fire one request with the new filters before they close.
  const [expandedState, setExpandedState] = useState<{ search: string; ids: ReadonlySet<number> }>({
    search: canonicalSearch,
    ids: new Set(),
  });
  const expanded = expandedState.search === canonicalSearch ? expandedState.ids : new Set<number>();
  function toggleExpanded(providerId: number) {
    const next = new Set(expanded);
    if (next.has(providerId)) next.delete(providerId);
    else next.add(providerId);
    setExpandedState({ search: canonicalSearch, ids: next });
  }

  const queryParams = new URLSearchParams(canonicalSearch);
  queryParams.set("includeAnnual", "true");
  const query = `/api/v1/tariffs/by-provider?${queryParams}`;
  const { data, error, loading, refetch } = useApiQuery<PageResponse<ProviderCheapestTariffDto>>(query);

  function toggleSort(field: TariffSortField) {
    const current = filters.sort;
    updateFilters({ sort: { field, descending: current?.field === field && !current.descending }, page: 0 });
  }

  function sortIndicator(field: TariffSortField): string {
    if (filters.sort?.field !== field) return "";
    return filters.sort.descending ? " ↓" : " ↑";
  }

  return (
    <div className="tariffs-list">
      <div className="page-intro">
        <p className="eyebrow">СРАВНЕНИЕ ПРЕДЛОЖЕНИЙ</p>
        <h1>Тарифы</h1>
        <p className="page-description">Подберите виртуальный сервер по конфигурации, цене и условиям.</p>
      </div>
      <div className="notice">
        <p>
          Цены взяты с сайтов провайдеров и могут быть акционными (например, при оплате на длительный срок) —
          уточняйте на сайте провайдера.
        </p>
        <p>
          «цена с» — когда цена в каталоге последний раз менялась; «проверено» — когда её последний раз подтвердили
          (краулер или редактор); давно не проверенная цена могла устареть.
        </p>
      </div>

      <div className="filters">
        <input value={countryInput} onChange={(e) => handleCountryChange(e.target.value)} placeholder="Страна" maxLength={2} />
        <input
          value={minVcpuInput}
          onChange={(e) => setMinVcpuInput(e.target.value)}
          placeholder="Мин. vCPU"
          inputMode="numeric"
        />
        <input
          value={minRamMbInput}
          onChange={(e) => setMinRamMbInput(e.target.value)}
          placeholder="Мин. RAM, МБ"
          inputMode="numeric"
        />
        <input
          value={minDiskGbInput}
          onChange={(e) => setMinDiskGbInput(e.target.value)}
          placeholder="Мин. диск, ГБ"
          inputMode="numeric"
        />
        <DictionarySelect
          dictionary="diskTypes"
          value={filters.diskType ?? ""}
          onChange={(value) => updateFilters({ diskType: value === "" ? undefined : value, page: 0 })}
          placeholder="Тип диска"
        />
        <input
          value={maxPriceInput}
          onChange={(e) => setMaxPriceInput(e.target.value)}
          placeholder="Макс. цена, $/мес"
        />
        <DictionarySelect
          dictionary="paymentMethods"
          value={filters.paymentMethod ?? ""}
          onChange={(value) => updateFilters({ paymentMethod: value === "" ? undefined : value, page: 0 })}
          placeholder="Способ оплаты"
        />
        <TriStateSelect
          value={filters.unmeteredTraffic}
          onChange={(value) => updateFilters({ unmeteredTraffic: value, page: 0 })}
          placeholder="Трафик: любой"
          trueLabel="безлимитный"
          falseLabel="с лимитом"
        />
        <TriStateSelect
          value={filters.inStock}
          onChange={(value) => updateFilters({ inStock: value, page: 0 })}
          placeholder="Наличие: любое"
          trueLabel="в наличии"
          falseLabel="нет в наличии"
        />
        <label>
          <input
            type="checkbox"
            checked={!filters.activeOnly}
            onChange={(e) => updateFilters({ activeOnly: !e.target.checked, page: 0 })}
          />
          Показывать неактивные тарифы
        </label>
      </div>

      {loading && <Loading />}
      {!loading && error !== undefined && <ErrorView error={error} onRetry={refetch} />}
      {!loading && error === undefined && data !== undefined && data.content.length === 0 && <Empty />}
      {!loading && error === undefined && data !== undefined && data.content.length > 0 && (
        <>
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>
                    <button type="button" className="th-sort" onClick={() => toggleSort("name")}>
                      Провайдер{sortIndicator("name")}
                    </button>
                  </th>
                  <th className="num">
                    <button type="button" className="th-sort" onClick={() => toggleSort("priceUsd")}>
                      Цена, $/мес{sortIndicator("priceUsd")}
                    </button>
                  </th>
                  <th>Характеристики</th>
                  <th>Другие тарифы</th>
                  <th>Даты</th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((row) => {
                  const isExpanded = expanded.has(row.provider.id);
                  const remaining = otherTariffCount(row.matchingTariffCount);
                  return (
                    <TariffGroupRows
                      key={row.provider.id}
                      row={row}
                      remaining={remaining}
                      isExpanded={isExpanded}
                      onToggle={() => toggleExpanded(row.provider.id)}
                      fieldsSchema={fieldsSchema}
                      filters={filters}
                    />
                  );
                })}
              </tbody>
            </table>
          </div>
          <Pagination
            page={data.page}
            totalPages={data.totalPages}
            totalElements={data.totalElements}
            size={data.size}
            onPageChange={(page) => updateFilters({ page })}
            onSizeChange={(size) => updateFilters({ size, page: 0 })}
          />
        </>
      )}
    </div>
  );
}

interface TariffGroupRowsProps {
  row: ProviderCheapestTariffDto;
  remaining: number;
  isExpanded: boolean;
  onToggle: () => void;
  fieldsSchema: TariffFilterFieldsSchema;
  filters: TariffTableFilters;
}

function TariffPriceAmounts({
  prices,
  annualPriceUsdPerMonth,
}: {
  prices: PriceDto[];
  annualPriceUsdPerMonth: string | undefined;
}) {
  return prices.map((price) => {
    const annual = price.billingPeriod === ANNUAL;
    const usd = annual ? annualPriceUsdPerMonth : price.amountUsd;
    return (
      <div key={price.id}>
        {usd !== undefined ? `$${formatMoney(usd)}/мес${annual ? " при оплате за год" : ""}` : "—"}
        <div className="muted">{formatMoney(price.amount)} {price.currency}/{annual ? "год" : "мес"}</div>
      </div>
    );
  });
}

function TariffPriceDates({ prices }: { prices: PriceDto[] }) {
  return prices.map((price) => (
    <div key={price.id}>
      {prices.length > 1 && <span className="muted">{price.billingPeriod === ANNUAL ? "За год" : "За месяц"} </span>}
      <PriceDates effectiveFrom={price.effectiveFrom} lastConfirmedAt={price.lastConfirmedAt} />
    </div>
  ));
}

function TariffGroupRows({ row, remaining, isExpanded, onToggle, fieldsSchema, filters }: TariffGroupRowsProps) {
  const { provider, cheapestTariff, cheapestPrice, otherPrice, annualPriceUsdPerMonth } = row;
  const prices = otherPrice === undefined ? [cheapestPrice] : [cheapestPrice, otherPrice];
  return (
    <>
      <tr
        className={remaining > 0 ? "tariff-group-row tariff-group-row--clickable" : "tariff-group-row"}
        onClick={remaining > 0 ? onToggle : undefined}
      >
        <td>
          <Link to={`/providers/${provider.id}`} onClick={stopRowToggle} onAuxClick={stopRowToggle}>
            {provider.name}
          </Link>{" "}
          <a
            href={provider.websiteUrl}
            target="_blank"
            rel="noopener noreferrer"
            onClick={stopRowToggle}
            onAuxClick={stopRowToggle}
          >
            сайт
          </a>
        </td>
        <td className="num">
          <TariffPriceAmounts prices={prices} annualPriceUsdPerMonth={annualPriceUsdPerMonth} />
        </td>
        <td>
          {cheapestTariff.vcpuCount} vCPU, {cheapestTariff.ramMb} MB RAM, {cheapestTariff.diskGb} GB{" "}
          {cheapestTariff.diskType}
        </td>
        <td>
          {remaining > 0 && (
            <button
              type="button"
              className="disclosure-button"
              aria-expanded={isExpanded}
              onClick={(e) => {
                e.stopPropagation();
                onToggle();
              }}
            >
              {otherTariffsLabel(remaining)} {isExpanded ? "▲" : "▼"}
            </button>
          )}
        </td>
        <td>
          <TariffPriceDates prices={prices} />
        </td>
      </tr>
      {isExpanded && (
        <tr className="tariff-expansion-row">
          <td colSpan={5}>
            <TariffExpansionPanel
              providerId={provider.id}
              headlineTariffId={cheapestTariff.id}
              fieldsSchema={fieldsSchema}
              filters={filters}
            />
          </td>
        </tr>
      )}
    </>
  );
}

interface TariffExpansionPanelProps {
  providerId: number;
  headlineTariffId: number;
  fieldsSchema: TariffFilterFieldsSchema;
  filters: TariffTableFilters;
}

function TariffExpansionPanel({ providerId, headlineTariffId, fieldsSchema, filters }: TariffExpansionPanelProps) {
  const query = buildExpansionQuery(fieldsSchema, filters, providerId);
  const { data, error, loading, refetch } = useApiQuery<PageResponse<TariffSearchItemDto>>(
    `/api/v1/tariffs?${query}`,
  );

  if (loading) return <Loading />;
  if (error !== undefined || data === undefined) return <ErrorView error={error} onRetry={refetch} />;

  const items = selectOtherTariffs(data.content, headlineTariffId);
  if (items.length === 0) return <Empty text="Нет других подходящих тарифов" />;

  return (
    <div className="tariff-expansion">
      {showsOnlyFirstPage(data.totalElements) && <p className="muted">Показаны первые 100 тарифов</p>}
      <ul className="tariff-list">
        {items.map((item) => {
          const prices = [item.currentPrice, item.otherPrice].filter((price) => price !== undefined);
          return (
            <li key={item.tariff.id}>
              <Link to={`/tariffs/${item.tariff.id}`}>{item.tariff.name}</Link>
              <TariffPriceAmounts prices={prices} annualPriceUsdPerMonth={item.annualPriceUsdPerMonth} />
              <div className="muted">
                {item.tariff.vcpuCount} vCPU, {item.tariff.ramMb} MB RAM, {item.tariff.diskGb} GB {item.tariff.diskType},{" "}
                {item.tariff.inStock ? "в наличии" : "нет в наличии"}
              </div>
              <TariffPriceDates prices={prices} />
              {item.tariff.url !== undefined && (
                <a href={item.tariff.url} target="_blank" rel="noopener noreferrer">
                  на сайте
                </a>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
