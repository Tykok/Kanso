"use client";

import { useEffect, useState, type InputHTMLAttributes, type ReactNode } from "react";
import { API_URL, ApiError } from "@/lib/api";

export const PASSWORD_MIN = 12;

/**
 * The server enforces the same floor. Repeating it here only avoids a round trip
 * whose one possible answer is "too short", and lets the count update as you type.
 */
export function passwordProblem(password: string, confirm?: string): string | null {
  if (password.length < PASSWORD_MIN) {
    return `Use at least ${PASSWORD_MIN} characters — ${password.length} so far.`;
  }
  if (confirm !== undefined && confirm !== password) return "The two passwords do not match.";
  return null;
}

/**
 * Failures arrive as RFC 7807, so `detail` is already a sentence written for a
 * human and is shown verbatim. The one case worth rewording is no answer at all:
 * "failed to fetch" says nothing, the address that stayed silent says everything.
 */
export function messageFor(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  return `No answer from the API at ${API_URL}. Check that it is running.`;
}

/**
 * The API names the offending input in prose rather than in a field map. Reading
 * that name back out puts the message under the input it talks about, which is
 * where the eye already is, instead of in a banner at the bottom of the form.
 */
export function fieldFromDetail(error: unknown): "email" | "password" | null {
  if (!(error instanceof ApiError)) return null;
  const detail = error.message.toLowerCase();
  if (detail.includes("password")) return "password";
  if (detail.includes("email")) return "email";
  return null;
}

type TextFieldProps = InputHTMLAttributes<HTMLInputElement> & {
  label: string;
  hint?: ReactNode;
  error?: string | null;
};

export function TextField({ label, hint, error, ...input }: TextFieldProps) {
  return (
    <label className="setup-field">
      <span className="setup-label">{label}</span>
      <input {...input} aria-invalid={error ? true : undefined} />
      {error ? (
        <span className="setup-error">{error}</span>
      ) : hint ? (
        <span className="setup-hint">{hint}</span>
      ) : null}
    </label>
  );
}

export function Toggle({
  label,
  hint,
  checked,
  onChange,
}: {
  label: string;
  hint?: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
}) {
  return (
    <label className="setup-toggle">
      <input type="checkbox" checked={checked} onChange={(event) => onChange(event.target.checked)} />
      <span className="setup-toggle-text">
        <span>{label}</span>
        {hint && <span className="setup-hint">{hint}</span>}
      </span>
    </label>
  );
}

/**
 * Radios rather than buttons: arrow keys walk the group for free, and Enter still
 * submits the step. A segmented control built from buttons loses both.
 */
export function ChoiceGroup<T extends string>({
  label,
  name,
  value,
  options,
  onChange,
}: {
  label: string;
  name: string;
  value: T;
  options: readonly { value: T; label: string }[];
  onChange: (value: T) => void;
}) {
  return (
    <div className="setup-field">
      <span className="setup-label">{label}</span>
      <div className="setup-choices" role="radiogroup" aria-label={label}>
        {options.map((option) => (
          <label key={option.value} className="setup-choice">
            <input
              type="radio"
              name={name}
              checked={value === option.value}
              onChange={() => onChange(option.value)}
            />
            <span>{option.label}</span>
          </label>
        ))}
      </div>
    </div>
  );
}

/**
 * The clipboard API needs a secure context, which a self-hosted instance reached
 * over plain http is not. The value therefore stays in a real input that selects
 * itself on focus, so copying by hand works when the button cannot.
 */
export function CopyRow({ value, label }: { value: string; label?: string }) {
  const [state, setState] = useState<"idle" | "copied" | "failed">("idle");

  useEffect(() => {
    if (state === "idle") return;
    const timer = setTimeout(() => setState("idle"), 2500);
    return () => clearTimeout(timer);
  }, [state]);

  const row = (
    <div className="setup-copy">
      <input readOnly value={value} onFocus={(event) => event.currentTarget.select()} />
      <button
        type="button"
        className="button"
        onClick={() => {
          navigator.clipboard
            ?.writeText(value)
            .then(() => setState("copied"))
            .catch(() => setState("failed"));
        }}
      >
        {state === "copied" ? "Copied" : state === "failed" ? "Select it" : "Copy"}
      </button>
    </div>
  );

  if (!label) return row;
  return (
    <div className="setup-field">
      <span className="setup-label">{label}</span>
      {row}
    </div>
  );
}

export function Callout({ children }: { children: ReactNode }) {
  return <p className="setup-callout">{children}</p>;
}
