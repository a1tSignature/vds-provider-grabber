import { useState, type FormEvent } from "react";
import { useApiMutation, useApiQuery } from "../../api/hooks";
import type { LocationDto, PriceDto, ProviderDetailDto } from "../../api/types";
import { ErrorView, Loading } from "../../components/StateViews";
import { DictionarySelect } from "../../dictionaries/DictionarySelect";
import { ConfirmDialog, Field, FormStatus } from "../../forms/FormParts";
import { useForm } from "../../forms/useForm";
import {
  buildPriceCloseRequest,
  buildPriceRequest,
  buildTariffLocationsRequest,
  PRICE_CLOSE_REQUIRED,
  PRICE_REQUIRED,
  priceWriteNotice,
  type PriceCloseFormValues,
  type PriceCloseRequest,
  type PriceFormValues,
  type PriceWriteRequest,
} from "./requests";

/** Options are the provider's own locations: the backend rejects a link to another provider's. */
export function TariffLocationsEditor({
  tariffId,
  providerId,
  linked,
  onSaved,
  onCancel,
}: {
  tariffId: number;
  providerId: number;
  linked: LocationDto[];
  onSaved: () => void;
  onCancel: () => void;
}) {
  const provider = useApiQuery<ProviderDetailDto>(`/api/v1/providers/${providerId}`);
  const form = useForm<{ selected: ReadonlySet<number> }>({ selected: new Set(linked.map((location) => location.id)) });
  const { mutate } = useApiMutation<number[], number[]>("PUT", `/api/v1/tariffs/${tariffId}/locations`);

  if (provider.loading) return <Loading />;
  if (provider.error !== undefined || provider.data === undefined) {
    return <ErrorView error={provider.error} onRetry={provider.refetch} />;
  }

  const { selected } = form.state.values;
  function toggle(id: number, checked: boolean) {
    const next = new Set(selected);
    if (checked) next.add(id);
    else next.delete(id);
    form.set({ selected: next });
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    void form.submit(async (current) => {
      await mutate(buildTariffLocationsRequest(current.selected));
      onSaved();
    });
  }

  const options = provider.data.locations;
  return (
    <form className="form inline-editor" onSubmit={handleSubmit} noValidate>
      <FormStatus error={form.state.error} onRetry={() => void form.retry()} />
      {options.length === 0 && <p className="muted">у провайдера нет локаций</p>}
      {options.map((location) => (
        <label key={location.id} className="form-check">
          <input
            type="checkbox"
            checked={selected.has(location.id)}
            onChange={(e) => toggle(location.id, e.target.checked)}
          />
          {[location.countryCode, location.city, location.datacenterName].filter((part) => part !== undefined).join(", ")}
        </label>
      ))}
      <div className="form-actions">
        <button type="submit" className="btn-primary" disabled={form.state.submitting}>
          {form.state.submitting ? "Сохранение…" : "Сохранить"}
        </button>
        <button type="button" onClick={onCancel} disabled={form.state.submitting}>
          Отмена
        </button>
      </div>
    </form>
  );
}

const PRICE_FIELDS = ["billingPeriod", "amount", "currency", "effectiveFrom"];

export function PriceWriteForm({ tariffId, onSaved }: { tariffId: number; onSaved: () => void }) {
  const form = useForm<PriceFormValues>(
    { billingPeriod: "", amount: "", currency: "", effectiveFrom: "" },
    PRICE_REQUIRED,
  );
  const { mutateWithStatus } = useApiMutation<PriceWriteRequest, PriceDto>("PUT", `/api/v1/tariffs/${tariffId}/price`);
  const { values } = form.state;

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    void form.submit(async (current) => {
      const { status, data } = await mutateWithStatus(buildPriceRequest(current));
      onSaved();
      return { notice: priceWriteNotice(status), warnings: data.warnings };
    });
  }

  return (
    <form className="form inline-editor" onSubmit={handleSubmit} noValidate>
      <h3>Записать цену</h3>
      <FormStatus
        error={form.state.error}
        notice={form.state.notice}
        warnings={form.state.warnings}
        onRetry={() => void form.retry()}
        fields={PRICE_FIELDS}
      />
      <div className="form-grid">
        <Field label="Период" required error={form.fieldErrors.billingPeriod}>
          <DictionarySelect
            dictionary="billingPeriods"
            value={values.billingPeriod}
            onChange={(billingPeriod) => form.set({ billingPeriod })}
            placeholder="выберите"
          />
        </Field>
        <Field label="Сумма" required error={form.fieldErrors.amount}>
          <input
            value={values.amount}
            onChange={(e) => form.set({ amount: e.target.value })}
            inputMode="decimal"
            placeholder="4.50"
          />
        </Field>
        <Field label="Валюта" required error={form.fieldErrors.currency}>
          <input
            value={values.currency}
            onChange={(e) => form.set({ currency: e.target.value })}
            placeholder="EUR"
          />
        </Field>
        <Field label="Действует с (UTC, необязательно)" error={form.fieldErrors.effectiveFrom}>
          <input
            type="datetime-local"
            step={1}
            value={values.effectiveFrom}
            onChange={(e) => form.set({ effectiveFrom: e.target.value })}
          />
        </Field>
      </div>
      <div className="form-actions">
        <button type="submit" className="btn-primary" disabled={form.state.submitting}>
          {form.state.submitting ? "Запись…" : "Записать"}
        </button>
      </div>
    </form>
  );
}

const CLOSE_FIELDS = ["billingPeriod", "effectiveTo"];

export function PriceCloseForm({ tariffId, onSaved }: { tariffId: number; onSaved: () => void }) {
  const form = useForm<PriceCloseFormValues>({ billingPeriod: "", effectiveTo: "" }, PRICE_CLOSE_REQUIRED);
  const { mutate } = useApiMutation<PriceCloseRequest, PriceDto>("POST", `/api/v1/tariffs/${tariffId}/price/close`);
  const [confirming, setConfirming] = useState(false);
  const { values } = form.state;

  function close() {
    setConfirming(false);
    void form.submit(async (current) => {
      await mutate(buildPriceCloseRequest(current));
      onSaved();
      return { notice: "цена закрыта" };
    });
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (values.billingPeriod === "") {
      void form.submit(() => Promise.resolve());
      return;
    }
    setConfirming(true);
  }

  return (
    <form className="form inline-editor" onSubmit={handleSubmit} noValidate>
      <h3>Закрыть цену</h3>
      <FormStatus
        error={form.state.error}
        notice={form.state.notice}
        onRetry={() => void form.retry()}
        fields={CLOSE_FIELDS}
        overrides={{ notFound: "для этого периода нет текущей цены" }}
      />
      <div className="form-grid">
        <Field label="Период" required error={form.fieldErrors.billingPeriod}>
          <DictionarySelect
            dictionary="billingPeriods"
            value={values.billingPeriod}
            onChange={(billingPeriod) => form.set({ billingPeriod })}
            placeholder="выберите"
          />
        </Field>
        <Field label="Действует по (UTC, необязательно)" error={form.fieldErrors.effectiveTo}>
          <input
            type="datetime-local"
            step={1}
            value={values.effectiveTo}
            onChange={(e) => form.set({ effectiveTo: e.target.value })}
          />
        </Field>
      </div>
      <div className="form-actions">
        <button type="submit" disabled={form.state.submitting}>
          {form.state.submitting ? "Закрытие…" : "Закрыть цену"}
        </button>
      </div>
      {confirming && (
        <ConfirmDialog
          text={`Закрыть текущую цену за период ${values.billingPeriod}? У тарифа не останется действующей цены за этот период.`}
          confirmLabel="Закрыть цену"
          busy={form.state.submitting}
          onConfirm={close}
          onCancel={() => setConfirming(false)}
        />
      )}
    </form>
  );
}
