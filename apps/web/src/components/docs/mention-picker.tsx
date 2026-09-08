"use client";

import { labelOfKey } from "@/lib/statuses";
import { useState } from "react";
import type { Ticket, User } from "@/lib/api";
import { STATUS_LABELS } from "@/lib/status";
import { Backdrop } from "../overlays";
import { StatusDot } from "../ui/status-dot";
import type { DocMention } from "@/store/docs";

/**
 * What `#` and `@` open. One component for both because they are one gesture — pick a
 * thing by typing part of its name — and the only difference is what a row draws and
 * what happens on choosing.
 *
 * `#` inserts a ticket link block: a reference to the same ticket, which is why it earns
 * a block. `@` inserts the person's name into the text being written, and stops there —
 * a *stored* mention, the kind that reaches somebody's inbox, needs the
 * `comment_mentions` table slice 0 was to bring and slice D's notifications to deliver.
 * Writing half of that here would put a second, undelivered definition of "mentioned" in
 * the tree.
 */
export function MentionPicker({
  mention,
  tickets,
  people,
  onPickTicket,
  onPickPerson,
  onClose,
}: {
  mention: DocMention;
  tickets: Ticket[];
  people: User[];
  onPickTicket: (ticketId: string) => void;
  onPickPerson: (person: User) => void;
  onClose: () => void;
}) {
  const [filter, setFilter] = useState("");
  const needle = filter.trim().toLowerCase();

  const matchingTickets = tickets
    .filter(
      (ticket) =>
        (ticket.identifier?.toLowerCase().includes(needle) ?? false) ||
        ticket.title.toLowerCase().includes(needle),
    )
    .slice(0, 8);
  const matchingPeople = people
    .filter(
      (person) =>
        person.displayName.toLowerCase().includes(needle) ||
        person.email.toLowerCase().includes(needle),
    )
    .slice(0, 8);

  return (
    <Backdrop onClose={onClose} panelClassName="w-[480px]">
      <div className="flex flex-col gap-2 p-3">
        <input
          autoFocus
          data-testid="mention-filter"
          aria-label={mention === "ticket" ? "Mention a ticket" : "Mention a person"}
          placeholder={mention === "ticket" ? "Mention a ticket…" : "Mention a person…"}
          value={filter}
          className="w-full"
          onChange={(event) => setFilter(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Escape") {
              event.preventDefault();
              onClose();
            }
            event.stopPropagation();
          }}
        />

        <div className="flex flex-col">
          {mention === "ticket"
            ? matchingTickets.map((ticket) => (
                <button
                  key={ticket.id}
                  data-testid="mention-choice"
                  className="flex items-center gap-2.5 rounded-md px-2 py-2 text-left hover:bg-accent"
                  onClick={() => onPickTicket(ticket.id)}
                >
                  <StatusDot status={ticket.status} />
                  <span className="font-mono text-11 text-faint">{ticket.identifier}</span>
                  <span className="min-w-0 flex-1 truncate text-13">{ticket.title}</span>
                  <span className="text-11 text-faint">{labelOfKey(ticket.status)}</span>
                </button>
              ))
            : matchingPeople.map((person) => (
                <button
                  key={person.id}
                  data-testid="mention-choice"
                  className="flex items-center gap-2.5 rounded-md px-2 py-2 text-left hover:bg-accent"
                  onClick={() => onPickPerson(person)}
                >
                  <span className="min-w-0 flex-1 truncate text-13">{person.displayName}</span>
                  <span className="text-11 text-faint">{person.email}</span>
                </button>
              ))}

          {(mention === "ticket" ? matchingTickets : matchingPeople).length === 0 && (
            <span className="px-2 py-2 text-12 text-faint">Nothing by that name.</span>
          )}
        </div>
      </div>
    </Backdrop>
  );
}
