"use client";

import { useMemo, useRef, useState } from "react";
import { shortcutRows } from "@/lib/actions";
import {
  TICKET_PRIORITIES,
  TICKET_STATUSES,
  type Project,
  type Ticket,
  type TicketPriority,
  type TicketStatus,
} from "@/lib/api";
import { statusLabel } from "./pills";

function Backdrop({ onClose, children }: { onClose: () => void; children: React.ReactNode }) {
  return (
    <div className="backdrop" onClick={onClose}>
      <div className="panel" onClick={(event) => event.stopPropagation()}>
        {children}
      </div>
    </div>
  );
}

/**
 * One input, Enter to create. Anything else about the ticket is a keystroke away
 * once it exists — asking for a status and a project up front is what makes other
 * trackers slow to file into.
 */
export function Composer({
  onCreate,
  onClose,
  pending,
}: {
  onCreate: (title: string) => void;
  onClose: () => void;
  pending: boolean;
}) {
  const [title, setTitle] = useState("");

  return (
    <Backdrop onClose={onClose}>
      <input
        className="composer-input"
        autoFocus
        placeholder="New ticket…"
        value={title}
        disabled={pending}
        onChange={(event) => setTitle(event.target.value)}
        onKeyDown={(event) => {
          event.stopPropagation();
          if (event.key === "Enter" && title.trim()) onCreate(title.trim());
          if (event.key === "Escape") onClose();
        }}
      />
      <div className="composer-footer">
        <kbd>↵</kbd> create <kbd>esc</kbd> cancel
        {pending && <span style={{ marginLeft: "auto" }}>saving…</span>}
      </div>
    </Backdrop>
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
      <div className="panel-header">
        <input
          style={{ flex: 1, border: "none", padding: 0 }}
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
      <div className="panel-body" style={{ padding: 0, gap: 0 }}>
        {matches.length === 0 && <div className="empty">No matching command</div>}
        {matches.map((command, index) => (
          <button
            key={command.id}
            className="palette-option"
            data-active={index === active}
            onMouseEnter={() => setActive(index)}
            onClick={command.run}
          >
            <span>{command.label}</span>
            {command.hint && <span className="hint">{command.hint}</span>}
          </button>
        ))}
      </div>
      <div className="palette-footer">
        <kbd>↑</kbd> <kbd>↓</kbd> move <kbd>↵</kbd> run <kbd>esc</kbd> close
      </div>
    </Backdrop>
  );
}

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

  return (
    <Backdrop onClose={onClose}>
      <div className="panel-header">
        <span style={{ fontFamily: "var(--mono)", color: "var(--text-faint)" }}>{ticket.identifier}</span>
        <strong style={{ flex: 1 }}>{ticket.title}</strong>
        <button className="button" onClick={onClose}>
          Close
        </button>
      </div>

      <div className="panel-body">
        <label>
          Status
          <select
            value={ticket.status}
            onChange={(event) => onPatch({ status: event.target.value as TicketStatus })}
          >
            {TICKET_STATUSES.map((status) => (
              <option key={status} value={status}>
                {statusLabel(status)}
              </option>
            ))}
          </select>
        </label>

        <label>
          Priority
          <select
            value={ticket.priority}
            onChange={(event) => onPatch({ priority: event.target.value as TicketPriority })}
          >
            {TICKET_PRIORITIES.map((priority) => (
              <option key={priority} value={priority}>
                {priority}
              </option>
            ))}
          </select>
        </label>

        <label>
          Project
          <select
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
        </label>

        <label>
          Due date
          <input
            type="date"
            value={ticket.dueDate ?? ""}
            onChange={(event) =>
              onPatch(event.target.value ? { dueDate: event.target.value } : { unset: ["dueDate"] })
            }
          />
        </label>

        <label>
          Description
          <textarea
            rows={6}
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

        <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
          <button className="button" onClick={() => onPatch({ archived: !ticket.archived })}>
            {ticket.archived ? "Unarchive" : "Archive"}
          </button>
          <button className="button error" onClick={onDelete}>
            Delete
          </button>
          <span style={{ marginLeft: "auto", color: "var(--text-faint)", fontSize: 11 }}>
            {ticket.mirror.notionPageId ? "Mirrored in Notion" : "Not in Notion yet"}
          </span>
        </div>
      </div>
    </Backdrop>
  );
}

export function HelpOverlay({ onClose }: { onClose: () => void }) {
  return (
    <Backdrop onClose={onClose}>
      <div className="panel-header">
        <strong style={{ flex: 1 }}>Keyboard</strong>
        <button className="button" onClick={onClose}>
          Close
        </button>
      </div>
      <div className="panel-body">
        <div className="shortcuts">
          {shortcutRows().map((row) => (
            <div key={row.keys} style={{ display: "contents" }}>
              <kbd>{row.keys}</kbd>
              <span>{row.label}</span>
            </div>
          ))}
          {/*
            The two keys the registry cannot own: the palette is a modified key,
            resolved before the registry is consulted, and Escape is not an action
            but the way out of whatever is on top of the list.
          */}
          <div style={{ display: "contents" }}>
            <kbd>⌘K / Ctrl+K</kbd>
            <span>Command palette</span>
          </div>
          <div style={{ display: "contents" }}>
            <kbd>Esc</kbd>
            <span>Close</span>
          </div>
        </div>
      </div>
    </Backdrop>
  );
}
