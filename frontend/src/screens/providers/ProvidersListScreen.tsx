import { useEffect, useMemo, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router";
import { OriginBadge } from "../../components/OriginBadge";
import { SystemStatusBadge } from "../../components/SystemStatusBadge";
import { CountryFlag } from "../../components/CountryFlag";
import { Pagination } from "../../components/Pagination";
import { Empty, ErrorView, Loading } from "../../components/StateViews";
import { DictionarySelect } from "../../dictionaries/DictionarySelect";
import { PaymentMethodIcon, paymentMethodLabel } from "../../domain/paymentMethods";
import { systemStatusFilterLabel } from "../../domain/systemStatusLabels";
import { COUNTRIES } from "../../domain/countries";
import { useProvidersListQuery } from "./useProvidersListQuery";
import { EditorOnly } from "../../forms/FormParts";
import { useDictionaries } from "../../dictionaries/DictionariesContext";
import { formatMoney, websiteHost } from "../../lib/format";
import { parseUrlState, serializeUrlState, useDebouncedValue } from "../../lib/urlState";
import {
  buildProviderFiltersSchema,
  normalizeCountryCode,
  type ProviderFiltersSchema,
  type ProviderSortField,
} from "./filters";

type ProviderFilters = ReturnType<typeof parseUrlState<ProviderFiltersSchema>>;

/**
 * Local state for a two-letter country code filter: committing only a valid code or an empty
 * string (never a partial one) is what lets the user type the first letter at all -- a value
 * bound straight to the parsed filter would have `countryCodeField.parse` throw away every
 * single-letter input on the very next render, making the field uneditable.
 */
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

export function ProvidersListScreen() {
  const dictionaries = useDictionaries();
  const schema = useMemo(() => buildProviderFiltersSchema(dictionaries), [dictionaries]);
  const location = useLocation();
  const navigate = useNavigate();

  const filters = useMemo(() => parseUrlState(schema, location.search), [schema, location.search]);
  const canonicalSearch = useMemo(() => serializeUrlState(schema, filters), [schema, filters]);

  // Keeps the address bar canonical: an invalid or stale query parameter is dropped here, not
  // silently kept around for the next reload to reproduce.
  useEffect(() => {
    const current = location.search.startsWith("?") ? location.search.slice(1) : location.search;
    if (current !== canonicalSearch) {
      navigate(`${location.pathname}${canonicalSearch ? `?${canonicalSearch}` : ""}`, { replace: true });
    }
  }, [canonicalSearch, location.pathname, location.search, navigate]);

  function updateFilters(partial: Partial<ProviderFilters>) {
    const next = { ...filters, ...partial };
    navigate(`${location.pathname}?${serializeUrlState(schema, next)}`);
  }

  const [qInput, setQInput] = useState(filters.q);
  const debouncedQ = useDebouncedValue(qInput, 300);
  useEffect(() => setQInput(filters.q), [filters.q]);
  useEffect(() => {
    if (debouncedQ !== filters.q) updateFilters({ q: debouncedQ, page: 0 });
  }, [debouncedQ]);

  const [registrationCountryInput, handleRegistrationCountryChange] = useCountryFilterInput(
    filters.registrationCountry,
    (value) => updateFilters({ registrationCountry: value, page: 0 }),
  );
  const [countrySearch, setCountrySearch] = useState("");

  const { data, error, loading, refetch } = useProvidersListQuery(canonicalSearch);

  function toggleLocationCountry(code: string) {
    const next = filters.locationCountry.includes(code)
      ? filters.locationCountry.filter((country) => country !== code)
      : [...filters.locationCountry, code];
    updateFilters({ locationCountry: next, page: 0 });
  }

  function toggleSort(field: ProviderSortField) {
    const current = filters.sort;
    updateFilters({ sort: { field, descending: current?.field === field && !current.descending }, page: 0 });
  }

  function sortIndicator(field: ProviderSortField): string {
    if (filters.sort?.field !== field) return "";
    return filters.sort.descending ? " ↓" : " ↑";
  }

  function togglePaymentMethod(method: string) {
    const next = filters.paymentMethod.includes(method)
      ? filters.paymentMethod.filter((item) => item !== method)
      : [...filters.paymentMethod, method];
    updateFilters({ paymentMethod: next, page: 0 });
  }

  function toggleSystemStatus(systemStatus: string) {
    const next = filters.systemStatus.includes(systemStatus)
      ? filters.systemStatus.filter((item) => item !== systemStatus)
      : [...filters.systemStatus, systemStatus];
    updateFilters({ systemStatus: next, page: 0 });
  }

  return (
    <div className="providers-list">
      <div className="page-header">
        <div>
          <p className="eyebrow">КАТАЛОГ ИНФРАСТРУКТУРЫ</p>
          <h1>Провайдеры</h1>
          <p className="page-description">Сравнивайте условия, страны размещения и способы оплаты.</p>
        </div>
        <EditorOnly>
          <Link to="/providers/new" className="btn-primary">
            Добавить провайдера
          </Link>
        </EditorOnly>
      </div>
      <div className="filters">
        <input
          value={qInput}
          onChange={(event) => setQInput(event.target.value)}
          placeholder="Поиск по названию или slug"
        />
        <DictionarySelect
          dictionary="providerStatuses"
          value={filters.status ?? ""}
          onChange={(value) => updateFilters({ status: value === "" ? undefined : value, page: 0 })}
          placeholder="Любой статус"
        />
        <input
          value={registrationCountryInput}
          onChange={(event) => handleRegistrationCountryChange(event.target.value)}
          placeholder="Страна регистрации"
          maxLength={2}
        />
        <details className="country-filter">
          <summary>
            Страны размещения{filters.locationCountry.length > 0 ? ` · ${filters.locationCountry.length}` : ""}
          </summary>
          <div className="country-filter-menu">
            <input
              type="search"
              value={countrySearch}
              onChange={(event) => setCountrySearch(event.target.value)}
              placeholder="Найти страну"
              aria-label="Найти страну размещения"
            />
            {filters.locationCountry.length > 0 && (
              <button type="button" className="btn-link" onClick={() => updateFilters({ locationCountry: [], page: 0 })}>
                Сбросить выбор
              </button>
            )}
            <div className="country-filter-options">
              {COUNTRIES.filter(({ code, name }) =>
                `${name} ${code}`.toLocaleLowerCase("ru").includes(countrySearch.trim().toLocaleLowerCase("ru")),
              ).map(({ code, name }) => (
                <label key={code} className="country-filter-option">
                  <input
                    type="checkbox"
                    checked={filters.locationCountry.includes(code)}
                    onChange={() => toggleLocationCountry(code)}
                  />
                  <CountryFlag code={code} />
                  <span>{name}</span>
                  <span className="muted">{code}</span>
                </label>
              ))}
            </div>
          </div>
        </details>
        <label>
          <input
            type="checkbox"
            checked={filters.hasActiveTariffs === true}
            onChange={(event) => updateFilters({ hasActiveTariffs: event.target.checked ? true : undefined, page: 0 })}
          />
          Только с активными тарифами
        </label>
        <fieldset className="payment-methods-filter">
          <legend>Статус в системе</legend>
          {dictionaries.systemStatuses.map((systemStatus) => (
            <label key={systemStatus} className="pm-option">
              <input
                type="checkbox"
                checked={filters.systemStatus.includes(systemStatus)}
                onChange={() => toggleSystemStatus(systemStatus)}
              />
              {systemStatusFilterLabel(systemStatus)}
            </label>
          ))}
          <button
            type="button"
            className="btn-link"
            onClick={() => updateFilters({ systemStatus: [...dictionaries.systemStatuses], page: 0 })}
          >
            все
          </button>
          {filters.systemStatus.length === 0 && <span className="muted">не выбрано — только настроенные</span>}
        </fieldset>
        <fieldset className="payment-methods-filter">
          <legend>Способы оплаты</legend>
          {dictionaries.paymentMethods.map((method) => (
            <label key={method} className="pm-option">
              <input
                type="checkbox"
                checked={filters.paymentMethod.includes(method)}
                onChange={() => togglePaymentMethod(method)}
              />
              <PaymentMethodIcon method={method} decorative />
              {paymentMethodLabel(method)}
            </label>
          ))}
        </fieldset>
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
                      Название / slug{sortIndicator("name")}
                    </button>
                  </th>
                  <th>Сайт</th>
                  <th className="num">
                    <button type="button" className="th-sort" onClick={() => toggleSort("minMonthlyPriceUsd")}>
                      Минимальная цена{sortIndicator("minMonthlyPriceUsd")}
                    </button>
                  </th>
                  <th>
                    <button type="button" className="th-sort" onClick={() => toggleSort("status")}>
                      Статус{sortIndicator("status")}
                    </button>
                  </th>
                  <th>Страна рег.</th>
                  <th>Юрлицо</th>
                  <th>Способы оплаты</th>
                  <th>Страны размещения</th>
                  <th className="num">Активных тарифов</th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((provider) => (
                  <tr key={provider.id}>
                    <td>
                      <Link to={`/providers/${provider.id}`}>{provider.name}</Link>{" "}
                      <SystemStatusBadge systemStatus={provider.systemStatus} />
                      <div className="muted">{provider.slug}</div>
                    </td>
                    <td className="cell-link">
                      <a href={provider.websiteUrl} target="_blank" rel="noopener noreferrer" title={provider.websiteUrl}>
                        {websiteHost(provider.websiteUrl)} ↗
                      </a>
                    </td>
                    <td className="num">
                      {provider.minMonthlyPriceUsd === undefined && provider.minAnnualPriceUsdPerMonth === undefined && "—"}
                      {provider.minMonthlyPriceUsd !== undefined && <div>${formatMoney(provider.minMonthlyPriceUsd)}/мес</div>}
                      {provider.minAnnualPriceUsdPerMonth !== undefined && (
                        <div className={provider.minMonthlyPriceUsd !== undefined ? "muted" : undefined}>
                          ${formatMoney(provider.minAnnualPriceUsdPerMonth)}/мес при оплате за год
                        </div>
                      )}
                    </td>
                    <td>{provider.status}</td>
                    <td>
                      {provider.registrationCountry ?? "—"}{" "}
                      <OriginBadge origin={provider.registrationCountryOrigin} compact />
                    </td>
                    <td>
                      {provider.legalEntityName ?? "—"} <OriginBadge origin={provider.legalEntityNameOrigin} compact />
                    </td>
                    <td>
                      <span className="pm-icons">
                        {provider.paymentMethods.map((method) => (
                          <PaymentMethodIcon key={method} method={method} />
                        ))}
                      </span>
                    </td>
                    <td>
                      <span className="country-flags">
                        {provider.locationCountryCodes.length === 0
                          ? "—"
                          : provider.locationCountryCodes.map((code) => <CountryFlag key={code} code={code} />)}
                      </span>
                    </td>
                    <td className="num">{provider.activeTariffCount}</td>
                  </tr>
                ))}
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
