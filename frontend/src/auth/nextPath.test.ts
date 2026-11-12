import { describe, expect, it } from "vitest";
import { sanitizeNext } from "./nextPath";

describe("sanitizeNext", () => {
  it("accepts a same-origin relative path", () => {
    expect(sanitizeNext("/x")).toBe("/x");
    expect(sanitizeNext("/providers/42?tab=tariffs")).toBe("/providers/42?tab=tariffs");
  });

  it("falls back to /providers for a protocol-relative URL", () => {
    expect(sanitizeNext("//evil")).toBe("/providers");
  });

  it("falls back to /providers for an absolute URL", () => {
    expect(sanitizeNext("https://evil.example/x")).toBe("/providers");
  });

  it("falls back to /providers when absent", () => {
    expect(sanitizeNext(null)).toBe("/providers");
    expect(sanitizeNext("")).toBe("/providers");
  });

  it("falls back to /providers for a backslash protocol-relative URL", () => {
    expect(sanitizeNext("/\\evil.com/x")).toBe("/providers");
  });

  it("does not double-decode an already-decoded value", () => {
    // As useSearchParams().get() would hand it: one decode already applied, '&' still literal
    // inside the query string because the real value came from a serialized URLSearchParams.
    expect(sanitizeNext("/providers?q=a%26b")).toBe("/providers?q=a%26b");
  });
});
