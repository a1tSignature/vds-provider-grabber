import { useCallback, useReducer, useRef } from "react";
import type { ApiClientError } from "../api/client";
import { fieldErrorsOf } from "./errors";
import {
  formReducer,
  initialFormState,
  missingRequired,
  type FormAction,
  type FormState,
  type SubmitOutcome,
} from "./formState";

export type SubmitAction<V> = (values: V) => Promise<SubmitOutcome | void>;

export interface Submitter<V> {
  /** Blocks on an empty required field, ignores a second submit while one is in flight. */
  submit: (action: SubmitAction<V>) => Promise<boolean>;
  /** Repeats the last submit with the current values (the retry banner's button). */
  retry: () => Promise<boolean>;
}

/**
 * The submit/retry logic, outside React so it is testable without a DOM. The in-flight flag is a
 * plain variable set synchronously: a second click in the same tick, before React re-renders the
 * disabled button, is still rejected.
 */
export function createSubmitter<V extends object>(
  getValues: () => V,
  dispatch: (action: FormAction<V>) => void,
  required: readonly (keyof V & string)[],
): Submitter<V> {
  let inFlight = false;
  let lastAction: SubmitAction<V> | null = null;

  async function submit(action: SubmitAction<V>): Promise<boolean> {
    if (inFlight) return false;
    const values = getValues();
    const missing = missingRequired(values, required);
    if (missing.length > 0) {
      dispatch({ type: "blocked", missing });
      return false;
    }
    inFlight = true;
    lastAction = action;
    dispatch({ type: "submitting" });
    try {
      const outcome = await action(values);
      dispatch({ type: "succeeded", outcome: outcome ?? {} });
      return true;
    } catch (cause) {
      dispatch({ type: "failed", error: cause as ApiClientError });
      return false;
    } finally {
      inFlight = false;
    }
  }

  return {
    submit,
    retry: () => (lastAction === null ? Promise.resolve(false) : submit(lastAction)),
  };
}

export interface FormApi<V> extends Submitter<V> {
  state: FormState<V>;
  set: (values: Partial<V>) => void;
  reset: (values: V) => void;
  /** Field name → message: a backend `details[]` entry, or "обязательное поле" after a blocked submit. */
  fieldErrors: Record<string, string>;
}

/** `required` is read once, on the first render. */
export function useForm<V extends object>(initial: V, required: readonly (keyof V & string)[] = []): FormApi<V> {
  const [state, dispatch] = useReducer(formReducer<V>, initial, initialFormState);
  const stateRef = useRef(state);
  stateRef.current = state;
  const submitter = useRef<Submitter<V> | null>(null);
  submitter.current ??= createSubmitter(() => stateRef.current.values, dispatch, required);

  const set = useCallback((values: Partial<V>) => dispatch({ type: "set", values }), []);
  const reset = useCallback((values: V) => dispatch({ type: "reset", values }), []);

  const fieldErrors = fieldErrorsOf(state.error);
  for (const field of state.missing) fieldErrors[field] ??= "обязательное поле";

  return { state, set, reset, submit: submitter.current.submit, retry: submitter.current.retry, fieldErrors };
}
