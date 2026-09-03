"use client";

import { useMe, useSetTicketFields, useTeamFields, useTicketFields } from "@/lib/queries";
import type { CustomField, CustomFieldValue } from "@/lib/api";
import { actionErrorMessage } from "@/lib/errors";
import { valueFromControl, valueToControl } from "@/lib/field-values";
import { mayWrite } from "@/lib/seat";

/**
 * A ticket's custom fields, one control each.
 *
 * The four controls are native `<input>` and `<select>`, the way `detail-panel.tsx` draws
 * status and priority — this codebase's standing argument that a closed list gets a real
 * `<select>` rather than a bespoke menu, because the native one is already keyboard
 * accessible, already announces itself, and already works on a phone.
 *
 * **The definitions come from the team, the values from the ticket.** Two reads rather than
 * one embedded shape, and it is why `V32` keys `customFields` by field id on the wire: a
 * name is what a screen calls a thing, and keying by it would cost every reader a rename.
 * This component is the one place that joins the two, which is exactly what a screen is for.
 *
 * A team with no fields draws nothing at all — not an empty heading. A row that says
 * "Custom fields: none" is a row that teaches a reader to ignore that part of the panel.
 */
export function TicketFields({
  ticket,
}: {
  /** The whole ticket is not needed; these three are — the same narrowness `TicketLabels` takes. */
  ticket: { id: string; teamId?: string | null; identifier?: string | null };
}) {
  const me = useMe();
  const defined = useTeamFields(ticket.teamId ?? undefined);
  const held = useTicketFields(ticket.id);
  const set = useSetTicketFields();

  const fields = defined.data ?? [];
  const values = held.data ?? {};
  const writable = mayWrite(me.data?.user.instanceRole);
  const busy = set.isPending;

  // After the hooks, so the count is the same on every render.
  if (ticket.teamId == null || fields.length === 0) return null;

  /**
   * One field at a time, which is what the endpoint is shaped for: an absent key is
   * untouched. Sending the whole map on every edit would make this client's stale view of
   * the definitions authoritative over the server's.
   */
  const write = (field: CustomField, raw: string) => {
    const value = valueFromControl(field.type, raw);
    set.mutate({ ticketId: ticket.id, values: { [field.id]: value } });
  };

  return (
    <div className="flex flex-col gap-2">
      {fields.map((field) => (
        <div key={field.id} className={ROW}>
          <span className="text-11 text-faint">
            {field.name}
            {/* The server enforces this as "a value may not be taken away", never as "a
                ticket must have one" — so the mark is a prompt, not a warning that something
                is wrong with a ticket that has no value yet. */}
            {field.required ? <span className="text-urgent"> *</span> : null}
          </span>
          <Control
            field={field}
            value={values[field.id]}
            disabled={busy || !writable}
            onWrite={(raw) => write(field, raw)}
          />
        </div>
      ))}
      {set.error ? (
        <span role="alert" className="text-11 text-urgent">
          {actionErrorMessage(set.error)}
        </span>
      ) : null}
    </div>
  );
}

/**
 * The control for one type. Exhaustive over `CustomFieldType` — a fifth type added to the
 * union without a branch here is a TypeScript error, which is the point: `V32` refuses to
 * add a type nothing renders, and this is that refusal expressed where it can be checked.
 */
function Control({
  field,
  value,
  disabled,
  onWrite,
}: {
  field: CustomField;
  value: CustomFieldValue | undefined;
  disabled: boolean;
  onWrite: (raw: string) => void;
}) {
  const name = `field-${field.id}`;

  switch (field.type) {
    case "boolean":
      return (
        <input
          id={name}
          type="checkbox"
          className="h-3.5 w-3.5 accent-current"
          checked={value === true}
          disabled={disabled}
          // Stringified so that `valueFromControl` is the single place a control's output
          // becomes a wire value, for all four types rather than three.
          onChange={(event) => onWrite(String(event.target.checked))}
        />
      );

    case "select":
      return (
        <select
          id={name}
          className={CONTROL}
          value={valueToControl(value)}
          disabled={disabled}
          onChange={(event) => onWrite(event.target.value)}
        >
          {/* The way back to no value, since a select has no empty state of its own. Absent
              for a required field: clearing one is refused by the server, and offering a
              choice that always fails is worse than not offering it. */}
          {field.required ? null : <option value="">—</option>}
          {field.options.map((option) => (
            <option key={option} value={option}>
              {option}
            </option>
          ))}
        </select>
      );

    case "number":
      return (
        <input
          id={name}
          type="number"
          className={CONTROL}
          defaultValue={valueToControl(value)}
          disabled={disabled}
          // On blur rather than on change: a number box fires per keystroke, so `onChange`
          // would send a request for `1`, `12` and `120` on the way to typing 120 — and the
          // first two would each land in the activity feed as a decision somebody made.
          onBlur={(event) => onWrite(event.target.value)}
          // The list behind this panel listens on `window` for `j`, `k` and `x`.
          onKeyDown={(event) => event.stopPropagation()}
        />
      );

    case "text":
      return (
        <input
          id={name}
          type="text"
          className={CONTROL}
          defaultValue={valueToControl(value)}
          disabled={disabled}
          onBlur={(event) => onWrite(event.target.value)}
          onKeyDown={(event) => event.stopPropagation()}
        />
      );
  }
}

/** The panel's own `META_ROW`: an 11px caption at the left, its control at the right. */
const ROW = "grid grid-cols-[88px_1fr] items-center gap-3";

/**
 * `justify-self-start` because a grid item stretches by default, and a stretched `<select>`
 * puts its chevron against the far edge of the column — which reads as a full-width control
 * somebody meant to style and did not. The native control sizes to its longest option, which
 * is the width the value actually needs.
 */
const CONTROL =
  "min-w-0 justify-self-start border-none bg-transparent p-0 text-13 text-foreground disabled:opacity-50";
