import { describe, expect, it } from "vitest";
import { fieldErrorsOf, formErrorMessage, isRetryable } from "./errors";

describe("fieldErrorsOf", () => {
  it("keys details by field and joins several messages for one field", () => {
    const errors = fieldErrorsOf({
      kind: "validation",
      message: "Validation failed",
      details: [
        { field: "websiteUrl", message: "must be http(s)" },
        { field: "slug", message: "too long" },
        { field: "slug", message: "bad chars" },
      ],
    });
    expect(errors).toEqual({ websiteUrl: "must be http(s)", slug: "too long; bad chars" });
  });

  it("is empty for anything but a validation error", () => {
    expect(fieldErrorsOf({ kind: "malformed", message: "Malformed request body" })).toEqual({});
    expect(fieldErrorsOf(undefined)).toEqual({});
  });
});

describe("formErrorMessage", () => {
  it.each([
    [{ kind: "forbidden", message: "x" } as const, "недостаточно прав"],
    [{ kind: "tooLarge", message: "x" } as const, "слишком большой запрос"],
    [{ kind: "conflict", message: "slug already exists" } as const, "slug already exists"],
    [{ kind: "rateLimited", message: "x", retryAfterSeconds: 7 } as const, "слишком много попыток, подождите 7 с"],
  ])("%o → %s", (error, text) => {
    expect(formErrorMessage(error)).toBe(text);
  });

  it("marks a 400 without details as malformed", () => {
    expect(formErrorMessage({ kind: "malformed", message: "Malformed request body" })).toContain("некорректный");
  });

  it("shows nothing for 401 (handled globally) and abort", () => {
    expect(formErrorMessage({ kind: "unauthorized", message: "x" })).toBeUndefined();
    expect(formErrorMessage({ kind: "aborted" })).toBeUndefined();
  });

  it("uses a per-form text for 404", () => {
    expect(formErrorMessage({ kind: "notFound", message: "x" }, { notFound: "нет текущей цены" })).toBe(
      "нет текущей цены",
    );
  });
});

describe("isRetryable", () => {
  it("is true for 5xx and network only", () => {
    expect(isRetryable({ kind: "server", message: "", status: 500 })).toBe(true);
    expect(isRetryable({ kind: "network" })).toBe(true);
    expect(isRetryable({ kind: "conflict", message: "" })).toBe(false);
  });
});
