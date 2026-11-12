import type { ApiClientError } from "../api/client";

/** What a successful submit reports back to the form: an optional message plus response `warnings`. */
export interface SubmitOutcome {
  notice?: string;
  warnings?: string[];
}

export interface FormState<V> {
  values: V;
  submitting: boolean;
  /** Required fields that were empty on the last submit attempt. */
  missing: string[];
  error?: ApiClientError;
  notice?: string;
  warnings: string[];
}

export type FormAction<V> =
  | { type: "set"; values: Partial<V> }
  | { type: "reset"; values: V }
  | { type: "blocked"; missing: string[] }
  | { type: "submitting" }
  | { type: "failed"; error: ApiClientError }
  | { type: "succeeded"; outcome: SubmitOutcome };

export function initialFormState<V>(values: V): FormState<V> {
  return { values, submitting: false, missing: [], warnings: [] };
}

/**
 * Every transition keeps `values`: a failed or retried submit never loses what was typed. Only an
 * explicit `reset` (e.g. after the caller refetched the record) replaces them.
 */
export function formReducer<V>(state: FormState<V>, action: FormAction<V>): FormState<V> {
  switch (action.type) {
    case "set": {
      const missing = state.missing.filter((field) => !(field in action.values));
      return { ...state, values: { ...state.values, ...action.values }, missing };
    }
    case "reset":
      return initialFormState(action.values);
    case "blocked":
      return { ...withoutResult(state), missing: action.missing };
    case "submitting":
      return { ...withoutResult(state), submitting: true, missing: [] };
    case "failed":
      return { ...withoutResult(state), submitting: false, error: action.error };
    case "succeeded": {
      const next: FormState<V> = { ...withoutResult(state), submitting: false, warnings: action.outcome.warnings ?? [] };
      if (action.outcome.notice !== undefined) next.notice = action.outcome.notice;
      return next;
    }
  }
}

function withoutResult<V>(state: FormState<V>): FormState<V> {
  return { values: state.values, submitting: state.submitting, missing: state.missing, warnings: [] };
}

/** The only client-side check forms make: a required string field must not be blank. */
export function missingRequired<V extends object>(values: V, required: readonly (keyof V & string)[]): string[] {
  return required.filter((field) => {
    const value = values[field];
    return typeof value === "string" && value.trim() === "";
  });
}
