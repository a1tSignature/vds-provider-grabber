import type { FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { useApiMutation, useApiQuery } from "../../api/hooks";
import type { ProviderDetailDto, ProviderDto } from "../../api/types";
import { ErrorView, Loading } from "../../components/StateViews";
import { DictionarySelect } from "../../dictionaries/DictionarySelect";
import { EditorOnly, Field, FormStatus } from "../../forms/FormParts";
import { useForm } from "../../forms/useForm";
import {
  buildProviderRequest,
  PROVIDER_REQUIRED,
  providerFormValues,
  type ProviderFormValues,
  type ProviderWriteRequest,
} from "./requests";

const FORBIDDEN = <p className="state-view state-view-error">недостаточно прав</p>;

export function ProviderFormScreen() {
  const { id } = useParams<{ id: string }>();
  return <EditorOnly fallback={FORBIDDEN}>{id === undefined ? <ProviderCreate /> : <ProviderEdit id={id} />}</EditorOnly>;
}

function ProviderCreate() {
  const navigate = useNavigate();
  const { mutate } = useApiMutation<ProviderWriteRequest, ProviderDto>("POST", "/api/v1/providers");
  return (
    <ProviderForm
      title="Новый провайдер"
      initial={providerFormValues()}
      cancelTo="/providers"
      onSubmit={async (values) => {
        const created = await mutate(buildProviderRequest(values));
        navigate(`/providers/${created.id}`);
      }}
    />
  );
}

function ProviderEdit({ id }: { id: string }) {
  const navigate = useNavigate();
  const { data, error, loading, refetch } = useApiQuery<ProviderDetailDto>(`/api/v1/providers/${id}`);
  const { mutate } = useApiMutation<ProviderWriteRequest, ProviderDto>("PUT", `/api/v1/providers/${id}`);

  if (loading) return <Loading />;
  if (error !== undefined || data === undefined) return <ErrorView error={error} onRetry={refetch} />;

  return (
    <ProviderForm
      title={`Редактирование: ${data.provider.name}`}
      initial={providerFormValues(data.provider)}
      cancelTo={`/providers/${id}`}
      onSubmit={async (values) => {
        await mutate(buildProviderRequest(values));
        navigate(`/providers/${id}`);
      }}
    />
  );
}

const FIELDS = ["slug", "name", "websiteUrl", "description", "status"];

function ProviderForm({
  title,
  initial,
  cancelTo,
  onSubmit,
}: {
  title: string;
  initial: ProviderFormValues;
  cancelTo: string;
  onSubmit: (values: ProviderFormValues) => Promise<void>;
}) {
  const form = useForm(initial, PROVIDER_REQUIRED);
  const { values } = form.state;

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    void form.submit(onSubmit);
  }

  return (
    <form className="form" onSubmit={handleSubmit} noValidate>
      <h1>{title}</h1>
      <FormStatus error={form.state.error} onRetry={() => void form.retry()} fields={FIELDS} />
      <Field label="Slug" required error={form.fieldErrors.slug}>
        <input value={values.slug} onChange={(e) => form.set({ slug: e.target.value })} />
      </Field>
      <Field label="Название" required error={form.fieldErrors.name}>
        <input value={values.name} onChange={(e) => form.set({ name: e.target.value })} />
      </Field>
      <Field label="Сайт" required error={form.fieldErrors.websiteUrl}>
        <input value={values.websiteUrl} onChange={(e) => form.set({ websiteUrl: e.target.value })} />
      </Field>
      <Field label="Описание" error={form.fieldErrors.description}>
        <textarea value={values.description} onChange={(e) => form.set({ description: e.target.value })} rows={4} />
      </Field>
      <Field label="Статус" required error={form.fieldErrors.status}>
        <DictionarySelect
          dictionary="providerStatuses"
          value={values.status}
          onChange={(status) => form.set({ status })}
          placeholder="выберите"
        />
      </Field>
      <div className="form-actions">
        <button type="submit" className="btn-primary" disabled={form.state.submitting}>
          {form.state.submitting ? "Сохранение…" : "Сохранить"}
        </button>
        <Link to={cancelTo}>Отмена</Link>
      </div>
    </form>
  );
}
