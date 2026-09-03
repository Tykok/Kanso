"use client";

import { useState } from "react";

import type { CustomField, CustomFieldType } from "@/lib/api";
import { actionErrorMessage } from "@/lib/errors";
import { useDefineField, useDeleteField, useTeamFields } from "@/lib/queries";

/**
 * Where a team's custom fields are defined — inside the team dialog, beside its members.
 *
 * `MembersSection` is the precedent and the argument: a team-scoped collection is edited
 * where the team is, not in a settings screen that would have to ask which team it meant.
 * The settings screens in this app are user-level and instance-level, and a field belongs to
 * neither.
 *
 * It is deliberately *not* the pattern `ticket-labels.tsx` uses. Creating a label lives in
 * the picker because a team that has never made one would otherwise open an empty menu — a
 * label is one word and the gesture is cheap. A field is a name, a type and possibly a list
 * of choices, and defining one from inside a ticket panel would put a four-input form in a
 * place somebody went to change a status.
 */
export function CustomFieldsSection({
  teamId,
  canConfigure,
}: {
  teamId: string;
  /** The server decides; this only decides what to draw. See `lib/seat.ts`. */
  canConfigure: boolean;
}) {
  const defined = useTeamFields(teamId);
  const define = useDefineField(teamId);
  const remove = useDeleteField(teamId);

  const [name, setName] = useState("");
  const [type, setType] = useState<CustomFieldType>("text");
  const [required, setRequired] = useState(false);
  const [choices, setChoices] = useState("");

  const fields = defined.data ?? [];
  const busy = define.isPending || remove.isPending;
  const failure = define.error ?? remove.error;

  const submit = () => {
    if (name.trim() === "") return;
    define.mutate(
      {
        name: name.trim(),
        type,
        required,
        // Split here rather than asked for as a list: a comma-separated box is one input for
        // a thing people paste, and the server drops blanks and collapses repeats anyway.
        options: type === "select" ? splitChoices(choices) : [],
      },
      {
        onSuccess: () => {
          setName("");
          setChoices("");
          setRequired(false);
        },
      },
    );
  };

  return (
    <div className="flex flex-col gap-2">
      <span className="text-11 font-medium tracking-wide text-faint uppercase">
        Custom fields
      </span>

      {fields.length === 0 ? (
        <p className="text-11 text-faint">
          No fields yet. A field defined here appears on every ticket in this team.
        </p>
      ) : (
        <ul className="flex flex-col gap-1">
          {fields.map((field) => (
            <li key={field.id} className="flex items-center justify-between gap-2">
              <span className="min-w-0 truncate text-13 text-foreground">
                {field.name}
                <span className="text-11 text-faint"> · {describe(field)}</span>
              </span>
              {canConfigure ? (
                <button
                  type="button"
                  className="text-11 text-faint hover:text-urgent disabled:opacity-50"
                  disabled={busy}
                  // The count comes from the server, derived on read, and it is the whole
                  // reason this delete can be offered at all: the values cascade, so a
                  // confirmation that could not name a number would be asking for a
                  // signature on a blank cheque.
                  onClick={() => {
                    const held = field.valueCount;
                    const warning =
                      held === 0
                        ? `Delete "${field.name}"?`
                        : `Delete "${field.name}"? ${held} ticket${held === 1 ? "" : "s"} will lose its value.`;
                    if (window.confirm(warning)) remove.mutate(field.id);
                  }}
                >
                  Delete
                </button>
              ) : null}
            </li>
          ))}
        </ul>
      )}

      {canConfigure ? (
        <div className="flex flex-col gap-1.5 border-t border-border pt-2">
          <div className="flex items-center gap-1.5">
            <input
              className="min-w-0 flex-1"
              placeholder="Severity"
              value={name}
              maxLength={60}
              disabled={busy}
              onChange={(event) => setName(event.target.value)}
              onKeyDown={(event) => {
                event.stopPropagation();
                if (event.key === "Enter") submit();
              }}
            />
            <select
              aria-label="Field type"
              value={type}
              disabled={busy}
              onChange={(event) => setType(event.target.value as CustomFieldType)}
            >
              {TYPES.map((option) => (
                <option key={option} value={option}>
                  {TYPE_LABELS[option]}
                </option>
              ))}
            </select>
          </div>

          {/* Only for `select`, because `custom_fields_options_chk` refuses choices on every
              other type — and drawing a box the server will refuse is drawing a trap. */}
          {type === "select" ? (
            <input
              aria-label="Choices"
              placeholder="low, high, urgent"
              value={choices}
              disabled={busy}
              onChange={(event) => setChoices(event.target.value)}
              onKeyDown={(event) => event.stopPropagation()}
            />
          ) : null}

          <label className="flex items-center gap-1.5 text-11 text-faint">
            <input
              type="checkbox"
              className="h-3.5 w-3.5 accent-current"
              checked={required}
              disabled={busy}
              onChange={(event) => setRequired(event.target.checked)}
            />
            Required — a value may not be cleared once set. A ticket without one is still valid.
          </label>

          <button
            type="button"
            className="self-start text-11 text-faint hover:text-foreground disabled:opacity-50"
            disabled={busy || name.trim() === ""}
            onClick={submit}
          >
            Add field
          </button>
        </div>
      ) : null}

      {failure ? (
        <span role="alert" className="text-11 text-urgent">
          {actionErrorMessage(failure)}
        </span>
      ) : null}
    </div>
  );
}

/**
 * A comma-separated box into the list the server takes.
 *
 * Blanks dropped and repeats collapsed here as well as on the server — not redundant, because
 * this is what decides whether the box `low,,high` sends two choices or three, and the
 * feedback belongs where the typing is.
 */
export function splitChoices(raw: string): string[] {
  const seen = raw
    .split(",")
    .map((choice) => choice.trim())
    .filter((choice) => choice !== "");
  return [...new Set(seen)];
}

/** What a row says about a field's shape, in the fewest words that are still true. */
export function describe(field: Pick<CustomField, "type" | "required" | "options">): string {
  const shape = field.type === "select" ? field.options.join(" / ") : TYPE_LABELS[field.type];
  return field.required ? `${shape} · required` : shape;
}

const TYPES = ["text", "number", "boolean", "select"] as const satisfies readonly CustomFieldType[];

const TYPE_LABELS: Record<CustomFieldType, string> = {
  text: "Text",
  number: "Number",
  boolean: "Yes / no",
  select: "Choice",
};
