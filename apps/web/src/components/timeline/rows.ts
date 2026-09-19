import type { TimelineProject, TimelineTicket, TimelineView } from "@/lib/api";

/**
 * A lane of the chart, and a line of the column beside it.
 *
 * `team` is the level `KAN-9x` adds above the project one. It carries a key rather than an
 * id because that is what the response gives a ticket — `teamKey` — and inventing a lookup
 * to a team row the timeline does not fetch would be a second request for a heading.
 */
export type Row =
  | { kind: "team"; teamKey: string }
  | { kind: "project"; project: TimelineProject }
  | { kind: "ticket"; ticket: TimelineTicket };

/**
 * A row that has a lane in the chart — everything but a team heading.
 *
 * `row.tsx` and `arrows.tsx` take this rather than [Row]: a team heading spans the column
 * and draws nothing beside it, so the two files that position things against the time axis
 * must not be handed one. Narrowing here rather than branching in each of them keeps the
 * impossible case out of both.
 */
export type LaneRow = Exclude<Row, { kind: "team" }>;

/** Ids come from different tables, so the kind is part of the key. */
export const rowKey = (row: Row): string =>
  row.kind === "team"
    ? `team:${row.teamKey}`
    : row.kind === "project"
      ? `project:${row.project.id}`
      : `ticket:${row.ticket.id}`;

/**
 * Whether the column draws team headings at all.
 *
 * **Only when the scope spans more than one team.** On a team's own timeline a heading
 * would repeat that team's name against every row, distinguish nothing, and cost a level of
 * indentation to say it. On the global chart it is the only thing that tells two `KAN-1`s
 * apart.
 *
 * This is the rule `StatusCategory.label` already follows on the server — a bucket header
 * prints its category's word only when the scope spans two vocabularies — and it is the
 * same judgement for the same reason: a heading that never distinguishes anything is a
 * heading nobody reads, and then stops seeing.
 */
export function spansTeams(tickets: readonly TimelineTicket[]): boolean {
  const keys = new Set<string>();
  for (const ticket of tickets) {
    keys.add(ticket.teamKey);
    if (keys.size > 1) return true;
  }
  return false;
}

/**
 * Rows in reading order: each team once when there is more than one, each project once
 * under its team, its tickets under it, and the project-less ones last under no heading.
 *
 * Pure — no React, no fetch — for the reason `template-fill.ts` and `PrLinkParser` are: the
 * grouping is a rule about what somebody reads, and it has to be assertable as a table of
 * inputs rather than through a rendered component.
 *
 * A project with no tickets on this page still gets its row: its bar may come from an
 * explicit bound, and dropping it would make the chart lose a bar that has nothing to do
 * with which tickets the page happened to carry.
 */
export function buildRows(view: TimelineView | undefined): Row[] {
  if (!view) return [];

  const headed = spansTeams(view.tickets);
  const teamKeys = headed
    ? [...new Set(view.tickets.map((ticket) => ticket.teamKey))].sort((a, b) => a.localeCompare(b))
    : [undefined];

  // A project belongs to the team its tickets are in. The response carries no team on a
  // project row, and asking for one would be a second query for a heading — so the first
  // ticket under it decides, and a project nobody on this page has work in sits in the
  // first group, which is the only answer available.
  const teamOfProject = new Map<string, string>();
  for (const ticket of view.tickets) {
    if (ticket.projectId && !teamOfProject.has(ticket.projectId)) {
      teamOfProject.set(ticket.projectId, ticket.teamKey);
    }
  }

  const rows: Row[] = [];

  for (const teamKey of teamKeys) {
    const mine = view.tickets.filter(
      (ticket) => teamKey === undefined || ticket.teamKey === teamKey,
    );
    if (mine.length === 0 && teamKey !== undefined) {
      // A team with nothing on this page gets no heading. A heading over nothing is the
      // empty-group defect the tray used to have, in reverse.
      const ownsAProject = view.projects.some(
        (project) => teamOfProject.get(project.id) === teamKey,
      );
      if (!ownsAProject) continue;
    }

    const projectsHere = view.projects.filter((project) =>
      teamKey === undefined
        ? true
        : teamOfProject.get(project.id) === teamKey ||
          mine.some((ticket) => ticket.projectId === project.id),
    );
    const drawnHere = new Set(projectsHere.map((project) => project.id));

    /**
     * Everything of this team's that no project row in *this group* will carry.
     *
     * The second half of that condition is the one that matters, and it is there because
     * the e2e suite caught its absence: a shared project is filed under whichever team's
     * ticket named it first, so a ticket from the *other* team pointing at that same
     * project was neither under a project row in its own group nor an orphan — it fell
     * through both branches and vanished from the chart. A row must always land somewhere.
     */
    const orphansHere = mine.filter(
      (ticket) => ticket.projectId === undefined || !drawnHere.has(ticket.projectId),
    );

    if (projectsHere.length === 0 && orphansHere.length === 0) continue;
    if (teamKey !== undefined) rows.push({ kind: "team", teamKey });

    for (const project of projectsHere) {
      rows.push({ kind: "project", project });
      for (const ticket of mine) {
        if (ticket.projectId === project.id) rows.push({ kind: "ticket", ticket });
      }
    }

    for (const ticket of orphansHere) rows.push({ kind: "ticket", ticket });
  }

  return rows;
}
