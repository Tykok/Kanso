"use client";

import { useMemo, useRef, useState } from "react";
import { shortcutRows } from "@/lib/actions";
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
import { isMac } from "@/lib/platform";
import { STATUS_LABELS } from "@/lib/status";
import { cn } from "@/lib/utils";
import type { View } from "@/store/ui";
import { Button } from "./ui/button";
import { Kbd } from "./ui/kbd";
import { Menu, type MenuItem } from "./menu";
import { PriorityMark } from "./ui/priority-mark";
import { StatusDot } from "./ui/status-dot";

/**
 * Exported since the composer moved into a file of its own. `DialogFrame`, for its
 * part, rewrites these four lines: it needs `role="dialog"`, a `tabIndex` and a
 * `keydown` boundary, none of which this wrapper takes.
 *
 * `panelClassName` is the one thing that varies between what this wraps: the
 * composer draws a wide, 640px form; the command palette and the help panel are
 * happy at the narrower default. A width is not a colour, a radius or a shadow, so
 * it stays a plain Tailwind class rather than growing its own token.
 */
export function Backdrop({
  onClose,
  panelClassName,
  children,
}: {
  onClose: () => void;
  panelClassName?: string;
  children: React.ReactNode;
}) {
  return (
    <div
      className="fixed inset-0 z-20 flex items-start justify-center bg-black/34 pt-[12vh]"
      onClick={onClose}
    >
      <div
        className={cn(
          "w-[min(560px,92vw)] overflow-hidden rounded-panel bg-popover shadow-float",
          panelClassName,
        )}
        onClick={(event) => event.stopPropagation()}
      >
        {children}
      </div>
    </div>
  );
}

type Command = { id: string; label: string; hint?: string; run: () => void };

export function CommandPalette({
  commands,
  onClose,
}: {
  commands: Command[];
  onClose: () => void;
}) {
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);

  const matches = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return commands;
    return commands.filter((command) => command.label.toLowerCase().includes(needle));
  }, [commands, query]);

  // Typing narrows the list, so the highlight goes back to the top — done in the
  // change handler rather than an effect, which would re-render a second time to
  // undo a selection the user never saw.
  const search = (next: string) => {
    setQuery(next);
    setActive(0);
  };

  return (
    <Backdrop onClose={onClose}>
      <div data-testid="panel-header" className="border-b border-border px-4 py-3">
        <input
          className="w-full border-none bg-transparent p-0 text-15 text-foreground outline-none placeholder:text-faint"
          autoFocus
          placeholder="Type a command…"
          value={query}
          onChange={(event) => search(event.target.value)}
          onKeyDown={(event) => {
            event.stopPropagation();
            if (event.key === "ArrowDown") {
              event.preventDefault();
              setActive((index) => Math.min(index + 1, matches.length - 1));
            }
            if (event.key === "ArrowUp") {
              event.preventDefault();
              setActive((index) => Math.max(index - 1, 0));
            }
            if (event.key === "Enter") {
              event.preventDefault();
              matches[active]?.run();
            }
            if (event.key === "Escape") onClose();
          }}
        />
      </div>
      <div className="flex max-h-[60vh] flex-col overflow-y-auto">
        {matches.length === 0 && <div className="empty">No matching command</div>}
        {matches.map((command, index) => (
          <button
            key={command.id}
            className="flex w-full items-center gap-2.5 px-4 py-2 text-left text-13 data-[active=true]:bg-accent"
            data-active={index === active}
            onMouseEnter={() => setActive(index)}
            onClick={command.run}
          >
            <span>{command.label}</span>
            {command.hint && <span className="ml-auto text-11 text-faint">{command.hint}</span>}
          </button>
        ))}
      </div>
      <div className="flex items-center gap-2 border-t border-border px-4 py-2 text-11 text-faint">
        <Kbd>↑</Kbd> <Kbd>↓</Kbd> <span>move</span> <Kbd>↵</Kbd> <span>run</span> <Kbd>esc</Kbd>{" "}
        <span>close</span>
      </div>
    </Backdrop>
  );
}

/** One metadata row: an 11px caption at the left, its control at the right —
 *  the same two-column shape `Field` gives every row of a dialog. */
function MetaRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="grid grid-cols-[88px_1fr] items-center gap-3">
      <span className="text-11 text-faint">{label}</span>
      <div className="flex min-w-0 items-center gap-2">{children}</div>
    </div>
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

/**
 * The three sections, in the order they are shown. A key that works everywhere is
 * listed once at the top rather than repeated under both views.
 */
const SHORTCUT_SECTIONS: { mode: View | undefined; title: string }[] = [
  { mode: undefined, title: "Anywhere" },
  { mode: "list", title: "In the list" },
  { mode: "timeline", title: "On the timeline" },
];

export function HelpOverlay({ onClose }: { onClose: () => void }) {
  const rows = shortcutRows(isMac());

  return (
    <Backdrop onClose={onClose}>
      <div data-testid="panel-header" className="flex items-center gap-2.5 px-4 py-3">
        {/*
          A real heading rather than a `<strong>`: this panel is the one thing on
          screen, and it had no element announcing what it is. It is also what the
          keyboard scenario asserts on — `.shortcuts` stopped being unique the moment
          the list grew a section per mode, and keying a test on a private class is
          what `follow-ups.md` already holds against that suite.
        */}
        <h2 className="flex-1 text-15 font-medium text-foreground">Keyboard</h2>
        <Button type="button" variant="outline" size="sm" onClick={onClose}>
          Close
        </Button>
      </div>
      <div className="flex max-h-[70vh] flex-col gap-5 overflow-y-auto px-4 pb-4">
        {/*
          Grouped by mode, because a flat list would offer the chart's `h` `l` `H` `L`
          to somebody in the list, where those keys resolve to nothing at all. A
          section with no rows is not printed: an empty heading reads as a gap.
        */}
        {SHORTCUT_SECTIONS.map((section) => {
          const inSection = rows.filter((row) => row.mode === section.mode);
          if (inSection.length === 0) return null;

          return (
            <div key={section.title} className="flex flex-col gap-2">
              <div className="text-11 text-faint">{section.title}</div>
              <div className="grid grid-cols-[auto_1fr] items-center gap-x-3 gap-y-1.5">
                {inSection.map((row) => (
                  <div key={`${section.title}:${row.keys}`} className="contents">
                    <Kbd className="justify-self-start">{row.keys}</Kbd>
                    <span className="text-13 text-muted-foreground">{row.label}</span>
                  </div>
                ))}
                {/*
                  The one key the registry cannot own as a shortcut: Escape is not an
                  action but the way out of whatever is on top of the list. ⌘K used to be
                  drawn here beside it and now comes from `app.palette`'s `hint`.
                */}
                {section.mode === undefined && (
                  <div className="contents">
                    <Kbd className="justify-self-start">Esc</Kbd>
                    <span className="text-13 text-muted-foreground">Close</span>
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </Backdrop>
  );
}
