import { useEffect, useRef, type ReactNode } from "react";
import type { ApiClientError } from "../api/client";
import { canEdit, useSession } from "../auth/SessionContext";
import { formErrorMessage, isRetryable, type FormErrorOverrides } from "./errors";
import { warningText } from "./warnings";

export function Field({
  label,
  error,
  required = false,
  children,
}: {
  label: string;
  error?: string | undefined;
  required?: boolean;
  children: ReactNode;
}) {
  return (
    <label className="form-field">
      <span className="form-field-label">
        {label}
        {required && " *"}
      </span>
      {children}
      {error !== undefined && <span className="field-error">{error}</span>}
    </label>
  );
}

interface FormStatusProps {
  error?: ApiClientError | undefined;
  notice?: string | undefined;
  warnings?: string[];
  onRetry: () => void;
  /** Field names rendered inline; a `details[]` entry for any other field is listed here instead. */
  fields?: readonly string[];
  overrides?: FormErrorOverrides;
}

/** The form-level slot shows errors for fields with no input. Retryable failures retain the entered values. */
export function FormStatus({ error, notice, warnings = [], onRetry, fields = [], overrides = {} }: FormStatusProps) {
  const message = formErrorMessage(error, overrides);
  const orphanDetails =
    error?.kind === "validation" ? error.details.filter((detail) => !fields.includes(detail.field)) : [];
  return (
    <>
      {message !== undefined && (
        <div className="form-banner form-banner--error" role="alert">
          <p>{message}</p>
          {orphanDetails.length > 0 && (
            <ul>
              {orphanDetails.map((detail) => (
                <li key={`${detail.field}:${detail.message}`}>
                  {detail.field}: {detail.message}
                </li>
              ))}
            </ul>
          )}
          {isRetryable(error) && (
            <p>
              Введённые данные сохранены в форме.{" "}
              <button type="button" onClick={onRetry}>
                Повторить
              </button>
            </p>
          )}
        </div>
      )}
      {(notice !== undefined || warnings.length > 0) && (
        <div className="form-banner form-banner--notice" role="status">
          {notice !== undefined && <p>{notice}</p>}
          {warnings.map((code) => (
            <p key={code} className="form-warning">
              {warningText(code)}
            </p>
          ))}
        </div>
      )}
    </>
  );
}

export function ConfirmDialog({
  text,
  confirmLabel,
  busy,
  onConfirm,
  onCancel,
}: {
  text: string;
  confirmLabel: string;
  busy: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const cancelRef = useRef<HTMLButtonElement>(null);
  useEffect(() => cancelRef.current?.focus(), []);
  return (
    <div className="confirm-overlay">
      <div
        className="confirm-dialog"
        role="alertdialog"
        aria-modal="true"
        aria-label={confirmLabel}
        onKeyDown={(event) => {
          if (event.key === "Escape" && !busy) onCancel();
        }}
      >
        <p>{text}</p>
        <div className="form-actions">
          <button type="button" ref={cancelRef} onClick={onCancel} disabled={busy}>
            Отмена
          </button>
          <button type="button" className="btn-danger" onClick={onConfirm} disabled={busy}>
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}

/** Editor affordances render only for EDITOR/ADMIN; the backend still authorises every write. */
export function EditorOnly({ children, fallback = null }: { children: ReactNode; fallback?: ReactNode }) {
  const { session } = useSession();
  return <>{session !== null && canEdit(session.roles) ? children : fallback}</>;
}

export function useCanEdit(): boolean {
  const { session } = useSession();
  return session !== null && canEdit(session.roles);
}
