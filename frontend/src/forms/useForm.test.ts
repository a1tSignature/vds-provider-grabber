import { describe, expect, it } from "vitest";
import type { FormAction } from "./formState";
import { createSubmitter } from "./useForm";

interface Values {
  name: string;
}

function setup(values: Values = { name: "x" }) {
  const actions: FormAction<Values>[] = [];
  const submitter = createSubmitter<Values>(() => values, (action) => actions.push(action), ["name"]);
  return { submitter, actions };
}

describe("createSubmitter", () => {
  it("rejects a second submit issued in the same tick, before the first settles", async () => {
    const { submitter } = setup();
    let calls = 0;
    let release: () => void = () => {};
    const action = () => {
      calls += 1;
      return new Promise<void>((resolve) => {
        release = resolve;
      });
    };

    const first = submitter.submit(action);
    const second = submitter.submit(action);
    release();

    expect(await second).toBe(false);
    expect(await first).toBe(true);
    expect(calls).toBe(1);
  });

  it("blocks an empty required field without running the action", async () => {
    const { submitter, actions } = setup({ name: " " });
    let ran = false;
    expect(
      await submitter.submit(async () => {
        ran = true;
      }),
    ).toBe(false);
    expect(ran).toBe(false);
    expect(actions).toEqual([{ type: "blocked", missing: ["name"] }]);
  });

  it("retries the last action after a failure", async () => {
    const { submitter, actions } = setup();
    let attempt = 0;
    const action = async () => {
      attempt += 1;
      if (attempt === 1) throw { kind: "network" };
    };

    expect(await submitter.submit(action)).toBe(false);
    expect(await submitter.retry()).toBe(true);
    expect(attempt).toBe(2);
    expect(actions.map((a) => a.type)).toEqual(["submitting", "failed", "submitting", "succeeded"]);
  });

  it("does nothing on retry before any submit", async () => {
    expect(await setup().submitter.retry()).toBe(false);
  });
});
