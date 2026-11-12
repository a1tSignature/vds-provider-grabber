const WARNING_TEXT: Record<string, string> = {
  NO_FX_RATE: "курс валюты неизвестен — цена в USD не рассчитана",
};

/** A response `warnings` code as Russian text; an unknown code is shown as is rather than dropped. */
export function warningText(code: string): string {
  return WARNING_TEXT[code] ?? code;
}
