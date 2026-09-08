"use client";

import { Menu } from "@/components/menu";
import { PRIORITY_LABELS, STATUS_LABELS } from "@/lib/status";
import type { Label } from "@/lib/api/social";
import {
  TICKET_PRIORITIES,
  DEFAULT_STATUSES,
  type Cycle,
  type Person,
  type TicketPriority,
  type TicketStatus,
} from "@/lib/api";

/**
 * The floating strip at the bottom of screen 21.
 *
 * One request per action, never one per row: the server refuses the whole selection when a
 * single row is forbidden, and six requests could not promise that. `esc` is handled by the
 * page — it is the same key that closes everything else, and this component would be the
 * wrong owner of it.
 *
 * The `Label` button is the drawing's sixth and was missing while `V8` had not landed —
 * a button opening an empty picker being worse than no button. It *adds* the label to
 * every selected row rather than replacing what each wears: the labels of six rows are
 * nowhere on this screen, and a replace would take off what nobody could see.
 */
export function BulkStrip({
  count,
  cycles,
  people,
  labels,
  busy,
  onStatus,
  onPriority,
  onAssign,
  onCycle,
  onLabel,
  onDelete,
  onCancel,
}: {
  count: number;
  cycles: Cycle[];
  people: Person[];
  labels: Label[];
  busy: boolean;
  onStatus: (status: TicketStatus) => void;
  onPriority: (priority: TicketPriority) => void;
  onAssign: (userId: string | null) => void;
  onCycle: (cycleId: string) => void;
  onLabel: (labelId: string) => void;
  onDelete: () => void;
  onCancel: () => void;
}) {
  const nextCycle = cycles.find((cycle) => cycle.state === "upcoming") ?? cycles[0];

  return (
    <div
      role="toolbar"
      aria-label={`${count} selected`}
      data-testid="bulk-strip"
      className="absolute bottom-[26px] left-1/2 flex -translate-x-1/2 items-center gap-2 rounded-lg bg-card p-2.5 shadow-float"
    >
      <span className="flex items-center gap-2 pr-2 pl-1 text-12 font-medium">
        {count} selected
        <button type="button" className="text-11 font-normal text-faint hover:text-foreground" onClick={onCancel}>
          esc to cancel
        </button>
      </span>
      <span aria-hidden className="h-[22px] w-px bg-border" />

      <Menu
        label="Set status"
        asChild
        trigger={<button type="button" className={itemClass} disabled={busy}>Status</button>}
        items={DEFAULT_STATUSES.map((status) => ({
          id: `bulk.status.${status}`,
          label: STATUS_LABELS[status],
          onSelect: () => onStatus(status),
        }))}
      />
      <Menu
        label="Assign"
        asChild
        trigger={<button type="button" className={itemClass} disabled={busy}>Assign</button>}
        items={[
          { id: "bulk.assign.none", label: "Nobody", onSelect: () => onAssign(null) },
          ...people.map((person) => ({
            id: `bulk.assign.${person.id}`,
            label: person.displayName,
            onSelect: () => onAssign(person.id),
          })),
        ]}
      />
      <Menu
        label="Set priority"
        asChild
        trigger={<button type="button" className={itemClass} disabled={busy}>Priority</button>}
        items={TICKET_PRIORITIES.map((priority) => ({
          id: `bulk.priority.${priority}`,
          label: PRIORITY_LABELS[priority],
          onSelect: () => onPriority(priority),
        }))}
      />
      {/* Named for the cycle it would move to, as the drawing has it — `Cycle 25`, not
          `Cycle`. A generic label would need a second click to find out where. */}
      {nextCycle && (
        <button type="button" className={itemClass} disabled={busy} onClick={() => onCycle(nextCycle.id)}>
          Cycle {nextCycle.number}
        </button>
      )}

      {/* Only when the team owns one. A menu whose single entry is "there are none" is a
          button that punishes the click; the label a ticket needs is made from the panel,
          where a new one can be attached to the ticket in front of you. */}
      {labels.length > 0 && (
        <Menu
          /**
           * The accessible name is the visible word, not a friendlier sentence.
           * `Menu` writes `aria-label={label}` on its trigger, which *replaces* the child's
           * text — so "Add a label" left a button reading `Label` and announcing something
           * else, which is WCAG 2.5.3 (Label in Name) failing. `pills.tsx` gets this right
           * by prefixing rather than replacing (`Status: In progress`); here the visible
           * text is the whole name, so the label is the word itself.
           */
          label="Label"
          asChild
          trigger={<button type="button" className={itemClass} disabled={busy}>Label</button>}
          items={labels.map((label) => ({
            id: `bulk.label.${label.id}`,
            label: label.name,
            onSelect: () => onLabel(label.id),
          }))}
        />
      )}

      <span aria-hidden className="h-[22px] w-px bg-border" />
      <button
        type="button"
        className="rounded-sm px-2.5 py-[5px] text-12 text-urgent hover:bg-accent disabled:opacity-50"
        disabled={busy}
        onClick={onDelete}
      >
        Delete
      </button>
    </div>
  );
}

const itemClass =
  "rounded-sm px-2.5 py-[5px] text-12 text-muted-foreground hover:bg-accent hover:text-foreground disabled:opacity-50";
