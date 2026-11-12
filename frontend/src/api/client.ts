import { clearStoredSession, isExpired, readStoredSession } from "../auth/session";

export interface ApiErrorDetail {
  field: string;
  message: string;
}

export type ApiClientError =
  | { kind: "validation"; message: string; details: ApiErrorDetail[] }
  | { kind: "malformed"; message: string }
  | { kind: "unauthorized"; message: string }
  | { kind: "forbidden"; message: string }
  | { kind: "notFound"; message: string }
  | { kind: "conflict"; message: string }
  | { kind: "tooLarge"; message: string }
  | { kind: "unsupported"; message: string }
  | { kind: "rateLimited"; message: string; retryAfterSeconds?: number }
  | { kind: "server"; message: string; status: number }
  | { kind: "network" }
  | { kind: "aborted" };

interface ApiEnvelope {
  message: string;
  details?: ApiErrorDetail[];
}

interface RequestOptions {
  body?: unknown;
  signal?: AbortSignal;
  /** The login call carries no token and its own 401 is a credential error, not a session wipe. */
  skipAuth?: boolean;
}

async function request<T>(method: string, path: string, options: RequestOptions = {}): Promise<T> {
  return (await requestWithStatus<T>(method, path, options)).data;
}

async function requestWithStatus<T>(
  method: string,
  path: string,
  options: RequestOptions = {},
): Promise<{ status: number; data: T }> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (!options.skipAuth) {
    const session = readStoredSession();
    if (session === null || isExpired(session.expiresAt)) {
      clearStoredSession();
      throw { kind: "unauthorized", message: "сессия истекла, войдите снова" } satisfies ApiClientError;
    }
    headers.Authorization = `Bearer ${session.token}`;
  }

  const init: RequestInit = { method, headers };
  if (options.body !== undefined) init.body = JSON.stringify(options.body);
  if (options.signal !== undefined) init.signal = options.signal;

  let response: Response;
  try {
    response = await fetch(path, init);
  } catch (cause) {
    if (cause instanceof Error && cause.name === "AbortError") {
      throw { kind: "aborted" } satisfies ApiClientError;
    }
    throw { kind: "network" } satisfies ApiClientError;
  }

  if (response.status === 204) return { status: 204, data: undefined as T };
  if (response.ok) return { status: response.status, data: (await response.json()) as T };
  throw toClientError(response.status, await readEnvelope(response), response.headers);
}

async function readEnvelope(response: Response): Promise<ApiEnvelope | null> {
  try {
    return (await response.json()) as ApiEnvelope;
  } catch {
    return null;
  }
}

function toClientError(status: number, envelope: ApiEnvelope | null, headers: Headers): ApiClientError {
  const message = envelope?.message ?? "";
  switch (status) {
    case 400:
      return envelope?.details !== undefined
        ? { kind: "validation", message, details: envelope.details }
        : { kind: "malformed", message };
    case 401:
      return { kind: "unauthorized", message };
    case 403:
      return { kind: "forbidden", message };
    case 404:
      return { kind: "notFound", message };
    case 409:
      return { kind: "conflict", message };
    case 413:
      return { kind: "tooLarge", message };
    case 415:
      return { kind: "unsupported", message };
    case 429: {
      const retryAfterHeader = headers.get("Retry-After");
      return retryAfterHeader === null
        ? { kind: "rateLimited", message }
        : { kind: "rateLimited", message, retryAfterSeconds: Number.parseInt(retryAfterHeader, 10) };
    }
    default:
      return { kind: "server", message, status };
  }
}

/** `aborted` carries no message because nothing should ever show it as an error; everything else has one. */
export function clientErrorMessage(error: ApiClientError): string {
  return error.kind === "aborted" || error.kind === "network" ? "" : error.message;
}

export const apiClient = {
  get: <T,>(path: string, options: { signal?: AbortSignal } = {}): Promise<T> => request<T>("GET", path, options),
  send: <T,>(
    method: "POST" | "PUT" | "PATCH" | "DELETE",
    path: string,
    body?: unknown,
    options: { signal?: AbortSignal; skipAuth?: boolean } = {},
  ): Promise<T> => request<T>(method, path, { ...options, body }),
  /** Like `send`, for the few endpoints whose success status carries meaning (price write: 201 vs 200). */
  sendWithStatus: <T,>(
    method: "POST" | "PUT" | "PATCH" | "DELETE",
    path: string,
    body?: unknown,
    options: { signal?: AbortSignal; skipAuth?: boolean } = {},
  ): Promise<{ status: number; data: T }> => requestWithStatus<T>(method, path, { ...options, body }),
};
