import { useCallback, useEffect, useState } from "react";
import { useSession } from "../../auth/SessionContext";
import { apiClient, type ApiClientError } from "../../api/client";
import type { PageResponse, ProviderSummaryDto } from "../../api/types";

interface QueryState {
  data?: PageResponse<ProviderSummaryDto>;
  error?: ApiClientError;
  loading: boolean;
}

/** The API accepts one country per filter; multiple selections use OR within each filter. */
export function useProvidersListQuery(search: string) {
  const [state, setState] = useState<QueryState>({ loading: true });
  const [nonce, setNonce] = useState(0);
  const { handleUnauthorized } = useSession();

  useEffect(() => {
    const controller = new AbortController();
    const params = new URLSearchParams(search);
    const selectedCountries = params.getAll("locationCountry");
    const registrationCountries = params.getAll("registrationCountry");
    setState({ loading: true });

    async function load() {
      try {
        if (selectedCountries.length <= 1 && registrationCountries.length <= 1) {
          const path = `/api/v1/providers${search ? `?${search}` : ""}`;
          const data = await apiClient.get<PageResponse<ProviderSummaryDto>>(path, { signal: controller.signal });
          setState({ data, loading: false });
          return;
        }

        const page = Number.parseInt(params.get("page") ?? "0", 10);
        const size = Number.parseInt(params.get("size") ?? "20", 10);
        if (selectedCountries.length > 1) params.delete("locationCountry");
        if (registrationCountries.length > 1) params.delete("registrationCountry");
        params.set("page", "0");
        params.set("size", "100");
        const requestPage = (pageNumber: number) => {
          const pageParams = new URLSearchParams(params);
          pageParams.set("page", String(pageNumber));
          return apiClient.get<PageResponse<ProviderSummaryDto>>(`/api/v1/providers?${pageParams}`, {
            signal: controller.signal,
          });
        };

        const first = await requestPage(0);
        const rest = await Promise.all(
          Array.from({ length: Math.max(0, first.totalPages - 1) }, (_, index) => requestPage(index + 1)),
        );
        const selected = new Set(selectedCountries);
        const registrations = new Set(registrationCountries);
        const matches = [first, ...rest]
          .flatMap((result) => result.content)
          .filter((provider) => selected.size <= 1 || provider.locationCountryCodes.some((code) => selected.has(code)))
          .filter((provider) => registrations.size <= 1 || registrations.has(provider.registrationCountry ?? ""));
        const start = page * size;
        setState({
          data: {
            content: matches.slice(start, start + size),
            page,
            size,
            totalElements: matches.length,
            totalPages: Math.ceil(matches.length / size),
          },
          loading: false,
        });
      } catch (error) {
        const apiError = error as ApiClientError;
        if (apiError.kind === "aborted") return;
        if (apiError.kind === "unauthorized") {
          handleUnauthorized();
          return;
        }
        setState({ error: apiError, loading: false });
      }
    }

    void load();
    return () => controller.abort();
  }, [search, nonce, handleUnauthorized]);

  const refetch = useCallback(() => setNonce((current) => current + 1), []);
  return { ...state, refetch };
}
