"use client";

import type { KeyboardEvent, ReactNode } from "react";
import { Seal } from "../ui/seal";

export type StepId = "account" | "notion" | "google" | "preferences";

export const STEP_NAMES: Record<StepId, string> = {
  account: "Account",
  notion: "Notion",
  google: "Google sign-in",
  preferences: "Preferences",
};

export function SetupPage({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-screen flex-col items-center px-5 py-10">
      {/*
       * Auto margins centre the card while it fits and collapse to zero when it does
       * not. `justify-content: center` cannot do the second half: it pushes the top of
       * a tall card above the scroll origin, out of reach.
       */}
      <div className="my-auto flex w-full max-w-[600px] flex-col gap-4">
        {/* Smaller than the sign-in screen's: this one sits above a card that already
            has a heading of its own, so it introduces rather than announces. */}
        <div className="flex items-center gap-2.5">
          <Seal size={18} title="Kanso 簡素" />
        </div>
        {children}
      </div>
    </div>
  );
}

export function StepRail({ plan, index }: { plan: StepId[]; index: number }) {
  const done = index >= plan.length;

  return (
    <nav className="mb-2.5 flex flex-col gap-1" aria-label="Setup progress">
      <span
        className="text-11 uppercase tracking-wide text-faint"
        style={{ fontFamily: "var(--font-mono)" }}
      >
        {done ? "All steps done" : `Step ${index + 1} of ${plan.length}`}
      </span>
      <ol className="flex flex-wrap text-12">
        {plan.map((step, position) => {
          const color =
            done || position < index
              ? "text-muted-foreground"
              : position === index
                ? "font-medium text-foreground"
                : "text-faint";
          return (
            <li
              key={step}
              aria-current={position === index ? "step" : undefined}
              className={`${color} after:mx-[7px] after:text-border after:content-['·'] last:after:content-none`}
            >
              {STEP_NAMES[step]}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}

/**
 * Escape must not undo a half-filled form. Stepping out of the field is the most
 * it is allowed to do here — everything else on this page is a one-way write.
 */
function escapeBlurs(event: KeyboardEvent<HTMLFormElement>) {
  if (event.key === "Escape") (event.target as HTMLElement).blur?.();
}

type FormCardProps = {
  /** The progress rail, absent on the sign-in page which is not a wizard. */
  head?: ReactNode;
  title: string;
  intro?: ReactNode;
  children: ReactNode;
  /** An API failure that belongs to the step rather than to one input. */
  error?: string | null;
  onSubmit: () => void;
  primaryLabel: string;
  pending?: boolean;
  onBack?: () => void;
  onSkip?: () => void;
  skipLabel?: string;
};

/**
 * One card, one form, one primary action, so Enter always does the obvious thing
 * from wherever the cursor happens to be.
 */
export function FormCard({
  head,
  title,
  intro,
  children,
  error,
  onSubmit,
  primaryLabel,
  pending,
  onBack,
  onSkip,
  skipLabel = "Skip",
}: FormCardProps) {
  return (
    <form
      className="flex flex-col overflow-hidden rounded-panel border border-border bg-card shadow-panel"
      onSubmit={(event) => {
        event.preventDefault();
        onSubmit();
      }}
      onKeyDown={escapeBlurs}
    >
      <div className="border-b border-border px-[18px] py-3.5">
        {head}
        <h1 className="m-0 text-15 font-medium tracking-tight">{title}</h1>
        {intro && <p className="m-0 mt-1.5 text-13 text-muted-foreground">{intro}</p>}
      </div>

      <div className="flex flex-col gap-4 px-[18px] py-[18px]">
        {children}
        {error && (
          <p className="m-0 text-12 text-urgent" role="alert">
            {error}
          </p>
        )}
      </div>

      <div className="flex items-center gap-2 border-t border-border px-3.5 py-2.5">
        {onBack && (
          <button type="button" className="button" onClick={onBack}>
            Back
          </button>
        )}
        <span className="text-11 text-faint">
          <kbd>↵</kbd> {primaryLabel.toLowerCase()}
        </span>
        <span className="ml-auto flex items-center gap-2">
          {onSkip && (
            <button type="button" className="button" onClick={onSkip}>
              {skipLabel}
            </button>
          )}
          <button type="submit" className="button button-primary" disabled={pending}>
            {pending ? "Working…" : primaryLabel}
          </button>
        </span>
      </div>
    </form>
  );
}

export function MessageCard({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="flex flex-col overflow-hidden rounded-panel border border-border bg-card shadow-panel">
      <div className="border-b border-border px-[18px] py-3.5">
        <h1 className="m-0 text-15 font-medium tracking-tight">{title}</h1>
      </div>
      <div className="flex flex-col gap-4 px-[18px] py-[18px]">{children}</div>
    </div>
  );
}
