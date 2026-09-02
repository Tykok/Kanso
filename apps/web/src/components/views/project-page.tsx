"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo } from "react";
import { TopbarSlot, usePageShell, useReportError } from "@/components/shell/topbar-slot";
import { dayValue, ticketAddress, ticketHref, type Project, type Ticket } from "@/lib/api";
import { STATUS_COLORS, STATUS_LABELS } from "@/lib/status";
import {
  useDocs,
  useMe,
  usePreferences,
  useProjectTickets,
  useProjects,
  useTeams,
  useUsers,
} from "@/lib/queries";
import { mayWrite } from "@/lib/seat";
import { useActionContext } from "@/lib/use-action-ctx";
import { useUi } from "@/store/ui";
import { StatusPill, SyncBadge, TicketIdentifier } from "../pills";
import { GroupLabel } from "../ui/group-label";
import { PriorityMark } from "../ui/priority-mark";
import { Row } from "../ui/row";
import { ActivityFeed } from "./activity-feed";
import { Avatar } from "./avatar";
import { donePercent, periodLabel, statusCounts } from "./project-copy";
import { HealthPill, ProjectHealthPanel } from "./project-health";

/** The first five and no more: the list is one screen away and it does this properly. */
const FIRST_TICKETS = 5;

/** The drawing's ticket row: id, priority, status, title, due. Narrower than the list's. */
const COLS = "grid-cols-[70px_20px_108px_1fr_68px]";

/**
 * Screen 05 — the project page.
 *
 * A summary and not a second list: five tickets, the counts as one bar, the documents its
 * tickets reference, and the feed. Everything here is a read of something already stored,
 * with one exception noted below — `projects` has no description column, and the
 * paragraph the drawing puts under the title has nowhere to come from.
 */
export function ProjectPageView({ projectId }: { projectId: string }) {
  const projects = useProjects();
  const teams = useTeams();
  const tickets = useProjectTickets(projectId);
  const preferences = usePreferences();
  const setScope = useUi((state) => state.setScope);
  const reportError = useReportError();

  const project = projects.data?.find((candidate) => candidate.id === projectId);
  const team = teams.data?.find((candidate) => candidate.id === project?.teamId);

  /**
   * What the registry may act on here: this project's rows.
   *
   * `ViewsShell` took them as a prop and built this; a layout cannot take props, so the
   * page publishes it instead. The four list-local callbacks stay inert for the reason
   * that shell gave: this screen draws a summary, so `j` and `k` have nothing to step
   * through and say so by doing nothing.
   */
  const noop = useCallback(() => {}, []);
  const ctx = useActionContext({
    tickets: tickets.data ?? [],
    selected: undefined,
    move: noop,
    startRename: noop,
    startLink: noop,
    startUnlink: noop,
    reportError,
  });

  // `Core / Sync engine`. The two links that used to spell this out are gone: the team
  // crumb was a `<Link href="/">` that set a scope on the way, which is the click the
  // sidebar's own rows now make honestly.
  usePageShell({ ctx, crumbs: { team: team?.name, leaf: project?.name } });

  /**
   * Landing here *is* looking at this project, so the scope follows.
   *
   * Without it, `c` would open the composer seeded from whatever the list was last
   * scoped to and file a ticket into another team — `creationSeed` reads the scope and
   * nothing else. It also makes the sidebar highlight the row the reader is on.
   */
  useEffect(() => {
    if (project) setScope({ kind: "project", id: project.id });
  }, [project, setScope]);

  return (
    <>
      {projects.isLoading && <div className="empty">Loading…</div>}
      {!projects.isLoading && !project && (
        <div className="empty error">No project answers to this address.</div>
      )}
      {project && <ProjectBody project={project} tickets={tickets.data ?? []} />}

      {/* The drawing's own footer strip, drawn by the page rather than handed to a shell:
          each screen names different keys, and the one thing they shared — the preference
          that hides the strip — is one hook call. */}
      {preferences.showStatusBar && (
        <div className="statusbar">
          <span>
            <kbd>c</kbd> new in this project
          </span>
          <span style={{ flex: 1 }} />
          {project && <SyncBadge mirror={project.mirror} />}
        </div>
      )}
    </>
  );
}

function ProjectBody({ project, tickets }: { project: Project; tickets: Ticket[] }) {
  const teams = useTeams();
  const users = useUsers();
  const docs = useDocs();
  const me = useMe();
  const { setScope, open } = useUi();

  const canWrite = mayWrite(me.data?.user.instanceRole);

  const team = teams.data?.find((candidate) => candidate.id === project.teamId);
  const lead = users.data?.find((candidate) => candidate.id === project.leadUserId);
  const counts = useMemo(() => statusCounts(tickets), [tickets]);
  const counting = counts.reduce((total, entry) => total + entry.count, 0);

  /**
   * The pages this project's work references.
   *
   * A project has no documents of its own — `ticket_docs` links a *ticket* to a Notion
   * page — so this is the union across its tickets, deduplicated. That is the honest
   * reading of "documents liés" against the schema as it stands, and it is also the useful
   * one: a page two of the project's tickets both point at is the project's page.
   */
  const linked = useMemo(() => {
    const wanted = new Set(tickets.flatMap((ticket) => ticket.docIds));
    return (docs.data ?? []).filter((doc) => wanted.has(doc.id));
  }, [tickets, docs.data]);

  const seeAll = () => setScope({ kind: "project", id: project.id });

  return (
    <>
      <TopbarSlot>
        <span className="flex-1" />
        <button
          type="button"
          className="button"
          onClick={() => useUi.getState().openDialog({ kind: "project", id: project.id })}
        >
          Edit
        </button>
        {/* The scope is already this project — the effect above set it — so the composer
            seeds itself from it and no argument has to be threaded through. Absent for a
            reader: the composer would refuse them, and a button that opens an apology is
            worse than no button. */}
        {canWrite && (
          <button type="button" className="button button-primary" onClick={() => open("composer")}>
            New ticket
          </button>
        )}
      </TopbarSlot>

      <div className="flex min-h-0 flex-1 flex-col gap-7 overflow-y-auto px-12 py-9">
        <div className="flex flex-wrap items-start gap-12">
          <div className="flex min-w-0 flex-1 flex-col gap-2.5">
            <div className="flex items-center gap-2.5">
              <span
                className="size-2 shrink-0 rounded-sm"
                aria-hidden
                style={{ background: STATUS_COLORS.in_progress }}
              />
              <span className="text-11 tracking-[0.1em] text-faint uppercase">
                {project.status} · {donePercent(counts)}%
              </span>
            </div>
            <h1 className="m-0 text-30 leading-tight font-medium tracking-tight">{project.name}</h1>
            {/*
              * The drawing puts a paragraph here and `projects` has no column to fill it
              * from — four columns and none of them prose. Slice A adds no migration, so
              * the paragraph is absent rather than invented; adding `projects.description`
              * is a schema question and not a screen's to answer.
              */}
          </div>

          <dl className="m-0 grid w-[260px] grid-cols-[76px_1fr] gap-3 text-12">
            <dt className="text-faint">Lead</dt>
            <dd className="m-0 flex items-center gap-1.5">
              {lead ? (
                <>
                  <Avatar displayName={lead.displayName} size={20} />
                  {lead.displayName}
                </>
              ) : (
                <span className="text-faint">—</span>
              )}
            </dd>
            <dt className="text-faint">Period</dt>
            <dd className="m-0">{periodLabel(project.start, project.end)}</dd>
            <dt className="text-faint">Team</dt>
            <dd className="m-0">{team ? team.name : <span className="text-faint">across teams</span>}</dd>
            {/*
              * A row of its own in the same table as Lead, Period and Team, rather than
              * a second word in the status line above: health is a fact about the
              * project, like its lead, and putting it beside the status would invite the
              * reading that it is a kind of status. The line above says where the work
              * is; this says whether it will land.
              */}
            <dt className="text-faint">Health</dt>
            <dd className="m-0">
              <HealthPill health={project.health} />
            </dd>
            <dt className="text-faint">Mirror</dt>
            <dd className="m-0">
              <SyncBadge mirror={project.mirror} />
            </dd>
          </dl>
        </div>

        {counting > 0 && (
          <div className="flex max-w-[860px] flex-col gap-2.5">
            <div
              className="flex h-2 overflow-hidden rounded-sm bg-accent"
              role="img"
              // One accessible name for the whole bar rather than six unlabelled slivers:
              // the segments have no text and each is only a few pixels wide.
              aria-label={counts
                .filter((entry) => entry.count > 0)
                .map((entry) => `${entry.count} ${STATUS_LABELS[entry.status].toLowerCase()}`)
                .join(", ")}
            >
              {counts
                .filter((entry) => entry.count > 0)
                .map((entry) => (
                  <div
                    key={entry.status}
                    style={{
                      width: `${(entry.count / counting) * 100}%`,
                      background: STATUS_COLORS[entry.status],
                    }}
                  />
                ))}
            </div>
            <div className="flex flex-wrap gap-5 text-11 text-muted-foreground">
              {counts
                .filter((entry) => entry.count > 0)
                .map((entry) => (
                  <span key={entry.status} className="inline-flex items-center gap-1.5">
                    <span
                      aria-hidden
                      className="size-2 rounded-sm"
                      style={{ background: STATUS_COLORS[entry.status] }}
                    />
                    {entry.count} {STATUS_LABELS[entry.status].toLowerCase()}
                  </span>
                ))}
            </div>
          </div>
        )}

        <div className="grid items-start gap-12 lg:grid-cols-[1fr_300px]">
          <div className="flex min-w-0 flex-col gap-2">
            <div className="flex items-baseline gap-2.5 pb-1">
              <span className="text-15 font-medium">Tickets</span>
              <span className="font-mono text-11 text-faint">{tickets.length}</span>
              <span className="flex-1" />
              {tickets.length > FIRST_TICKETS && (
                <Link href="/" className="text-12 text-muted-foreground" onClick={seeAll}>
                  See all
                </Link>
              )}
            </div>
            {tickets.length === 0 && <span className="text-12 text-faint">No tickets yet.</span>}
            {tickets.slice(0, FIRST_TICKETS).map((ticket) => (
              <Link key={ticket.id} href={ticketHref(ticketAddress(ticket))} className="contents">
                <Row data-testid="project-ticket" className={`grid ${COLS}`}>
                  <TicketIdentifier ticket={ticket} className="font-mono text-11 text-faint" />
                  <PriorityMark priority={ticket.priority} />
                  <StatusPill status={ticket.status} />
                  <span className="truncate">{ticket.title}</span>
                  <span className="text-11 text-faint">
                    {ticket.due ? dayValue(ticket.due).slice(5) : "—"}
                  </span>
                </Row>
              </Link>
            ))}
          </div>

          <div className="flex flex-col gap-6">
            {/* Above the documents and the feed: it is the newest thing anybody said about
                this project, and the one thing on the page a reader may have come for. */}
            <ProjectHealthPanel project={project} />

            {linked.length > 0 && (
              <div className="flex flex-col gap-2">
                <GroupLabel className="px-0 pt-0 pb-0">Linked documents</GroupLabel>
                {linked.map((doc) => (
                  <a
                    key={doc.id}
                    href={doc.url ?? "#"}
                    target="_blank"
                    rel="noreferrer"
                    className="flex items-center gap-2 rounded-md px-2 py-1.5 text-12 hover:bg-accent"
                  >
                    <span aria-hidden className="text-faint">
                      ◈
                    </span>
                    <span className="min-w-0 flex-1 truncate">{doc.title ?? doc.notionPageId}</span>
                  </a>
                ))}
              </div>
            )}

            <ActivityFeed entityType="project" entityId={project.id} />
          </div>
        </div>
      </div>
    </>
  );
}
