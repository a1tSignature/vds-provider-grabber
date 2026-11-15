import type { EvidenceItemDto } from "../api/types";

const MAX_ITEMS = 3;

function isHttpUrl(url: string): boolean {
  try {
    const { protocol } = new URL(url);
    return protocol === "http:" || protocol === "https:";
  } catch {
    return false;
  }
}

/** Snippets come from third-party pages: rendered as text, and only an http(s) URL becomes a link. */
export function EvidenceList({ evidence }: { evidence: EvidenceItemDto[] }) {
  if (evidence.length === 0) return null;
  return (
    <ul className="evidence-list">
      {evidence.slice(0, MAX_ITEMS).map((item, index) => (
        <li key={`${index}-${item.url}`}>
          {isHttpUrl(item.url) ? (
            <a href={item.url} target="_blank" rel="noopener noreferrer">
              {item.url}
            </a>
          ) : (
            <span>{item.url}</span>
          )}
          {item.kind !== "" && <span className="muted"> · {item.kind}</span>}
          {item.snippet !== "" && <q className="evidence-snippet">{item.snippet}</q>}
        </li>
      ))}
    </ul>
  );
}
