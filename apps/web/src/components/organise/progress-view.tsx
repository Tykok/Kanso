"use client";

import Link from "next/link";
import type { Team } from "@/lib/api";
import { useProgress, useTeamProgress } from "@/lib/queries";
import { ProgressBody } from "./progress-body";
import { useOrganiseTeam } from "./team";

/**
 * Screen 40 — the pace half of the page a person opens on themselves.
 *
 * Four things in descending order of usefulness: the pace in force and which of the two
 * numbers it is, what was delivered per closed cycle, what is being carried right now
 * against that pace, and how that load is cut. Nothing on it is comparative and nothing on
 * it is a grade — see the head of `lib/progress.ts` for why that is a design constraint
 * rather than a preference. The drawings are `progress-charts.tsx` and the sections are
 * `progress-body.tsx`, shared with the version of this page aimed at somebody else.
 *
 * Median cycle time was deliberately absent, waiting on the insights ticket that reads
 * `activity` for time-in-status rather than on a plausible-looking stub — nobody ever goes
 * back and checks a number that is already on the screen. KAN-23 filled it, and filled it
 * the way the gap was left: the median arrives on the same `GET /api/me/progress` as
 * everything else here, measured over the very cycles the bars above are drawn from, so the
 * two cannot come to disagree about which tickets they are describing.
 *
 * **It draws no chrome of its own, and used to.** It arrived on `main` as a page rendering
 * `OrganiseShell`, with a `Team / My progress` crumb and the reader's own name at the right
 * of the bar. That shell is gone — one shell now, in `app/(app)/layout.tsx` — and this is
 * no longer a page: it is `/me`'s **Progress** tab, so the crumb belongs to `/me` and the
 * name is redundant on a screen that is by definition about the person reading it.
 * `/progress` survives as a redirect, because a link somebody pasted last week is not a
 * thing to break in a refactor.
 *
 * What did *not* change is the arbitration. It still reads `GET /api/me/progress`, still a
 * per-team rate resolved through `useOrganiseTeam` — which is why it is one tab beside four
 * that count across every team, rather than merged into their request. `me/progress-tab.tsx`
 * writes that difference down at more length.
 */
export function ProgressView() {
  const { team } = useOrganiseTeam();
  const progress = useProgress(team?.id);

  return (
    <>
      {progress.isPending && <div className="px-4 py-12 text-center text-faint">Loading…</div>}

      {/* A team with no cycles and a person with nothing assigned still renders the whole
          page — every section below has a branch for its own absence. So the only empty
          state here is the one where the request itself failed. */}
      {progress.error !== null && !progress.isPending && (
        // `flex` beside `flex-col` — see the note in `progress-others.tsx`: `.empty` sets no
        // `display`, so this block had been rendering its two lines run together since
        // KAN-40.
        <div className="empty flex flex-col items-center gap-1">
          <span className="text-13 text-foreground">Your progress is not available</span>
          <span className="text-12 text-faint">
            {team === undefined
              ? "This instance has no team to measure you against yet."
              : "Kanso could not read your figures for this team."}
          </span>
        </div>
      )}

      {progress.data && (
        <>
          <ProgressBody progress={progress.data} own />
          {team && <Wider team={team} />}
        </>
      )}
    </>
  );
}

/**
 * The way out to the wider view, and nothing at all for a reader who may not have it.
 *
 * **The probe is `useTeamProgress`, not a flag on `/api/me`.** The server's rule for a
 * team's figures is the rule for reading another person with the "yourself, always" branch
 * taken out, so its verdict is exactly the question this component needs to ask — and in
 * the granted case the answer is a request the team page was going to make anyway. A
 * capability boolean on the session would have been a second spelling of a rule that
 * already has one, free to disagree with it the day the rule moves.
 *
 * Note what this is *not*: a picker of colleagues. A dropdown of people on one's own page
 * would make reading somebody's productivity figures a two-click idle gesture. Requiring
 * the reader to go to the team first stops nobody who has a reason, and invites nobody who
 * has not — and the team page is the one whose numbers name no person at all.
 *
 * Silent when refused, which is the one case where hiding a control is honest: the server
 * has already answered, so this is drawing what is true rather than guessing at a rule.
 */
function Wider({ team }: { team: Team }) {
  const wider = useTeamProgress(team.id);
  if (wider.isPending || wider.error !== null) return null;

  return (
    <div className="border-t border-border px-6 pb-6 pt-4 text-12 text-muted-foreground">
      <p className="m-0 max-w-[620px]">
        You administer {team.name}, so you can also read{" "}
        <Link
          className="text-foreground underline decoration-rule"
          href={`/teams/${team.id}/progress?team=${team.id}`}
        >
          {team.name}&rsquo;s own figures
        </Link>
        {" — aggregates only, and no ranking of people."}
      </p>
    </div>
  );
}
