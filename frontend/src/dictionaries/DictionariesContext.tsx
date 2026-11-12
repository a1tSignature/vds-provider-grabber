import { createContext, useContext, type ReactNode } from "react";
import { useApiQuery } from "../api/hooks";
import type { DictionariesDto } from "../api/types";
import { ErrorView, Loading } from "../components/StateViews";

const DictionariesContext = createContext<DictionariesDto | null>(null);

export function useDictionaries(): DictionariesDto {
  const value = useContext(DictionariesContext);
  if (value === null) throw new Error("useDictionaries must be used inside a DictionariesProvider");
  return value;
}

/** Loaded once, after login (the endpoint needs a token): every enum-valued control reads from this. */
export function DictionariesProvider({ children }: { children: ReactNode }) {
  const { data, error, loading, refetch } = useApiQuery<DictionariesDto>("/api/v1/dictionaries");
  if (loading) return <Loading />;
  if (error !== undefined || data === undefined) return <ErrorView error={error} onRetry={refetch} />;
  return <DictionariesContext.Provider value={data}>{children}</DictionariesContext.Provider>;
}
