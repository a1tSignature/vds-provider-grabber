const DEFAULT_NEXT = "/providers";

/**
 * A `?next=` value is only honoured when it is a same-origin, single-leading-slash path: a
 * double slash (`//evil`) is a protocol-relative URL, a leading `/\` is too (`new URL` and the
 * History API treat a backslash the same as a forward slash there), and anything without a
 * leading slash may be an absolute URL to another origin.
 *
 * `raw` must already be decoded (the real caller is `useSearchParams().get("next")`, which
 * decodes once) -- decoding again here would corrupt a legitimately escaped character inside an
 * otherwise valid path, e.g. turning `/providers?q=a%26b` into two query parameters instead of one.
 */
export function sanitizeNext(raw: string | null): string {
  if (raw === null || raw === "") return DEFAULT_NEXT;
  if (!raw.startsWith("/") || raw[1] === "/" || raw[1] === "\\") return DEFAULT_NEXT;
  return raw;
}
