import { useCallback, useEffect, useRef, useState } from "react";
import { useSession } from "../auth/SessionContext";
import { apiClient, type ApiClientError } from "./client";

interface QueryState<T> {
  data?: T;
  error?: ApiClientError;
  loading: boolean;
}

/** Refetch when the path changes. Null skips the request, and superseded requests are aborted without surfacing an error. */
export function useApiQuery<T>(path: string | null): {
  data: T | undefined;
  error: ApiClientError | undefined;
  loading: boolean;
  refetch: () => void;
} {
  const [state, setState] = useState<QueryState<T>>({ loading: path !== null });
  const [nonce, setNonce] = useState(0);
  const { handleUnauthorized } = useSession();

  useEffect(() => {
    if (path === null) {
      setState({ loading: false });
      return;
    }
    const controller = new AbortController();
    setState({ loading: true });
    apiClient.get<T>(path, { signal: controller.signal }).then(
      (data) => setState({ data, loading: false }),
      (error: ApiClientError) => {
        if (error.kind === "aborted") return;
        if (error.kind === "unauthorized") {
          handleUnauthorized();
          return;
        }
        setState({ error, loading: false });
      },
    );
    return () => controller.abort();
  }, [path, nonce]);

  const refetch = useCallback(() => setNonce((current) => current + 1), []);
  return { data: state.data, error: state.error, loading: state.loading, refetch };
}

interface MutationState<T> {
  data?: T;
  error?: ApiClientError;
  loading: boolean;
}

/** Use `skipAuth` for login, where a 401 indicates rejected credentials and leaves session handling to the caller. */
export function useApiMutation<TBody, TResult>(
  method: "POST" | "PUT" | "PATCH" | "DELETE",
  path: string,
  options: { skipAuth?: boolean } = {},
): {
  mutate: (body?: TBody) => Promise<TResult>;
  /** `mutate` that also resolves the success status, for endpoints where 200 and 201 differ. */
  mutateWithStatus: (body?: TBody) => Promise<{ status: number; data: TResult }>;
  data: TResult | undefined;
  error: ApiClientError | undefined;
  loading: boolean;
} {
  const [state, setState] = useState<MutationState<TResult>>({ loading: false });
  const { handleUnauthorized } = useSession();
  const skipAuth = options.skipAuth ?? false;
  const optionsRef = useRef({ skipAuth });
  optionsRef.current = { skipAuth };

  const mutateWithStatus = useCallback(
    async (body?: TBody): Promise<{ status: number; data: TResult }> => {
      setState({ loading: true });
      try {
        const result = await apiClient.sendWithStatus<TResult>(method, path, body, {
          skipAuth: optionsRef.current.skipAuth,
        });
        setState({ data: result.data, loading: false });
        return result;
      } catch (cause) {
        const error = cause as ApiClientError;
        if (!optionsRef.current.skipAuth && error.kind === "unauthorized") handleUnauthorized();
        setState({ error, loading: false });
        throw error;
      }
    },
    [method, path, handleUnauthorized],
  );

  const mutate = useCallback(
    async (body?: TBody): Promise<TResult> => (await mutateWithStatus(body)).data,
    [mutateWithStatus],
  );

  return { mutate, mutateWithStatus, data: state.data, error: state.error, loading: state.loading };
}
