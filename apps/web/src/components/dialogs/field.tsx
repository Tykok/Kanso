"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import { Button } from "@/components/ui/button";

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
    <div className="flex flex-col gap-1">
      <label className="flex flex-col gap-1">
        <span className="text-11 font-medium tracking-wide text-faint uppercase">{label}</span>
        {children}
      </label>
      {error ? (
        <span className="text-11 text-urgent" role="alert">
          {error}
        </span>
      ) : hint ? (
        <span className="text-11 text-faint">{hint}</span>
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
   * Whatever held the focus when this dialog was asked for.
   *
   * Read in a lazy state initialiser, which runs during the first render — before
   * React commits, so before `autoFocus` and the effect below have moved the focus
   * anywhere. An effect would only ever see the panel.
   */
  const [opener] = useState<HTMLElement | null>(() =>
    typeof document === "undefined" ? null : (document.activeElement as HTMLElement | null),
  );

  /**
   * The dialog takes the focus if it does not already hold it. Without this, a dialog
   * opened from a menu would leave the focus on the document body, and Escape would
   * go to the `window` handler in `page.tsx`, which does not read it as a dialog
   * keystroke. Fields carrying `autoFocus` win: they focused during the same commit,
   * before this effect ran.
   *
   * On the way out the focus goes back where it came from, which is what `menu.tsx`
   * already established for its own popover: closing an overlay must not drop a
   * keyboard user on `<body>`, with the next Tab starting again from the top of the
   * document. `isConnected` is the guard for the case this cannot serve — the row
   * that opened the dialog was what the dialog deleted.
   *
   * A full focus trap is still missing, here as in `overlays.tsx`; that is a separate
   * piece of work and this is not it.
   */
  useEffect(() => {
    const panel = panelRef.current;
    if (panel && !panel.contains(document.activeElement)) panel.focus();
    return () => {
      if (opener?.isConnected) opener.focus();
    };
  }, [opener]);

  return (
    <div
      className="fixed inset-0 z-20 flex items-start justify-center bg-black/34 pt-[12vh]"
      onClick={onClose}
    >
      <div
        ref={panelRef}
        className="w-[min(560px,92vw)] overflow-hidden rounded-panel bg-popover shadow-float outline-none"
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
          <div data-testid="panel-header" className="px-4 py-3">
            <strong className="text-15 font-medium text-foreground">{title}</strong>
          </div>

          <div className="flex max-h-[60vh] flex-col gap-3 overflow-y-auto px-4 py-3.5">
            {children}
          </div>

          <div className="flex items-center gap-2 border-t border-border px-4 py-2.5">
            {error ? (
              <span className="min-w-0 flex-1 text-11 text-urgent" role="alert">
                {error}
              </span>
            ) : (
              <span className="flex-1" />
            )}
            <Button type="button" variant="outline" size="sm" onClick={onClose} disabled={pending}>
              Cancel
            </Button>
            <Button
              type="submit"
              variant={submitDanger ? "destructive" : "default"}
              size="sm"
              disabled={pending}
            >
              {submitLabel}
            </Button>
          </div>
        </form>
      </div>
    </div>
  );
}
