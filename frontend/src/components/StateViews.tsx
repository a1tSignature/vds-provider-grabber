import { clientErrorMessage, type ApiClientError } from "../api/client";

export function Loading() {
  return <p className="state-view">Загрузка…</p>;
}

export function Empty({ text = "Ничего не найдено" }: { text?: string }) {
  return <p className="state-view">{text}</p>;
}

export function ErrorView({ error, onRetry }: { error: ApiClientError | undefined; onRetry: () => void }) {
  if (error?.kind === "forbidden") return <p className="state-view state-view-error">недостаточно прав</p>;
  if (error?.kind === "notFound") return <p className="state-view state-view-error">не найдено</p>;
  return (
    <div className="state-view state-view-error">
      <p>{serverMessage(error)}</p>
      <button type="button" onClick={onRetry}>
        Повторить
      </button>
    </div>
  );
}

function serverMessage(error: ApiClientError | undefined): string {
  if (error === undefined) return "Ошибка загрузки";
  if (error.kind === "network") return "Нет соединения с сервером";
  return clientErrorMessage(error) || "Ошибка загрузки";
}
