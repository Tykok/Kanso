import type { ReactNode } from "react";

/**
 * A field's label and hint on the left, its control on the right — the row shape
 * every choice in the appearance section shares. Split out because the account,
 * people and connections sections each build their own field shape (a label above
 * an input, not beside a segmented control), so this is not their shared ancestor —
 * only appearance's.
 */
export function SettingsField({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="flex items-center gap-4">
      <div className="flex flex-1 flex-col gap-px">
        <span className="text-13 font-medium">{label}</span>
        {hint && <span className="text-11 text-faint">{hint}</span>}
      </div>
      {children}
    </div>
  );
}

/**
 * A label above its own input or two, and whatever the field needs to say about
 * itself below — the account, people and connections sections' shape, none of
 * which pairs one label with one inline control the way appearance's rows do.
 */
export function SettingsFormField({ children }: { children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5 border-b border-border py-3.5 last:border-b-0">
      {children}
    </div>
  );
}

/** A row of controls that wrap together: an input beside its button, a select
 *  beside its own. */
export function SettingsInline({ children }: { children: ReactNode }) {
  return <div className="flex flex-wrap items-center gap-2">{children}</div>;
}

/** A value that is displayed but not editable, sized like the inputs around it. */
export function SettingsStatic({ children }: { children: ReactNode }) {
  return (
    <div className="rounded-md border border-transparent px-2 py-1.5 text-13 text-muted-foreground">
      {children}
    </div>
  );
}

export function SettingsNote({ error, children }: { error?: boolean; children: ReactNode }) {
  return <span className={`text-11 ${error ? "text-urgent" : "text-faint"}`}>{children}</span>;
}
