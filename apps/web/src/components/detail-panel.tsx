"use client";

import { useRef, useState } from "react";
import {
  TICKET_PRIORITIES,
  TICKET_STATUSES,
  dayValue,
  fromDayValue,
  type Project,
  type Ticket,
  type TicketPriority,
  type TicketStatus,
} from "@/lib/api";
import { STATUS_LABELS } from "@/lib/status";
import { Menu, type MenuItem } from "./menu";
import { Backdrop } from "./overlays";
import { Kbd } from "./ui/kbd";
import { PriorityMark } from "./ui/priority-mark";
import { StatusDot } from "./ui/status-dot";

/** One metadata row: an 11px caption at the left, its control at the right —
 *  the same two-column shape `Field` gives every row of a dialog. Wrapped in a
 *  `<label>`, the same association `dialogs/field.tsx` and `setup/fields.tsx` use,
 *  rather than the sibling `<span>` this used to be: a `<select>` or `<input>` with
 *  no enclosing or `for`-linked label has no accessible name at all. `<label>`
 *  takes `display: grid` from this className exactly as the `<div>` it replaces
 *  did, so the layout is unchanged. */
function MetaRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="grid grid-cols-[88px_1fr] items-center gap-3">
      <span className="text-11 text-faint">{label}</span>
      <div className="flex min-w-0 items-center gap-2">{children}</div>
    </label>
  );
}

/** The `<select>` still does the choosing — see `menu.tsx`'s own comment on why a
 *  native control beats a bespoke one here — dressed down to read as plain text
 *  beside the mark that already carries the colour. */
const META_SELECT = "min-w-0 border-none bg-transparent p-0 text-13 text-foreground";

export function DetailPanel({
  ticket,
  projects,
  onPatch,
  onDelete,
  onClose,
}: {
  ticket: Ticket;
  projects: Project[];
  onPatch: (patch: Record<string, unknown>) => void;
  onDelete: () => void;
  onClose: () => void;
}) {
  const [description, setDescription] = useState(ticket.description ?? "");
  const initial = useRef(ticket.description ?? "");

  // Archive and delete used to be two standalone buttons at the foot of the panel;
  // the drawing puts both behind the same `⋯` every row already uses, which is one
  // fewer control shape for a keyboard user to learn.
  const actions: MenuItem[] = [
    {
      id: "archive",
      label: ticket.archived ? "Unarchive" : "Archive",
      onSelect: () => onPatch({ archived: !ticket.archived }),
    },
    { id: "delete", label: "Delete", danger: true, onSelect: onDelete },
  ];

  return (
    <Backdrop onClose={onClose} panelClassName="w-[640px]">
      <div data-testid="panel-header" className="flex items-center gap-2 px-5 py-3">
        <span className="font-mono text-11 text-faint">{ticket.identifier}</span>
        <span className="flex-1" />
        <Menu label={`Actions for ${ticket.identifier}`} items={actions} />
        <Kbd>esc</Kbd>
      </div>

      <div className="flex max-h-[70vh] flex-col gap-4 overflow-y-auto px-5 pb-5">
        <h2 className="text-21 font-medium tracking-tight text-foreground">{ticket.title}</h2>

        <div className="flex flex-col gap-2.5">
          <MetaRow label="Status">
            <StatusDot status={ticket.status} />
            <select
              className={META_SELECT}
              value={ticket.status}
              onChange={(event) => onPatch({ status: event.target.value as TicketStatus })}
            >
              {TICKET_STATUSES.map((status) => (
                <option key={status} value={status}>
                  {STATUS_LABELS[status]}
                </option>
              ))}
            </select>
          </MetaRow>

          <MetaRow label="Priority">
            <PriorityMark priority={ticket.priority} />
            <select
              className={META_SELECT}
              value={ticket.priority}
              onChange={(event) => onPatch({ priority: event.target.value as TicketPriority })}
            >
              {TICKET_PRIORITIES.map((priority) => (
                <option key={priority} value={priority}>
                  {priority}
                </option>
              ))}
            </select>
          </MetaRow>

          <MetaRow label="Project">
            <select
              className={META_SELECT}
              value={ticket.projectId ?? ""}
              onChange={(event) =>
                onPatch(
                  event.target.value
                    ? { projectId: event.target.value }
                    : // An absent key means "unchanged", so clearing has to be explicit.
                      { unset: ["projectId"] },
                )
              }
            >
              <option value="">— none —</option>
              {projects.map((project) => (
                <option key={project.id} value={project.id}>
                  {project.name}
                </option>
              ))}
            </select>
          </MetaRow>

          <MetaRow label="Due date">
            <input
              type="date"
              className={META_SELECT}
              value={dayValue(ticket.due)}
              onChange={(event) =>
                onPatch(
                  event.target.value
                    ? { due: fromDayValue(event.target.value) }
                    : { unset: ["due"] },
                )
              }
            />
          </MetaRow>
        </div>

        <div className="h-px bg-border" />

        <label className="flex flex-col gap-1.5">
          <span className="text-11 text-faint">Description</span>
          <textarea
            rows={6}
            className="w-full resize-none rounded-md border border-border bg-card p-2.5 text-13 text-foreground"
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            onKeyDown={(event) => event.stopPropagation()}
            onBlur={() => {
              if (description !== initial.current) {
                onPatch(description ? { description } : { unset: ["description"] });
                initial.current = description;
              }
            }}
          />
        </label>

        <span className="text-11 text-faint">
          {ticket.mirror.notionPageId ? "Mirrored in Notion" : "Not in Notion yet"}
        </span>
      </div>
    </Backdrop>
  );
}
