import { useApiMutation } from "../api/hooks";
import { useForm } from "../forms/useForm";
import { formErrorMessage } from "../forms/errors";

interface FxRefreshResponse {
  inserted: number;
}

const NO_VALUES = {};

/** Refreshing rates affects future conversions. Stored `amountUsd` values stay fixed. */
export function FxRefreshButton() {
  const form = useForm(NO_VALUES);
  const { mutate } = useApiMutation<void, FxRefreshResponse>("POST", "/api/v1/admin/fx/refresh");
  const { error, notice, submitting } = form.state;

  function refresh() {
    void form.submit(async () => {
      const { inserted } = await mutate();
      return { notice: `курсы обновлены: ${inserted} новых` };
    });
  }

  const errorText =
    error?.kind === "server" && error.status === 502 ? "сервис курсов валют недоступен" : formErrorMessage(error);

  return (
    <span className="fx-refresh">
      <button
        type="button"
        onClick={refresh}
        disabled={submitting}
        title="Загружает свежие курсы для новых цен; уже сохранённые цены в USD не пересчитываются"
      >
        {submitting ? "Обновление курсов…" : "Обновить курсы"}
      </button>
      {!submitting && errorText !== undefined && (
        <span className="field-error" role="alert">
          {errorText}
        </span>
      )}
      {!submitting && error === undefined && notice !== undefined && (
        <span className="muted" role="status">
          {notice}
        </span>
      )}
    </span>
  );
}
