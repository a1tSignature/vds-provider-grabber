const WARNING_TEXT: Record<string, string> = {
  NO_FX_RATE: "курс валюты неизвестен — цена в USD не рассчитана",
};

export function warningText(code: string): string {
  return WARNING_TEXT[code] ?? code;
}
