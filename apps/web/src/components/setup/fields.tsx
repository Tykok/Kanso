"use client";

import { useEffect, useState, type InputHTMLAttributes, type ReactNode } from "react";
import { ApiError, apiOrigin } from "@/lib/api";

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
 *
 * `apiOrigin()` and not `API_URL`, which is empty wherever nothing was inlined and would
 * end this sentence on nothing at all. No hook here: a failed request is not something a
 * prerender can have, so this is only ever read in a browser.
 */
export function messageFor(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  return `No answer from the API at ${apiOrigin()}. Check that it is running.`;
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
    <label className="flex flex-col gap-1.5">
      <span className="text-11 uppercase tracking-wide text-faint">{label}</span>
      <input className="w-full" {...input} aria-invalid={error ? true : undefined} />
      {error ? (
        <span className="text-12 text-urgent">{error}</span>
      ) : hint ? (
        <span className="text-11 text-faint">{hint}</span>
      ) : null}
    </label>
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
    <div className="flex items-center gap-1.5">
      <input
        readOnly
        className="min-w-0 flex-1 text-12"
        style={{ fontFamily: "var(--font-mono)" }}
        value={value}
        onFocus={(event) => event.currentTarget.select()}
      />
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
    <div className="flex flex-col gap-1.5">
      <span className="text-11 uppercase tracking-wide text-faint">{label}</span>
      {row}
    </div>
  );
}

export function Callout({ children }: { children: ReactNode }) {
  return (
    <p className="m-0 rounded-md border border-border border-l-2 border-l-primary px-2.5 py-2 text-13 text-muted-foreground">
      {children}
    </p>
  );
}

/** "or", between the password form and the identity providers below it. */
export function Divider({ children }: { children: ReactNode }) {
  return (
    <div className="flex items-center gap-2.5 text-11 uppercase tracking-wide text-faint">
      <span className="h-px flex-1 bg-border" />
      {children}
      <span className="h-px flex-1 bg-border" />
    </div>
  );
}

/**
 * One button per identity provider actually configured on the API — never a
 * button that leads to a broken redirect. Used by the sign-in page and by
 * `login.tsx`'s bare screen, which is why it lives beside the fields both draw
 * from rather than in either one.
 */
export function ProviderButtons({
  providers,
  primary,
}: {
  providers: { id: string; label: string; href: string }[];
  primary?: boolean;
}) {
  return (
    <div className="flex flex-col gap-2">
      {providers.map((provider) => (
        <a
          key={provider.id}
          className={`button block text-left no-underline${primary ? " button-primary" : ""}`}
          href={provider.href}
        >
          Continue with {provider.label}
        </a>
      ))}
    </div>
  );
}
