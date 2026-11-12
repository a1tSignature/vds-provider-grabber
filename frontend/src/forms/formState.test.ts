import { describe, expect, it } from "vitest";
import { formReducer, initialFormState, missingRequired } from "./formState";

const typed = { name: "Hoster", slug: "hoster", note: "" };

describe("formReducer", () => {
  it("keeps every typed value through a network failure and the retry that follows", () => {
    let state = initialFormState(typed);
    state = formReducer(state, { type: "submitting" });
    state = formReducer(state, { type: "failed", error: { kind: "network" } });
    expect(state.values).toEqual(typed);
    expect(state.submitting).toBe(false);
    expect(state.error).toEqual({ kind: "network" });

    state = formReducer(state, { type: "submitting" });
    expect(state.values).toEqual(typed);
    expect(state.error).toBeUndefined();
  });

  it("keeps values through a 5xx", () => {
    const state = formReducer(initialFormState(typed), {
      type: "failed",
      error: { kind: "server", message: "boom", status: 503 },
    });
    expect(state.values).toEqual(typed);
  });

  it("records a success notice and the response warnings, clearing the previous error", () => {
    let state = formReducer(initialFormState(typed), { type: "failed", error: { kind: "network" } });
    state = formReducer(state, { type: "succeeded", outcome: { notice: "ok", warnings: ["W"] } });
    expect(state.error).toBeUndefined();
    expect(state.notice).toBe("ok");
    expect(state.warnings).toEqual(["W"]);
  });

  it("clears a field's missing flag once it is edited", () => {
    let state = formReducer(initialFormState(typed), { type: "blocked", missing: ["name", "slug"] });
    state = formReducer(state, { type: "set", values: { name: "x" } });
    expect(state.missing).toEqual(["slug"]);
  });
});

describe("missingRequired", () => {
  it("flags blank and whitespace-only required strings only", () => {
    expect(missingRequired({ a: "", b: "  ", c: "x", d: "" }, ["a", "b", "c"])).toEqual(["a", "b"]);
  });

  it("ignores non-string values", () => {
    expect(missingRequired({ flag: false }, ["flag"])).toEqual([]);
  });
});
