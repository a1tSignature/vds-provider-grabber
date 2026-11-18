import { useState } from "react";
import type { FormErrorOverrides } from "./errors";
import { ConfirmDialog, FormStatus } from "./FormParts";
import { useForm } from "./useForm";

const NO_VALUES = {};

export function DestructiveAction({
  label,
  confirmText,
  run,
  onDone,
  overrides,
}: {
  label: string;
  confirmText: string;
  run: () => Promise<unknown>;
  onDone: () => void;
  overrides?: FormErrorOverrides;
}) {
  const [open, setOpen] = useState(false);
  const form = useForm(NO_VALUES);

  async function confirm() {
    setOpen(false);
    await form.submit(async () => {
      await run();
      onDone();
    });
  }

  return (
    <span className="destructive-action">
      <button type="button" className="btn-danger" onClick={() => setOpen(true)} disabled={form.state.submitting}>
        {label}
      </button>
      {open && (
        <ConfirmDialog
          text={confirmText}
          confirmLabel={label}
          busy={form.state.submitting}
          onConfirm={() => void confirm()}
          onCancel={() => setOpen(false)}
        />
      )}
      <FormStatus
        error={form.state.error}
        onRetry={() => void form.retry()}
        {...(overrides ? { overrides } : {})}
      />
    </span>
  );
}
