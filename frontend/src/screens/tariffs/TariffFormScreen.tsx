import type { FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { useApiMutation, useApiQuery } from "../../api/hooks";
import type { ProviderDetailDto, TariffDetailDto, TariffDto } from "../../api/types";
import { ErrorView, Loading } from "../../components/StateViews";
import { DictionarySelect } from "../../dictionaries/DictionarySelect";
import { EditorOnly, Field, FormStatus } from "../../forms/FormParts";
import { useForm } from "../../forms/useForm";
import {
  buildTariffRequest,
  TARIFF_REQUIRED,
  tariffFormValues,
  type TariffFormValues,
  type TariffWriteRequest,
} from "./requests";

const FORBIDDEN = <p className="state-view state-view-error">недостаточно прав</p>;

/** `/providers/:id/tariffs/new`. */
export function TariffCreateScreen() {
  const { id } = useParams<{ id: string }>();
  return <EditorOnly fallback={FORBIDDEN}>{id !== undefined && <TariffCreate providerId={id} />}</EditorOnly>;
}

/** `/tariffs/:id/edit`. */
export function TariffEditScreen() {
  const { id } = useParams<{ id: string }>();
  return <EditorOnly fallback={FORBIDDEN}>{id !== undefined && <TariffEdit id={id} />}</EditorOnly>;
}

function TariffCreate({ providerId }: { providerId: string }) {
  const navigate = useNavigate();
  const provider = useApiQuery<ProviderDetailDto>(`/api/v1/providers/${providerId}`);
  const { mutate } = useApiMutation<TariffWriteRequest, TariffDto>("POST", `/api/v1/providers/${providerId}/tariffs`);

  if (provider.loading) return <Loading />;
  if (provider.error !== undefined || provider.data === undefined) {
    return <ErrorView error={provider.error} onRetry={provider.refetch} />;
  }

  return (
    <TariffForm
      title={`Новый тариф: ${provider.data.provider.name}`}
      initial={tariffFormValues()}
      cancelTo={`/providers/${providerId}`}
      onSubmit={async (values) => {
        const created = await mutate(buildTariffRequest(values));
        navigate(`/tariffs/${created.id}`);
      }}
    />
  );
}

function TariffEdit({ id }: { id: string }) {
  const navigate = useNavigate();
  const { data, error, loading, refetch } = useApiQuery<TariffDetailDto>(`/api/v1/tariffs/${id}`);
  const { mutate } = useApiMutation<TariffWriteRequest, TariffDto>("PUT", `/api/v1/tariffs/${id}`);

  if (loading) return <Loading />;
  if (error !== undefined || data === undefined) return <ErrorView error={error} onRetry={refetch} />;

  return (
    <TariffForm
      title={`Редактирование: ${data.tariff.name}`}
      initial={tariffFormValues(data.tariff)}
      cancelTo={`/tariffs/${id}`}
      onSubmit={async (values) => {
        await mutate(buildTariffRequest(values));
        navigate(`/tariffs/${id}`);
      }}
    />
  );
}

const FIELDS = [
  "externalId",
  "name",
  "url",
  "vcpuCount",
  "cpuModel",
  "ramMb",
  "diskGb",
  "diskType",
  "bandwidthMbps",
  "trafficLimitGb",
  "ipv4Count",
  "ipv6Supported",
  "virtualization",
  "isActive",
  "inStock",
];

function TariffForm({
  title,
  initial,
  cancelTo,
  onSubmit,
}: {
  title: string;
  initial: TariffFormValues;
  cancelTo: string;
  onSubmit: (values: TariffFormValues) => Promise<void>;
}) {
  const form = useForm(initial, TARIFF_REQUIRED);
  const { values } = form.state;
  const errors = form.fieldErrors;

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    void form.submit(onSubmit);
  }

  function intInput(field: "vcpuCount" | "ramMb" | "diskGb" | "bandwidthMbps" | "trafficLimitGb" | "ipv4Count") {
    return (
      <input
        inputMode="numeric"
        value={values[field]}
        onChange={(e) => form.set({ [field]: e.target.value })}
      />
    );
  }

  return (
    <form className="form" onSubmit={handleSubmit} noValidate>
      <h1>{title}</h1>
      <FormStatus error={form.state.error} onRetry={() => void form.retry()} fields={FIELDS} />
      <Field label="Название" required error={errors.name}>
        <input value={values.name} onChange={(e) => form.set({ name: e.target.value })} />
      </Field>
      <Field label="Внешний id" error={errors.externalId}>
        <input value={values.externalId} onChange={(e) => form.set({ externalId: e.target.value })} />
      </Field>
      <Field label="Ссылка на сайте" error={errors.url}>
        <input value={values.url} onChange={(e) => form.set({ url: e.target.value })} />
      </Field>
      <Field label="vCPU" required error={errors.vcpuCount}>
        {intInput("vcpuCount")}
      </Field>
      <Field label="Модель CPU" error={errors.cpuModel}>
        <input value={values.cpuModel} onChange={(e) => form.set({ cpuModel: e.target.value })} />
      </Field>
      <Field label="RAM, МБ" required error={errors.ramMb}>
        {intInput("ramMb")}
      </Field>
      <Field label="Диск, ГБ" required error={errors.diskGb}>
        {intInput("diskGb")}
      </Field>
      <Field label="Тип диска" required error={errors.diskType}>
        <DictionarySelect
          dictionary="diskTypes"
          value={values.diskType}
          onChange={(diskType) => form.set({ diskType })}
          placeholder="выберите"
        />
      </Field>
      <Field label="Виртуализация" required error={errors.virtualization}>
        <DictionarySelect
          dictionary="virtualizationTypes"
          value={values.virtualization}
          onChange={(virtualization) => form.set({ virtualization })}
          placeholder="выберите"
        />
      </Field>
      <Field label="Канал, Мбит/с" error={errors.bandwidthMbps}>
        {intInput("bandwidthMbps")}
      </Field>
      <Field label="Трафик, ГБ (пусто — безлимитный)" error={errors.trafficLimitGb}>
        {intInput("trafficLimitGb")}
      </Field>
      <Field label="IPv4, шт." required error={errors.ipv4Count}>
        {intInput("ipv4Count")}
      </Field>
      <Field label="IPv6" error={errors.ipv6Supported}>
        <select value={values.ipv6Supported} onChange={(e) => form.set({ ipv6Supported: e.target.value })}>
          <option value="">не указано</option>
          <option value="true">есть</option>
          <option value="false">нет</option>
        </select>
      </Field>
      <label className="form-check">
        <input type="checkbox" checked={values.isActive} onChange={(e) => form.set({ isActive: e.target.checked })} />
        Активен
      </label>
      {errors.isActive !== undefined && <span className="field-error">{errors.isActive}</span>}
      <label className="form-check">
        <input type="checkbox" checked={values.inStock} onChange={(e) => form.set({ inStock: e.target.checked })} />
        В наличии
      </label>
      {errors.inStock !== undefined && <span className="field-error">{errors.inStock}</span>}
      <div className="form-actions">
        <button type="submit" className="btn-primary" disabled={form.state.submitting}>
          {form.state.submitting ? "Сохранение…" : "Сохранить"}
        </button>
        <Link to={cancelTo}>Отмена</Link>
      </div>
    </form>
  );
}
