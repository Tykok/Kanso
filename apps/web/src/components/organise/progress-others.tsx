"use client";

import Link from "next/link";
import { GroupLabel } from "@/components/ui/group-label";
import { usePageShell } from "@/components/shell/topbar-slot";
import type { TeamProgress } from "@/lib/api";
import { teamLoadSentence } from "@/lib/progress";
import { usePersonProgress, useTeamMembers, useTeamProgress } from "@/lib/queries";
import { formatRate } from "@/lib/velocity";
import { ProgressBody } from "./progress-body";
import { Delivered, Projects, StatusBar } from "./progress-charts";
import { useOrganiseTeam } from "./team";

/**
 * Screen 41 — the same figures, aimed at somebody who is not the reader.
 *
 * **Why these are addresses and not a parameter on `/me`.** `/me` is the caller by
 * definition: the navigation rework moved screen 40 there and took the reader's own name
 * off the bar precisely because the page "is by definition about the person reading it". A
 * `?person=` on that route would make that sentence false, and would make one address
 * sometimes-403 — so a link pasted into a document would fail for the recipient with
 * nothing on screen explaining why. `lib/nav.ts` already states the rule these two follow:
 * "`?team=` and not a path segment: the team is context, not the subject." The subject of
 * this page is a person or a team, so it is in the path; the calendar the figures are
 * measured against is context, so it stays in `?team=`.
 *
 * **Neither view is a permission.** Both render whatever the server answers, and a refusal
 * is drawn as a sentence rather than avoided by hiding the route. The rule lives in
 * `ProgressAccess`, is asserted through the filter chain in `ProgressLeakTest`, and would
 * hold if these files were deleted — which is the whole point of the ticket.
 */

/** What a refusal looks like: the rule, in words, rather than an empty chart. */
function Refused({ what }: { what: string }) {
  return (
    <div className="empty flex-col gap-1">
      <span className="text-13 text-foreground">{what} are not yours to read</span>
      <span className="max-w-[420px] text-center text-12 text-faint">
        Figures about somebody else are readable by an owner or admin of the instance, and by
        an administrator of the team they are measured in. Your own are always yours.
      </span>
    </div>
  );
}

/**
 * One colleague's figures, in one team.
 *
 * `ProgressBody own={false}` and nothing else: the sections, the charts and KAN-40's two
 * refusals are the same objects the personal page draws, so a lone closed cycle still shows
 * a waiting message here and a cycle somebody delivered nothing in still keeps its stub.
 * Those matter more on this page than on the reader's own — somebody looking at a colleague
 * has no other way to know that the flat chart in front of them is one closed cycle rather
 * than a quiet quarter.
 */
export function PersonProgressView({ userId }: { userId: string }) {
  const { team } = useOrganiseTeam();
  const progress = usePersonProgress(team?.id, userId);

  usePageShell({ crumbs: { team: team?.name, leaf: progress.data?.person.displayName } });

  if (progress.isPending) {
    return <div className="px-4 py-12 text-center text-faint">Loading…</div>;
  }
  if (progress.error !== null || progress.data === undefined) {
    return <Refused what="Those figures" />;
  }
  return <ProgressBody progress={progress.data} own={false} />;
}

/**
 * A whole team's figures — aggregates, and by construction only aggregates.
 *
 * There is no ranking on this page and there is nothing on it to build one from: the
 * response carries no per-person field at all, which is stated at the service, at the wire
 * and in `lib/api/core.ts`. The plate is cut by status and by project — by things, never by
 * people. The per-person cut of the same plate is the workload screen, open to every reader
 * since screen 23, and it is not repeated here.
 */
export function TeamProgressView({ teamId }: { teamId: string }) {
  const { teams } = useOrganiseTeam();
  const progress = useTeamProgress(teamId);
  const named = teams.find((team) => team.id === teamId);

  usePageShell({ crumbs: { team: named?.name ?? progress.data?.team.name, leaf: "Progress" } });

  if (progress.isPending) {
    return <div className="px-4 py-12 text-center text-faint">Loading…</div>;
  }
  if (progress.error !== null || progress.data === undefined) {
    return <Refused what="A team's figures" />;
  }
  return <TeamBody progress={progress.data} />;
}

function TeamBody({ progress }: { progress: TeamProgress }) {
  const { pace, load, team } = progress;

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-6 overflow-y-auto p-6">
      <section className="flex flex-col gap-3">
        <GroupLabel className="px-0 pt-0">{team.name}&rsquo;s pace</GroupLabel>
        <div className="flex items-end gap-2.5">
          {/* An em dash, not a 0, for the reason the personal page gives: a team Kanso has
              never measured is not a team that delivers nothing. */}
          <span className="text-30 font-medium leading-none tracking-[-0.02em]">
            {pace.perWorkingDay === undefined ? "—" : formatRate(pace.perWorkingDay)}
          </span>
          <span className="pb-1 text-12 text-muted-foreground">
            {pace.perWorkingDay === undefined ? "no pace yet" : "points per working day"}
          </span>
        </div>
        {/* No `velocityCaption` here, and no `source`. A declared velocity is one person's
            estimate of themselves and nobody declares a team's, so there is no arbitration
            to caption — printing one would explain a rule that was never applied. What the
            number does need is how much history it stands on. */}
        <p className="m-0 max-w-[620px] text-12 text-muted-foreground">
          {pace.measuredCycles === 0
            ? "Measured over no closed cycle yet, so Kanso will not name a pace for this team."
            : `Measured over ${pace.measuredCycles} closed cycle${pace.measuredCycles === 1 ? "" : "s"},` +
              " as the mean of their rates rather than the pooled total."}
        </p>
      </section>

      <Delivered delivered={progress.delivered} paceLabel="this team's pace" />

      <section className="flex flex-col gap-3 border-t border-border pt-5">
        <GroupLabel className="px-0 pt-0">Carrying now</GroupLabel>
        <p className="m-0 max-w-[620px] text-13" data-testid="team-load-sentence">
          {teamLoadSentence(load, pace, team.name)}
        </p>
        {load.load.tickets > 0 && (
          <>
            <StatusBar load={load} />
            <Projects load={load} />
          </>
        )}
      </section>

      <People teamId={team.id} />
    </div>
  );
}

/**
 * Who this team is, in alphabetical order, with no number beside any name.
 *
 * This is the entry point to the per-person pages, and the ordering is the whole of what
 * keeps it from being the chart the ticket forbids. Names sorted by name carry no claim; a
 * list sorted by anything derived from the work — points, tickets, days — would *be* the
 * ranking, whichever column was drawn or left out, because the order alone is the
 * comparison. So it is `displayName`, ascending, and there is nothing else on the row.
 *
 * It reads `/api/teams/{id}/members`, which is not this feature's data at all: the same
 * list the team's settings screen draws, open to every reader long before this page
 * existed. Nothing about who delivered what reaches this component.
 */
function People({ teamId }: { teamId: string }) {
  const members = useTeamMembers(teamId);
  const rows = [...(members.data ?? [])].sort((a, b) =>
    a.user.displayName.localeCompare(b.user.displayName),
  );

  if (rows.length === 0) return null;

  return (
    <section className="flex flex-col gap-2 border-t border-border pt-5">
      <GroupLabel className="px-0 pt-0">People</GroupLabel>
      <p className="m-0 max-w-[620px] text-11 text-faint">
        By name. Their own figures are one page each — this list carries no number, and there
        is no order to it but the alphabet.
      </p>
      <div className="flex flex-col">
        {rows.map((row) => (
          <Link
            key={row.user.id}
            href={`/people/${row.user.id}/progress?team=${teamId}`}
            className="truncate py-1 text-13 text-foreground hover:underline"
            data-testid="team-person-row"
          >
            {row.user.displayName}
          </Link>
        ))}
      </div>
    </section>
  );
}
