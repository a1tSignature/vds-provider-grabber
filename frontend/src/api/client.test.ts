import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiClient, type ApiClientError } from "./client";
import { writeStoredSession } from "../auth/session";

function jsonResponse(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", ...headers } });
}

/** Every status-mapping test uses `skipAuth: true`: it exercises the mapping, not token attachment. */
async function sendAndCapture(response: Response | Promise<never>): Promise<ApiClientError> {
  vi.stubGlobal(
    "fetch",
    vi.fn(() => (response instanceof Response ? Promise.resolve(response) : response)),
  );
  try {
    await apiClient.send("POST", "/api/v1/whatever", undefined, { skipAuth: true });
    throw new Error("expected apiClient.send to throw");
  } catch (error) {
    return error as ApiClientError;
  }
}

describe("apiClient status mapping", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("maps 400 with details to validation", async () => {
    const error = await sendAndCapture(
      jsonResponse(400, { message: "bad", details: [{ field: "x", message: "required" }] }),
    );
    expect(error).toEqual({ kind: "validation", message: "bad", details: [{ field: "x", message: "required" }] });
  });

  it("maps 400 without details to malformed", async () => {
    const error = await sendAndCapture(jsonResponse(400, { message: "bad json" }));
    expect(error).toEqual({ kind: "malformed", message: "bad json" });
  });

  it.each([
    [401, "unauthorized"],
    [403, "forbidden"],
    [404, "notFound"],
    [409, "conflict"],
    [413, "tooLarge"],
    [415, "unsupported"],
  ] as const)("maps %i to %s", async (status, kind) => {
    const error = await sendAndCapture(jsonResponse(status, { message: "m" }));
    expect(error).toEqual({ kind, message: "m" });
  });

  it("maps 429 with Retry-After to rateLimited", async () => {
    const error = await sendAndCapture(jsonResponse(429, { message: "slow down" }, { "Retry-After": "7" }));
    expect(error).toEqual({ kind: "rateLimited", message: "slow down", retryAfterSeconds: 7 });
  });

  it("maps 429 without Retry-After to rateLimited with no retryAfterSeconds", async () => {
    const error = await sendAndCapture(jsonResponse(429, { message: "slow down" }));
    expect(error).toEqual({ kind: "rateLimited", message: "slow down" });
  });

  it("maps 5xx to server, keeping the message (with its ref) and the status", async () => {
    const error = await sendAndCapture(jsonResponse(500, { message: "Internal error (ref: abcd1234)" }));
    expect(error).toEqual({ kind: "server", message: "Internal error (ref: abcd1234)", status: 500 });
  });

  it("maps a network failure to network", async () => {
    const error = await sendAndCapture(Promise.reject(new Error("fetch failed")));
    expect(error).toEqual({ kind: "network" });
  });

  it("maps an AbortError to aborted", async () => {
    const abortError = new Error("aborted");
    abortError.name = "AbortError";
    const error = await sendAndCapture(Promise.reject(abortError));
    expect(error).toEqual({ kind: "aborted" });
  });

  it("returns undefined for a 204", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(new Response(null, { status: 204 }))));
    await expect(apiClient.send("DELETE", "/api/v1/whatever", undefined, { skipAuth: true })).resolves.toBeUndefined();
  });

  it("returns the parsed body on a 2xx", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(jsonResponse(200, { ok: true }))));
    await expect(
      apiClient.send<{ ok: boolean }>("POST", "/api/v1/whatever", undefined, { skipAuth: true }),
    ).resolves.toEqual({ ok: true });
  });
});

describe("apiClient local expiry check", () => {
  beforeEach(() => {
    const store = new Map<string, string>();
    vi.stubGlobal("sessionStorage", {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => store.set(key, value),
      removeItem: (key: string) => store.delete(key),
    });
  });

  afterEach(() => vi.unstubAllGlobals());

  it("rejects as unauthorized without ever calling fetch, when the stored session is expired", async () => {
    writeStoredSession({ token: "t", expiresAt: "2000-01-01T00:00:00Z", username: "u", roles: ["VIEWER"] });
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);

    await expect(apiClient.get("/api/v1/dictionaries")).rejects.toMatchObject({ kind: "unauthorized" });
    expect(fetchSpy).not.toHaveBeenCalled();
  });
});
