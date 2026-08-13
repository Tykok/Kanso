"use client";

import type { KeyboardEvent, ReactNode } from "react";
import { BrandLogo } from "../brand-logo";

export type StepId = "account" | "notion" | "google" | "preferences";

export const STEP_NAMES: Record<StepId, string> = {
  account: "Account",
  notion: "Notion",
  google: "Google sign-in",
  preferences: "Preferences",
};

export function SetupPage({ children }: { children: ReactNode }) {
  return (
    <div className="setup-page">
      <div className="setup-shell">
        {/* Smaller than the sign-in screen's: this one sits above a card that already
            has a heading of its own, so it introduces rather than announces. */}
        <div className="brand">
          <BrandLogo width={140} alt="Kanso 簡素" />
        </div>
        {children}
      </div>
    </div>
  );
}

export function StepRail({ plan, index }: { plan: StepId[]; index: number }) {
  const done = index >= plan.length;

  return (
    <nav className="setup-rail" aria-label="Setup progress">
      <span className="setup-rail-count">
        {done ? "All steps done" : `Step ${index + 1} of ${plan.length}`}
      </span>
      <ol>
        {plan.map((step, position) => (
          <li
            key={step}
            data-state={done || position < index ? "done" : position === index ? "current" : "todo"}
            aria-current={position === index ? "step" : undefined}
          >
            {STEP_NAMES[step]}
          </li>
        ))}
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
      className="setup-card"
      onSubmit={(event) => {
        event.preventDefault();
        onSubmit();
      }}
      onKeyDown={escapeBlurs}
    >
      <div className="setup-head">
        {head}
        <h1>{title}</h1>
        {intro && <p className="setup-intro">{intro}</p>}
      </div>

      <div className="setup-body">
        {children}
        {error && (
          <p className="setup-error" role="alert">
            {error}
          </p>
        )}
      </div>

      <div className="setup-foot">
        {onBack && (
          <button type="button" className="button" onClick={onBack}>
            Back
          </button>
        )}
        <span className="setup-keys">
          <kbd>↵</kbd> {primaryLabel.toLowerCase()}
        </span>
        <span className="setup-foot-end">
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
    <div className="setup-card">
      <div className="setup-head">
        <h1>{title}</h1>
      </div>
      <div className="setup-body">{children}</div>
    </div>
  );
}
