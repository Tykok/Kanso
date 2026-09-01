"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMemo } from "react";
import {
  EFFORT_POINTS,
  TICKET_PRIORITIES,
  TICKET_STATUSES,
  dayValue,
  fromDayValue,
  ticketHref,
  type EffortPoints,
  type Ticket,
  type TicketPriority,
  type TicketStatus,
  parseTicketKey,
} from "@/lib/api";
import { PRIORITY_LABELS, STATUS_LABELS } from "@/lib/status";
import {
  usePatchTicket,
  useProjects,
  useTeams,
  useTicketByKey,
  useTimeline,
  useUsers,
} from "@/lib/queries";
import { useUi } from "@/store/ui";
import { SyncBadge } from "../pills";
import { Kbd } from "../ui/kbd";
import { PriorityMark } from "../ui/priority-mark";
import { StatusDot } from "../ui/status-dot";
import { TicketLabels } from "../ticket-labels";
import { Avatar } from "./avatar";
import { ViewsShell } from "./shell";

/**
 * Screen 03 — the panel's content at page width.
 *
 * The same ticket, the same fields, the same wire calls; what changes is the measure.
 * The drawing gives the reading column 720px and the description 15px/1.75 against the
 * panel's 13px, because the panel is something you glance at beside a list and this is
 * something you read. Nothing here is a second way to edit a ticket: every control is
 * the panel's own control at a larger size, writing through `usePatchTicket`.
 */

/** A chip in the drawing's property row: 26px tall, the hover fill, the label inline. */
const CHIP =
  "inline-flex h-[26px] min-w-0 items-center gap-[7px] rounded-md bg-accent px-2.5 text-12 text-muted-foreground";

/** The `<select>` inside a chip, dressed down to read as the chip's own text. */
const CHIP_SELECT = "min-w-0 border-none bg-transparent p-0 text-12 text-muted-foreground";

export function TicketPageView({ ticketKey }: { ticketKey: string }) {
  const query = useTicketByKey(ticketKey);
  const ticket = query.data;

  /**
   * A key that does not parse never becomes a request — `useTicketByKey` disables itself —
   * so `isError` stays false and, until this existed, the page drew nothing at all. The
   * refusal has to be said in both cases: one of them is a typo somebody can see and fix,
   * and a blank screen tells them neither that the link is wrong nor that it was read.
   */
  const unresolvable = parseTicketKey(ticketKey) === null;

  return (
    <ViewsShell
      tickets={ticket ? [ticket] : []}
      selected={ticket}
      footer={
        ticket ? (
          <>
            <SyncBadge mirror={ticket.mirror} />
            <span>{ticket.mirror.notionPageId ? "Mirrored in Notion" : "Not in Notion yet"}</span>
            <span style={{ flex: 1 }} />
            <span>
              <kbd>e</kbd> rename
            </span>
            <span>
              <kbd>1</kbd>–<kbd>6</kbd> status
            </span>
          </>
        ) : undefined
      }
    >
      {query.isLoading && !unresolvable && <div className="empty">Loading…</div>}
      {(query.isError || unresolvable) && (
        <div className="empty error">
          {/* The interesting failure is a 404, and it is about a link rather than about
              the network: saying which key did not resolve is what lets the reader see
              the typo. */}
          No ticket answers to {ticketKey}.
        </div>
      )}
      {ticket && <TicketBody ticket={ticket} />}
    </ViewsShell>
  );
}

function TicketBody({ ticket }: { ticket: Ticket }) {
  const teams = useTeams();
  const projects = useProjects();
  const users = useUsers();
  const patch = usePatchTicket();
  const router = useRouter();
  const { setScope, select, open } = useUi();

  const team = teams.data?.find((candidate) => candidate.id === ticket.teamId);
  const project = projects.data?.find((candidate) => candidate.id === ticket.projectId);
  const assignees = (users.data ?? []).filter((person) => ticket.assigneeIds.includes(person.id));

  /**
   * What this ticket waits on. Read off the timeline response, which is where the
   * dependency edges live — there is no per-ticket dependency endpoint, and the chart's
   * query is scoped, so a ticket outside the current scope resolves to no chips rather
   * than to wrong ones. Drawn only when it has something to draw, exactly as the panel
   * does with a field it cannot fill.
   */
  const timeline = useTimeline(true);
  const dependsOn = useMemo(() => {
    const edges = timeline.data?.dependencies ?? [];
    const rows = timeline.data?.tickets ?? [];
    return edges
      .filter((edge) => edge.successorId === ticket.id)
      .flatMap((edge) => rows.find((row) => row.id === edge.predecessorId) ?? []);
  }, [timeline.data, ticket.id]);

  const set = (body: Parameters<typeof patch.mutate>[0]) => patch.mutate(body);

  return (
    <>
      <div className="flex items-center gap-2.5 bg-card px-5 py-3 text-12 text-faint">
        {team && (
          <>
            {/* A breadcrumb that selects a scope has to land on the screen that draws
                it, or the click means nothing. */}
            <Link
              href="/"
              className="hover:text-foreground"
              onClick={() => setScope({ kind: "team", id: team.id })}
            >
              {team.name}
            </Link>
            <span>/</span>
          </>
        )}
        {project && (
          <>
            <Link
              href="/"
              className="hover:text-foreground"
              onClick={() => setScope({ kind: "project", id: project.id })}
            >
              {project.name}
            </Link>
            <span>/</span>
          </>
        )}
        <span className="font-mono text-muted-foreground">{ticket.identifier}</span>
        <span className="flex-1" />
        {/*
          * The drawing's `⤡`, and the exact inverse of the panel's `⤢`: the same ticket,
          * the smaller measure, the list back behind it. It selects before it navigates
          * so the panel opens on this ticket rather than on whatever the cursor was left
          * on — and, like `⤢`, it does not touch the preference.
          */}
        <button
          type="button"
          title="Collapse into the panel"
          aria-label="Collapse into the panel"
          className="inline-flex size-6 items-center justify-center rounded-sm text-muted-foreground hover:bg-accent hover:text-foreground"
          onClick={() => {
            setScope(project ? { kind: "project", id: project.id } : { kind: "team", id: ticket.teamId });
            select(ticket.id);
            open("detail");
            router.push("/");
          }}
        >
          ⤡
        </button>
        {ticket.mirror.notionPageId && (
          <a
            className="text-muted-foreground hover:text-foreground"
            href={`https://www.notion.so/${ticket.mirror.notionPageId.replace(/-/g, "")}`}
            target="_blank"
            rel="noreferrer"
          >
            Open in Notion ↗
          </a>
        )}
        <Kbd>esc</Kbd>
      </div>

      <div className="flex min-h-0 flex-1 justify-center overflow-y-auto px-10 pt-11 pb-10">
        <div className="flex w-[720px] max-w-full flex-col gap-5">
          <h1 className="m-0 text-30 leading-tight font-medium tracking-tight">{ticket.title}</h1>

          {/* The panel's metadata table, unrolled into one row of chips: at 720px the
              two-column table would leave a third of the measure empty. */}
          <div className="flex flex-wrap gap-1.5">
            <label className={CHIP}>
              <StatusDot status={ticket.status} />
              <span className="sr-only">Status</span>
              <select
                className={CHIP_SELECT}
                value={ticket.status}
                onChange={(event) =>
                  set({ id: ticket.id, status: event.target.value as TicketStatus })
                }
              >
                {TICKET_STATUSES.map((status) => (
                  <option key={status} value={status}>
                    {STATUS_LABELS[status]}
                  </option>
                ))}
              </select>
            </label>

            <label className={CHIP}>
              <PriorityMark priority={ticket.priority} />
              <span className="sr-only">Priority</span>
              <select
                className={CHIP_SELECT}
                value={ticket.priority}
                onChange={(event) =>
                  set({ id: ticket.id, priority: event.target.value as TicketPriority })
                }
              >
                {TICKET_PRIORITIES.map((priority) => (
                  <option key={priority} value={priority}>
                    {PRIORITY_LABELS[priority]}
                  </option>
                ))}
              </select>
            </label>

            {/* Reads as `5 pts` at rest and picks from the same six values the panel
                offers: one vocabulary, two measures of the same screen. The unit is on the
                chip rather than in the option, because a chip has no label beside it. */}
            <label className={CHIP}>
              <span className="sr-only">Estimate in points</span>
              <select
                className={CHIP_SELECT}
                value={ticket.estimate ?? ""}
                onChange={(event) =>
                  set(
                    event.target.value
                      ? { id: ticket.id, estimate: Number(event.target.value) as EffortPoints }
                      : { id: ticket.id, unset: ["estimate"] },
                  )
                }
              >
                <option value="">— pts —</option>
                {EFFORT_POINTS.map((points) => (
                  <option key={points} value={points}>
                    {points}
                  </option>
                ))}
              </select>
              {ticket.estimate !== undefined && <span aria-hidden>pts</span>}
            </label>

            <label className={CHIP}>
              <span className="sr-only">Due date</span>
              <input
                type="date"
                className={CHIP_SELECT}
                value={dayValue(ticket.due)}
                onChange={(event) =>
                  set(
                    event.target.value
                      ? { id: ticket.id, due: fromDayValue(event.target.value) ?? undefined }
                      : // An absent key means "unchanged" on the wire, so clearing a
                        // bound is a named field and not an empty value.
                        { id: ticket.id, unset: ["due"] },
                  )
                }
              />
            </label>

            {/*
              * Read-only, unlike the three chips above it, and deliberately so: the
              * assignees are written by `PUT /api/tickets/{id}/assignees`, which the
              * client's `api` object does not carry — slice A adds no write call, and a
              * chip that looks like the editable ones beside it and refuses to be
              * clicked would be worse than one that plainly reads.
              */}
            {assignees.map((person) => (
              <span key={person.id} className={CHIP}>
                <Avatar displayName={person.displayName} size={18} />
                {person.displayName}
              </span>
            ))}

            {/* Writable, unlike the assignees beside it: `PUT /api/tickets/{id}/labels` is
                a call the client does carry, and it is the same control the panel draws —
                the labels of one ticket are one question, not two implementations. */}
            <TicketLabels ticket={ticket} />
          </div>

          {dependsOn.length > 0 && (
            <div className="flex flex-wrap items-center gap-2.5 text-12 text-faint">
              <span>Depends on</span>
              {dependsOn.map((predecessor) => (
                <Link
                  key={predecessor.id}
                  href={ticketHref(predecessor.identifier)}
                  className="inline-flex h-6 items-center rounded-sm bg-accent-soft px-2.5 font-mono text-11 text-accent-ink"
                >
                  {predecessor.identifier}
                </Link>
              ))}
            </div>
          )}

          <div className="my-1 h-px bg-border" />

          <TicketDescription ticket={ticket} onSave={set} />
        </div>
      </div>
    </>
  );
}

/**
 * The description at reading size.
 *
 * A `<textarea>` and not the drawing's rendered blocks: `tickets.description` is one
 * text column, and the block editor the drawing hints at with its `/` affordance is
 * slice B's document subsystem, not a ticket field. Saved on blur, exactly as the
 * panel does — a description is a paragraph somebody is still writing, and a keystroke
 * is not an edit worth a request.
 */
function TicketDescription({
  ticket,
  onSave,
}: {
  ticket: Ticket;
  onSave: (body: { id: string; description?: string; unset?: string[] }) => void;
}) {
  return (
    <label className="flex flex-col gap-2">
      <span className="sr-only">Description</span>
      <textarea
        // `field-sizing-content` so the box is as tall as what is in it: a fixed six
        // rows in a 720px reading column would either clip a long description or leave
        // a short one sitting in a hole.
        className="min-h-40 w-full resize-none border-none bg-transparent p-0 text-15 leading-[1.75] text-foreground field-sizing-content"
        placeholder="No description yet."
        defaultValue={ticket.description ?? ""}
        onKeyDown={(event) => event.stopPropagation()}
        onBlur={(event) => {
          const next = event.target.value;
          if (next === (ticket.description ?? "")) return;
          onSave(next ? { id: ticket.id, description: next } : { id: ticket.id, unset: ["description"] });
        }}
      />
    </label>
  );
}
