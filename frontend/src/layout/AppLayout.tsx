import { Link, NavLink, Outlet } from "react-router";
import { DictionariesProvider } from "../dictionaries/DictionariesContext";
import { useTheme } from "../lib/useTheme";
import { canEdit, useSession } from "../auth/SessionContext";
import { FxRefreshButton } from "./FxRefreshButton";

export function AppLayout() {
  const { session, logout } = useSession();
  const { theme, toggle } = useTheme();

  return (
    <div className="app-shell">
      <header className="app-header">
        <Link to="/providers" className="app-brand">
          VDS Catalog
        </Link>
        <nav className="app-nav">
          <NavLink to="/providers">Провайдеры</NavLink>
          <NavLink to="/tariffs">Тарифы</NavLink>
        </nav>
        <div className="app-header-fx-slot">{session !== null && canEdit(session.roles) && <FxRefreshButton />}</div>
        <div className="app-header-user">
          <button type="button" onClick={toggle}>
            {theme === "night" ? "Светлая тема" : "Тёмная тема"}
          </button>
          {session === null ? (
            <Link to="/login">Войти</Link>
          ) : (
            <>
              <span>
                {session.username} ({session.roles.join(", ")})
              </span>
              <button type="button" onClick={logout}>
                Выйти
              </button>
            </>
          )}
        </div>
      </header>
      <main className="app-content">
        <DictionariesProvider>
          <Outlet />
        </DictionariesProvider>
      </main>
      <footer className="app-footer">
        Rates By{" "}
        <a href="https://www.exchangerate-api.com" target="_blank" rel="noopener noreferrer">
          Exchange Rate API
        </a>
      </footer>
    </div>
  );
}
