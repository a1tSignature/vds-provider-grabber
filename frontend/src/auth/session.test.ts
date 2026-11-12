import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { clearStoredSession, isExpired, readStoredSession, writeStoredSession, type SessionData } from "./session";

const VALID: SessionData = { token: "t", expiresAt: "2099-01-01T00:00:00Z", username: "u", roles: ["VIEWER"] };

function fakeSessionStorage() {
  const store = new Map<string, string>();
  return {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => store.set(key, value),
    removeItem: (key: string) => store.delete(key),
  };
}

describe("isExpired", () => {
  const now = Date.parse("2026-01-01T00:00:00Z");

  it("is not expired strictly outside the 30s margin", () => {
    expect(isExpired("2026-01-01T00:00:31Z", now)).toBe(false);
  });

  it("is expired exactly at the 30s margin", () => {
    expect(isExpired("2026-01-01T00:00:30Z", now)).toBe(true);
  });

  it("is expired once already in the past", () => {
    expect(isExpired("2025-12-31T23:59:00Z", now)).toBe(true);
  });

  it("treats an unparseable timestamp as expired", () => {
    expect(isExpired("not-a-date", now)).toBe(true);
  });
});

describe("stored session", () => {
  beforeEach(() => {
    vi.stubGlobal("sessionStorage", fakeSessionStorage());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("round-trips a valid session", () => {
    writeStoredSession(VALID);
    expect(readStoredSession()).toEqual(VALID);
  });

  it("drops and clears an expired session instead of returning it", () => {
    writeStoredSession({ ...VALID, expiresAt: "2000-01-01T00:00:00Z" });
    expect(readStoredSession()).toBeNull();
    expect(readStoredSession()).toBeNull();
  });

  it("returns null when nothing is stored", () => {
    expect(readStoredSession()).toBeNull();
  });

  it("clearStoredSession removes it", () => {
    writeStoredSession(VALID);
    clearStoredSession();
    expect(readStoredSession()).toBeNull();
  });
});
