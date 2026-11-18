import type { ApiClientError } from "../api/client";

/** `400` `details[]` keyed by the request field they name; several messages for one field are joined. */
export function fieldErrorsOf(error: ApiClientError | undefined): Record<string, string> {
  if (error?.kind !== "validation") return {};
  const result: Record<string, string> = {};
  for (const detail of error.details) {
    result[detail.field] = result[detail.field] === undefined ? detail.message : `${result[detail.field]}; ${detail.message}`;
  }
  return result;
}

export function isRetryable(error: ApiClientError | undefined): boolean {
  return error?.kind === "server" || error?.kind === "network";
}

export interface FormErrorOverrides {
  notFound?: string;
}

/** Leaves 401 handling to the session and suppresses errors for aborted requests. */
export function formErrorMessage(
  error: ApiClientError | undefined,
  overrides: FormErrorOverrides = {},
): string | undefined {
  if (error === undefined) return undefined;
  switch (error.kind) {
    case "validation":
      return error.message || "проверьте поля формы";
    case "malformed":
      return `запрос отклонён как некорректный${error.message ? `: ${error.message}` : ""}`;
    case "conflict":
      return error.message || "конфликт с существующими данными";
    case "forbidden":
      return "недостаточно прав";
    case "notFound":
      return overrides.notFound ?? "не найдено";
    case "tooLarge":
      return "слишком большой запрос";
    case "unsupported":
      return error.message || "неподдерживаемый формат запроса";
    case "rateLimited":
      return error.retryAfterSeconds !== undefined
        ? `слишком много попыток, подождите ${error.retryAfterSeconds} с`
        : "слишком много попыток, подождите";
    case "server":
      return `ошибка сервера${error.message ? `: ${error.message}` : ""}`;
    case "network":
      return "нет соединения с сервером";
    case "unauthorized":
    case "aborted":
      return undefined;
  }
}
