"use client";

import { useState } from "react";
import { Menu } from "./menu";
import { toggle } from "./organise/selection";
import type { Label } from "@/lib/api/social";
import { actionErrorMessage } from "@/lib/errors";
import {
  useCreateLabel,
  useSetTicketLabels,
  useTeamLabels,
  useTicketLabels,
} from "@/lib/queries/social";

/**
 * The labels a ticket wears, and the only control in the application that puts one on.
 *
 * `V8` created `labels` and `ticket_labels` and `LabelService` served four routes, and no
 * screen could attach one — which made every label list empty in practice, and made the
 * saved view's label chip and screen 28's badges decoration over nothing. This is the
 * control both of those depend on.
 *
 * One component for the panel and the page, because they are the same control at two
 * sizes: the pills take their shape from `Design system`'s "Étiquettes et miroir" —
 * 22px, the neutral fill, the 11px text — which is small enough for the panel's metadata
 * column and reads as a chip beside the page's own.
 *
 * Creating a label is part of the picker rather than a settings screen somewhere else: a
 * team that has never made one would otherwise open a menu with nothing in it, and a
 * picker that can only pick from an empty list is where the reader stops.
 *
 * The write is `PUT /api/tickets/{id}/labels` — the whole set, never one attach and one
 * detach — so two quick clicks cannot land in the wrong order and leave the ticket
 * wearing what the reader just took off.
 */
export function TicketLabels({
  ticket,
}: {
  /** The whole ticket is not needed; these three are. `identifier` names the menu. */
  ticket: { id: string; teamId?: string | null; identifier?: string | null };
}) {
  const worn = useTicketLabels(ticket.id);
  // A label is team-scoped — two teams may both own `sync` without arguing about which
  // of them means it — so a ticket with no team has no vocabulary to pick from and no
  // team to create one in. The hooks are still called: React counts them, and the early
  // return below is what draws nothing.
  const available = useTeamLabels(ticket.teamId ?? undefined);
  const set = useSetTicketLabels();
  const create = useCreateLabel(ticket.teamId ?? "");
  const [naming, setNaming] = useState(false);

  const current = worn.data ?? [];
  const ids = current.map((label) => label.id);
  const busy = set.isPending || create.isPending;

  // Nothing to draw, and nowhere to put what somebody typed: the "+ Label" affordance
  // would open a menu of an empty vocabulary and a "create" that has no team to create in.
  // After the hooks, so the count is the same on every render.
  if (ticket.teamId == null) return null;

  const write = (labelIds: string[]) => set.mutate({ ticketId: ticket.id, labelIds });

  // `toggle` is screen 21's own, and a set of ids is a set of ids: writing a second one
  // here would be two implementations of "is it in the list" to keep in step. The order it
  // appends in does not matter — the server answers with the labels sorted by name.
  const flip = (label: Label) => write(toggle(ids, label.id));

  /**
   * A new label is attached the moment it exists. Two requests rather than one, and the
   * order matters: nothing can wear a label the server has not given an id to yet.
   *
   * A name the team already owns attaches that label instead of asking for a second one:
   * `POST /teams/{id}/labels` answers 409 there, and the reader who typed a name that is
   * already on the list meant the label, not a duplicate. Matched exactly, because the
   * server's own `UNIQUE (team_id, name)` is — `Sync` and `sync` are two labels there, and
   * a case-insensitive guess here would quietly attach the wrong one of them.
   */
  const add = (name: string) => {
    const trimmed = name.trim();
    if (!trimmed) {
      setNaming(false);
      return;
    }
    const existing = (available.data ?? []).find((label) => label.name === trimmed);
    if (existing) {
      setNaming(false);
      if (!ids.includes(existing.id)) write([...ids, existing.id]);
      return;
    }
    create.mutate(
      { name: trimmed },
      {
        onSuccess: (label) => {
          setNaming(false);
          write([...ids, label.id]);
        },
      },
    );
  };

  /** Reads the field and empties it, so no name is submitted twice. */
  const submit = (input: HTMLInputElement) => {
    const name = input.value;
    input.value = "";
    add(name);
  };

  // Said out loud rather than left as a menu that appears to do nothing: the panel has no
  // error region of its own, and a refused write here is usually "that is not one of your
  // teams", which the reader can act on.
  const failure = set.error ?? create.error;

  return (
    <div className="flex min-w-0 flex-wrap items-center gap-1.5">
      {current.map((label) => (
        <span key={label.id} className={PILL}>
          {label.name}
          <button
            type="button"
            aria-label={`Remove ${label.name}`}
            className="text-faint hover:text-foreground disabled:opacity-50"
            disabled={busy}
            onClick={() => write(ids.filter((id) => id !== label.id))}
          >
            ×
          </button>
        </span>
      ))}

      {naming ? (
        <input
          autoFocus
          aria-label="New label"
          placeholder="Label name"
          className="h-[22px] w-32 rounded-sm border border-border bg-card px-2 text-11 text-foreground"
          // The page under this one listens on `window` for `j`, `k` and `x`; without
          // this, typing a label name would walk the list behind the panel.
          onKeyDown={(event) => {
            event.stopPropagation();
            if (event.key === "Enter") submit(event.currentTarget);
            // Emptied before it closes, so the blur that may follow reads nothing and
            // creates nothing: cancelling has to actually cancel.
            if (event.key === "Escape") {
              event.currentTarget.value = "";
              setNaming(false);
            }
          }}
          // Blur commits too — clicking away from a name somebody typed should keep it —
          // and `submit` takes the value out of the field, so Enter followed by a blur is
          // one label and not two.
          onBlur={(event) => submit(event.target)}
        />
      ) : (
        <Menu
          label={`Labels for ${ticket.identifier}`}
          asChild
          trigger={
            <button type="button" className={ADD} disabled={busy}>
              + Label
            </button>
          }
          items={[
            ...(available.data ?? []).map((label) => ({
              id: `label.${label.id}`,
              // A tick rather than two lists: the menu is the whole set the team owns, and
              // splitting it into "on" and "off" halves would move an entry every click.
              label: `${ids.includes(label.id) ? "✓ " : ""}${label.name}`,
              onSelect: () => flip(label),
            })),
            { id: "label.new", label: "New label…", onSelect: () => setNaming(true) },
          ]}
        />
      )}

      {failure ? (
        <span role="alert" className="text-11 text-urgent">
          {actionErrorMessage(failure)}
        </span>
      ) : null}
    </div>
  );
}

/** `Design system`'s neutral label: 22px, the hover fill, 11px text, a 4px corner. */
const PILL =
  "inline-flex h-[22px] items-center gap-1.5 rounded-sm bg-accent px-2 text-11 text-muted-foreground";

const ADD =
  "inline-flex h-[22px] items-center rounded-sm border border-dashed border-border px-2 text-11 text-faint hover:text-foreground disabled:opacity-50";
