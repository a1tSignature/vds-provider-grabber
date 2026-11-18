const DEFAULT_NEXT = "/providers";

/**
 * Accepts a decoded same-origin path. Browsers can interpret leading // and /\ as cross-origin URL prefixes.
 * A second decode would turn escaped query characters such as %26 into delimiters.
 */
export function sanitizeNext(raw: string | null): string {
  if (raw === null || raw === "") return DEFAULT_NEXT;
  if (!raw.startsWith("/") || raw[1] === "/" || raw[1] === "\\") return DEFAULT_NEXT;
  return raw;
}
