import { useCallback, useEffect, useState } from "react";

export const THEME_STORAGE_KEY = "vds.theme";

type Theme = "day" | "night";

function readStoredTheme(): Theme | null {
  try {
    const raw = localStorage.getItem(THEME_STORAGE_KEY);
    return raw === "day" || raw === "night" ? raw : null;
  } catch {
    return null;
  }
}

function systemTheme(): Theme {
  return window.matchMedia("(prefers-color-scheme: light)").matches ? "day" : "night";
}

/** With no explicit theme, CSS follows the system preference. Toggling pins the theme and persists it when storage is available. */
export function useTheme(): { theme: Theme; toggle: () => void } {
  const [explicit, setExplicit] = useState<Theme | null>(() => readStoredTheme());

  useEffect(() => {
    if (explicit === null) delete document.documentElement.dataset.theme;
    else document.documentElement.dataset.theme = explicit;
  }, [explicit]);

  const toggle = useCallback(() => {
    setExplicit((current) => {
      const visible = current ?? systemTheme();
      const next: Theme = visible === "night" ? "day" : "night";
      try {
        localStorage.setItem(THEME_STORAGE_KEY, next);
      } catch {
        // The toggle still works for this tab; it just won't survive a reload.
      }
      return next;
    });
  }, []);

  return { theme: explicit ?? systemTheme(), toggle };
}
