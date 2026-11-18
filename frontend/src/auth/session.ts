const SESSION_STORAGE_KEY = "vds.session";

/** Treat tokens as expired shortly before their deadline to allow time for requests in flight. */
const EXPIRY_SAFETY_MARGIN_MS = 30_000;

export interface SessionData {
  token: string;
  expiresAt: string;
  username: string;
  roles: string[];
}

export function isExpired(expiresAt: string, now: number = Date.now()): boolean {
  const expiryMs = Date.parse(expiresAt);
  if (Number.isNaN(expiryMs)) return true;
  return expiryMs - now <= EXPIRY_SAFETY_MARGIN_MS;
}

export function readStoredSession(): SessionData | null {
  let raw: string | null;
  try {
    raw = sessionStorage.getItem(SESSION_STORAGE_KEY);
  } catch {
    return null;
  }
  if (raw === null) return null;
  const parsed = parseSession(raw);
  if (parsed === null || isExpired(parsed.expiresAt)) {
    clearStoredSession();
    return null;
  }
  return parsed;
}

export function writeStoredSession(session: SessionData): void {
  try {
    sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify(session));
  } catch {
    // A private-browsing quota failure leaves the in-memory session as the source of truth.
  }
}

export function clearStoredSession(): void {
  try {
    sessionStorage.removeItem(SESSION_STORAGE_KEY);
  } catch {
  }
}

function parseSession(raw: string): SessionData | null {
  try {
    const value = JSON.parse(raw) as Partial<SessionData>;
    if (
      typeof value.token === "string" &&
      typeof value.expiresAt === "string" &&
      typeof value.username === "string" &&
      Array.isArray(value.roles) &&
      value.roles.every((role) => typeof role === "string")
    ) {
      return { token: value.token, expiresAt: value.expiresAt, username: value.username, roles: value.roles };
    }
    return null;
  } catch {
    return null;
  }
}
