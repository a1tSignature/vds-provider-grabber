import { useState, type FormEvent } from "react";
import { useApiMutation } from "../../api/hooks";
import type { LegalInfoDto, LocationDto, ProviderPaymentMethodDto } from "../../api/types";
import { useDictionaries } from "../../dictionaries/DictionariesContext";
import { DictionarySelect } from "../../dictionaries/DictionarySelect";
import { OriginBadge } from "../../components/OriginBadge";
import { originMarker } from "../../domain/provenanceLabels";
import { DestructiveAction } from "../../forms/DestructiveAction";
import { Field, FormStatus } from "../../forms/FormParts";
import { useForm } from "../../forms/useForm";
import {
  buildLegalInfoRequest,
  buildLocationRequest,
  buildPaymentMethodsRequest,
  LEGAL_INFO_REQUIRED,
  LOCATION_REQUIRED,
  legalInfoFormValues,
  locationFormValues,
  paymentMethodRows,
  type LegalInfoWriteRequest,
  type LocationFormValues,
  type LocationWriteRequest,
  type PaymentMethodRow,
  type PaymentMethodWriteRequest,
} from "./requests";

/*
 * Inline editors for the provider card. Each writes its own sub-resource, then calls `onSaved`,
 * which refetches the card: there is no `GET` for these sub-resources on their own.
 */

const LEGAL_FIELDS = [
  "legalEntityName",
  "registrationCountry",
  "registrationNumber",
  "legalAddress",
  "dataSource",
  "confidence",
  "sourceUrl",
  "verifiedAt",
];

export function LegalInfoEditor({
  providerId,
  legalInfo,
  onSaved,
  onCancel,
}: {
  providerId: number;
  legalInfo: LegalInfoDto | undefined;
  onSaved: () => void;
  onCancel: () => void;
}) {
  const form = useForm(legalInfoFormValues(legalInfo), LEGAL_INFO_REQUIRED);
  const { mutate } = useApiMutation<LegalInfoWriteRequest, LegalInfoDto>(
    "PUT",
    `/api/v1/providers/${providerId}/legal-info`,
  );
  const { values } = form.state;

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    void form.submit(async (current) => {
      await mutate(buildLegalInfoRequest(current, legalInfo));
      onSaved();
    });
  }

  return (
    <form className="form inline-editor" onSubmit={handleSubmit} noValidate>
      <FormStatus error={form.state.error} onRetry={() => void form.retry()} fields={LEGAL_FIELDS} />
      <Field label="Юридическое название" error={form.fieldErrors.legalEntityName}>
        <input value={values.legalEntityName} onChange={(e) => form.set({ legalEntityName: e.target.value })} />
      </Field>
      <Field label="Страна регистрации" error={form.fieldErrors.registrationCountry}>
        <input
          value={values.registrationCountry}
          onChange={(e) => form.set({ registrationCountry: e.target.value })}
        />
      </Field>
      <Field label="Регистрационный номер" error={form.fieldErrors.registrationNumber}>
        <input value={values.registrationNumber} onChange={(e) => form.set({ registrationNumber: e.target.value })} />
      </Field>
      <Field label="Юридический адрес" error={form.fieldErrors.legalAddress}>
        <input value={values.legalAddress} onChange={(e) => form.set({ legalAddress: e.target.value })} />
      </Field>
      <Field label="Источник данных" required error={form.fieldErrors.dataSource}>
        <DictionarySelect
          dictionary="legalDataSources"
          value={values.dataSource}
          onChange={(dataSource) => form.set({ dataSource })}
          placeholder="выберите"
        />
      </Field>
      <Field label="Достоверность" required error={form.fieldErrors.confidence}>
        <DictionarySelect
          dictionary="confidenceLevels"
          value={values.confidence}
          onChange={(confidence) => form.set({ confidence })}
          placeholder="выберите"
        />
      </Field>
      <Field label="Источник (ссылка)" error={form.fieldErrors.sourceUrl}>
        <input value={values.sourceUrl} onChange={(e) => form.set({ sourceUrl: e.target.value })} />
      </Field>
      <Field label="Проверено (UTC)" error={form.fieldErrors.verifiedAt}>
        <input
          type="datetime-local"
          step={1}
          value={values.verifiedAt}
          onChange={(e) => form.set({ verifiedAt: e.target.value })}
        />
      </Field>
      <EditorButtons submitting={form.state.submitting} onCancel={onCancel} />
    </form>
  );
}

export function PaymentMethodsEditor({
  providerId,
  paymentMethods,
  onSaved,
  onCancel,
}: {
  providerId: number;
  paymentMethods: ProviderPaymentMethodDto[];
  onSaved: () => void;
  onCancel: () => void;
}) {
  const dictionaries = useDictionaries();
  const form = useForm<{ rows: PaymentMethodRow[] }>({ rows: paymentMethodRows(paymentMethods) });
  const { mutate } = useApiMutation<PaymentMethodWriteRequest[], ProviderPaymentMethodDto[]>(
    "PUT",
    `/api/v1/providers/${providerId}/payment-methods`,
  );
  const { rows } = form.state.values;
  const available = dictionaries.paymentMethods.filter((method) => !rows.some((row) => row.method === method));

  function updateRow(index: number, note: string) {
    form.set({ rows: rows.map((row, i) => (i === index ? { ...row, note } : row)) });
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    void form.submit(async (current) => {
      await mutate(buildPaymentMethodsRequest(current.rows));
      onSaved();
    });
  }

  return (
    <form className="form inline-editor" onSubmit={handleSubmit} noValidate>
      <FormStatus error={form.state.error} onRetry={() => void form.retry()} />
      {rows.length === 0 && <p className="muted">способов оплаты нет</p>}
      {rows.map((row, index) => {
        const remove = () => form.set({ rows: rows.filter((_, i) => i !== index) });
        return (
          <div key={row.method} className="form-row">
            <span className="form-row-label">
              {row.method} <OriginBadge origin={row.origin} />
            </span>
            <input
              value={row.note}
              onChange={(e) => updateRow(index, e.target.value)}
              placeholder="примечание"
              aria-label={`примечание для ${row.method}`}
            />
            {originMarker(row.origin) === null ? (
              <button type="button" onClick={remove}>
                Убрать
              </button>
            ) : (
              <DestructiveAction
                label="Убрать"
                confirmText={`${row.method} добавлен автоматически. Если убрать его и сохранить, автосбор больше не добавит этот способ оплаты.`}
                run={async () => remove()}
                onDone={() => undefined}
              />
            )}
          </div>
        );
      })}
      {available.length > 0 && (
        <select
          value=""
          onChange={(e) => {
            if (e.target.value !== "") form.set({ rows: [...rows, { method: e.target.value, note: "" }] });
          }}
        >
          <option value="">добавить способ оплаты…</option>
          {available.map((method) => (
            <option key={method} value={method}>
              {method}
            </option>
          ))}
        </select>
      )}
      <EditorButtons submitting={form.state.submitting} onCancel={onCancel} />
    </form>
  );
}

export function LocationsEditor({
  providerId,
  locations,
  onSaved,
}: {
  providerId: number;
  locations: LocationDto[];
  onSaved: () => void;
}) {
  const [editingId, setEditingId] = useState<number | "new" | null>(null);
  return (
    <div className="inline-editor">
      <ul>
        {locations.map((location) =>
          editingId === location.id ? (
            <li key={location.id}>
              <LocationUpdateForm location={location} onSaved={onSaved} onCancel={() => setEditingId(null)} />
            </li>
          ) : (
            <LocationRow
              key={location.id}
              location={location}
              onEdit={() => setEditingId(location.id)}
              onDeleted={onSaved}
            />
          ),
        )}
      </ul>
      {editingId === "new" ? (
        <LocationCreateForm providerId={providerId} onSaved={onSaved} onCancel={() => setEditingId(null)} />
      ) : (
        <button type="button" onClick={() => setEditingId("new")}>
          Добавить локацию
        </button>
      )}
    </div>
  );
}

function LocationRow({
  location,
  onEdit,
  onDeleted,
}: {
  location: LocationDto;
  onEdit: () => void;
  onDeleted: () => void;
}) {
  const { mutate } = useApiMutation<void, void>("DELETE", `/api/v1/locations/${location.id}`);
  return (
    <li className="form-row">
      <span>
        {describeLocation(location)} <OriginBadge origin={location.origin} />
      </span>
      <button type="button" onClick={onEdit}>
        Изменить
      </button>
      <DestructiveAction
        label="Удалить"
        confirmText={
          originMarker(location.origin) === null
            ? "Удалить локацию? Тарифы, привязанные к ней, потеряют эту привязку."
            : `Удалить локацию? Тарифы, привязанные к ней, потеряют эту привязку. Локация добавлена автоматически: если других локаций в ${location.countryCode} не останется, автосбор больше не добавит эту страну.`
        }
        run={() => mutate()}
        onDone={onDeleted}
      />
    </li>
  );
}

function describeLocation(location: LocationDto): string {
  return [location.countryCode, location.region, location.city, location.datacenterName]
    .filter((part) => part !== undefined)
    .join(", ");
}

function LocationCreateForm({
  providerId,
  onSaved,
  onCancel,
}: {
  providerId: number;
  onSaved: () => void;
  onCancel: () => void;
}) {
  const { mutate } = useApiMutation<LocationWriteRequest, LocationDto>(
    "POST",
    `/api/v1/providers/${providerId}/locations`,
  );
  return <LocationForm initial={locationFormValues()} save={mutate} onSaved={onSaved} onCancel={onCancel} />;
}

function LocationUpdateForm({
  location,
  onSaved,
  onCancel,
}: {
  location: LocationDto;
  onSaved: () => void;
  onCancel: () => void;
}) {
  const { mutate } = useApiMutation<LocationWriteRequest, LocationDto>("PUT", `/api/v1/locations/${location.id}`);
  return <LocationForm initial={locationFormValues(location)} save={mutate} onSaved={onSaved} onCancel={onCancel} />;
}

const LOCATION_FIELDS = ["countryCode", "region", "city", "datacenterName", "latitude", "longitude"];

function LocationForm({
  initial,
  save,
  onSaved,
  onCancel,
}: {
  initial: LocationFormValues;
  save: (body: LocationWriteRequest) => Promise<LocationDto>;
  onSaved: () => void;
  onCancel: () => void;
}) {
  const form = useForm(initial, LOCATION_REQUIRED);
  const { values } = form.state;

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    void form.submit(async (current) => {
      await save(buildLocationRequest(current));
      onSaved();
    });
  }

  return (
    <form className="form" onSubmit={handleSubmit} noValidate>
      <FormStatus error={form.state.error} onRetry={() => void form.retry()} fields={LOCATION_FIELDS} />
      <Field label="Страна" required error={form.fieldErrors.countryCode}>
        <input value={values.countryCode} onChange={(e) => form.set({ countryCode: e.target.value })} />
      </Field>
      <Field label="Регион" error={form.fieldErrors.region}>
        <input value={values.region} onChange={(e) => form.set({ region: e.target.value })} />
      </Field>
      <Field label="Город" error={form.fieldErrors.city}>
        <input value={values.city} onChange={(e) => form.set({ city: e.target.value })} />
      </Field>
      <Field label="Дата-центр" error={form.fieldErrors.datacenterName}>
        <input value={values.datacenterName} onChange={(e) => form.set({ datacenterName: e.target.value })} />
      </Field>
      <Field label="Широта" error={form.fieldErrors.latitude}>
        <input value={values.latitude} onChange={(e) => form.set({ latitude: e.target.value })} inputMode="decimal" />
      </Field>
      <Field label="Долгота" error={form.fieldErrors.longitude}>
        <input value={values.longitude} onChange={(e) => form.set({ longitude: e.target.value })} inputMode="decimal" />
      </Field>
      <EditorButtons submitting={form.state.submitting} onCancel={onCancel} />
    </form>
  );
}

export function EditorButtons({ submitting, onCancel }: { submitting: boolean; onCancel: () => void }) {
  return (
    <div className="form-actions">
      <button type="submit" className="btn-primary" disabled={submitting}>
        {submitting ? "Сохранение…" : "Сохранить"}
      </button>
      <button type="button" onClick={onCancel} disabled={submitting}>
        Отмена
      </button>
    </div>
  );
}
