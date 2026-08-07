"use client";

import { useEffect, useRef, type ReactNode } from "react";

/**
 * One dialog field: its label, its control, and a line of hint or error underneath.
 *
 * The label and the control are wrapped in a `<label>`, which associates them without
 * going through an `id`. The hint and the error are deliberately *outside* that
 * `<label>`: inside, they would enter the control's accessible name, and a `<select>`
 * would end up called "Parent team Left empty, the server derives it…". The error
 * carries `role="alert"` so it is announced when it appears.
 *
 * `aria-invalid` stays the caller's job, on the caller's own control: this component
 * does not reach into the children it is handed.
 */
export function Field({
  label,
  error,
  hint,
  children,
}: {
  label: string;
  error?: string | null;
  hint?: string;
  children: ReactNode;
}) {
  return (
    <div className="dialog-field">
      <label className="dialog-field-label">
        <span>{label}</span>
        {children}
      </label>
      {error ? (
        <span className="dialog-field-error" role="alert">
          {error}
        </span>
      ) : hint ? (
        <span className="dialog-field-hint">{hint}</span>
      ) : null}
    </div>
  );
}

/**
 * The wrapper the three dialogs share: the backdrop, the panel, the title, the body
 * and the two buttons.
 *
 * It rewrites the four lines of `.backdrop > .panel` rather than borrowing the
 * `Backdrop` from `overlays.tsx`, because it needs three things that one does not
 * take: `role="dialog"`, a `tabIndex` so it can own the focus, and a `keydown`
 * boundary. Five optional props on `Backdrop` would cost more than these four lines.
 *
 * `pending` disables both buttons. It covers the two cases where submitting makes no
 * sense: the request is in flight, or the data the dialog edits is not there yet.
 */
export function DialogFrame({
  title,
  onClose,
  onSubmit,
  submitLabel,
  submitDanger,
  pending,
  error,
  children,
}: {
  title: string;
  onClose: () => void;
  onSubmit: () => void;
  submitLabel: string;
  submitDanger?: boolean;
  pending?: boolean;
  error?: string | null;
  children: ReactNode;
}) {
  const panelRef = useRef<HTMLDivElement>(null);

  /**
   * The dialog takes the focus if it does not already hold it. Without this, a dialog
   * opened from a menu would leave the focus on the document body, and Escape would
   * go to the `window` handler in `page.tsx`, which does not read it as a dialog
   * keystroke. Fields carrying `autoFocus` win: they focused during the same commit,
   * before this effect ran.
   */
  useEffect(() => {
    const panel = panelRef.current;
    if (panel && !panel.contains(document.activeElement)) panel.focus();
  }, []);

  return (
    <div className="backdrop" onClick={onClose}>
      <div
        ref={panelRef}
        className="panel"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        tabIndex={-1}
        onClick={(event) => event.stopPropagation()}
        onKeyDown={(event) => {
          event.stopPropagation();
          if (event.key === "Escape") {
            event.preventDefault();
            onClose();
          }
        }}
      >
        <form
          onSubmit={(event) => {
            event.preventDefault();
            if (!pending) onSubmit();
          }}
        >
          <div className="panel-header">
            <strong style={{ flex: 1 }}>{title}</strong>
          </div>

          <div className="dialog-body">{children}</div>

          <div className="dialog-footer">
            {error ? (
              <span className="dialog-footer-error" role="alert">
                {error}
              </span>
            ) : (
              <span className="dialog-footer-spacer" />
            )}
            <button type="button" className="button" onClick={onClose} disabled={pending}>
              Cancel
            </button>
            <button
              type="submit"
              className={submitDanger ? "button button-danger" : "button button-primary"}
              disabled={pending}
            >
              {submitLabel}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
