import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import { useLocation, useNavigate } from "react-router";
import { clearStoredSession, readStoredSession, writeStoredSession, type SessionData } from "./session";

interface SessionContextValue {
  session: SessionData | null;
  login: (session: SessionData) => void;
  logout: () => void;
  /** Wipes the session and sends the user back to the login screen with the expiry notice. */
  handleUnauthorized: () => void;
}

const SessionContext = createContext<SessionContextValue | null>(null);

export function useSession(): SessionContextValue {
  const value = useContext(SessionContext);
  if (value === null) throw new Error("useSession must be used inside a SessionProvider");
  return value;
}

export function canEdit(roles: string[]): boolean {
  return roles.includes("EDITOR") || roles.includes("ADMIN");
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const navigate = useNavigate();
  const location = useLocation();
  const [session, setSession] = useState<SessionData | null>(() => readStoredSession());

  const login = useCallback((next: SessionData) => {
    writeStoredSession(next);
    setSession(next);
  }, []);

  const logout = useCallback(() => {
    clearStoredSession();
    setSession(null);
    navigate("/login", { replace: true });
  }, [navigate]);

  const handleUnauthorized = useCallback(() => {
    clearStoredSession();
    setSession(null);
    const next = encodeURIComponent(location.pathname + location.search);
    navigate(`/login?next=${next}&notice=expired`, { replace: true });
  }, [navigate, location]);

  const value = useMemo(
    () => ({ session, login, logout, handleUnauthorized }),
    [session, login, logout, handleUnauthorized],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}
