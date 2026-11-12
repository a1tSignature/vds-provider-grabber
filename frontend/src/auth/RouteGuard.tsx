import type { ReactNode } from "react";
import { Navigate, useLocation } from "react-router";
import { useSession } from "./SessionContext";

export function RouteGuard({ children }: { children: ReactNode }) {
  const { session } = useSession();
  const location = useLocation();
  if (session === null) {
    const next = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/login?next=${next}`} replace />;
  }
  return <>{children}</>;
}
