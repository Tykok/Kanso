"use client";

import { useState } from "react";
import { Menu } from "./menu";
import type { Label } from "@/lib/api/social";
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
  ticket: { id: string; teamId: string; identifier: string };
}) {
  const worn = useTicketLabels(ticket.id);
  const available = useTeamLabels(ticket.teamId);
  const set = useSetTicketLabels();
  const create = useCreateLabel(ticket.teamId);
  const [naming, setNaming] = useState(false);

  const current = worn.data ?? [];
  const ids = current.map((label) => label.id);
  const busy = set.isPending || create.isPending;

  const write = (labelIds: string[]) => set.mutate({ ticketId: ticket.id, labelIds });

  const toggle = (label: Label) =>
    write(ids.includes(label.id) ? ids.filter((id) => id !== label.id) : [...ids, label.id]);

  /**
   * A new label is attached the moment it exists. Two requests rather than one, and the
   * order matters: nothing can wear a label the server has not given an id to yet.
   */
  const add = (name: string) => {
    const trimmed = name.trim();
    if (!trimmed) return;
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
            if (event.key === "Enter") add(event.currentTarget.value);
            if (event.key === "Escape") setNaming(false);
          }}
          onBlur={(event) => (event.target.value.trim() ? add(event.target.value) : setNaming(false))}
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
              onSelect: () => toggle(label),
            })),
            { id: "label.new", label: "New label…", onSelect: () => setNaming(true) },
          ]}
        />
      )}
    </div>
  );
}

/** `Design system`'s neutral label: 22px, the hover fill, 11px text, a 4px corner. */
const PILL =
  "inline-flex h-[22px] items-center gap-1.5 rounded-sm bg-accent px-2 text-11 text-muted-foreground";

const ADD =
  "inline-flex h-[22px] items-center rounded-sm border border-dashed border-border px-2 text-11 text-faint hover:text-foreground disabled:opacity-50";
