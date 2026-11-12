import { useEffect, useMemo, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router";
import { useApiQuery } from "../../api/hooks";
import type { PageResponse, ProviderSummaryDto } from "../../api/types";
import { Pagination } from "../../components/Pagination";
import { Empty, ErrorView, Loading } from "../../components/StateViews";
import { DictionarySelect } from "../../dictionaries/DictionarySelect";
import { PaymentMethodIcon, paymentMethodLabel } from "../../domain/paymentMethods";
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
  const [locationCountryInput, handleLocationCountryChange] = useCountryFilterInput(
    filters.locationCountry,
    (value) => updateFilters({ locationCountry: value, page: 0 }),
  );

  const query = canonicalSearch ? `/api/v1/providers?${canonicalSearch}` : "/api/v1/providers";
  const { data, error, loading, refetch } = useApiQuery<PageResponse<ProviderSummaryDto>>(query);

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

  return (
    <div className="providers-list">
      <div className="page-header">
        <h1>Провайдеры</h1>
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
        <input
          value={locationCountryInput}
          onChange={(event) => handleLocationCountryChange(event.target.value)}
          placeholder="Страна размещения"
          maxLength={2}
        />
        <label>
          <input
            type="checkbox"
            checked={filters.hasActiveTariffs === true}
            onChange={(event) => updateFilters({ hasActiveTariffs: event.target.checked ? true : undefined, page: 0 })}
          />
          Только с активными тарифами
        </label>
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
                  <th>Способы оплаты</th>
                  <th>Страны размещения</th>
                  <th className="num">Активных тарифов</th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((provider) => (
                  <tr key={provider.id}>
                    <td>
                      <Link to={`/providers/${provider.id}`}>{provider.name}</Link>
                      <div className="muted">{provider.slug}</div>
                    </td>
                    <td className="cell-link">
                      <a href={provider.websiteUrl} target="_blank" rel="noopener noreferrer" title={provider.websiteUrl}>
                        {websiteHost(provider.websiteUrl)} ↗
                      </a>
                    </td>
                    <td className="num">
                      {provider.minMonthlyPriceUsd !== undefined ? `$${formatMoney(provider.minMonthlyPriceUsd)}/мес` : "—"}
                    </td>
                    <td>{provider.status}</td>
                    <td>{provider.registrationCountry ?? "—"}</td>
                    <td>
                      <span className="pm-icons">
                        {provider.paymentMethods.map((method) => (
                          <PaymentMethodIcon key={method} method={method} />
                        ))}
                      </span>
                    </td>
                    <td>{provider.locationCountryCodes.join(", ")}</td>
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
