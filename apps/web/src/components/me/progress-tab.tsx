"use client";

import { GroupLabel } from "@/components/ui/group-label";

/**
 * The pace — and the one tab on this screen this branch deliberately does not draw.
 *
 * **Why it is empty.** Screen 40 landed on `main` while this branch was open, as
 * `/progress` + `components/organise/progress-view.tsx` + `lib/progress.ts`: the pace in
 * force and which of the two numbers it is, points delivered per closed cycle, the load
 * being carried against that pace, and how that load is cut. The maintainer's ruling is
 * that `/me` is the tabbed home and that screen becomes this tab. So the tab exists here,
 * with its `?tab=progress` address, and `ProgressView`'s body drops into it at the merge.
 *
 * Nothing of it is redrawn here, and that is the point. Two copies of a velocity chart is
 * two places for the same arbitration to be explained differently — which is the specific
 * failure `lib/velocity.ts` exists to prevent, since the server arbitrates once and the
 * client only puts the verdict into words. It also made a ruling that must not be
 * silently contradicted: median cycle time is absent on purpose, waiting on the `activity`
 * time-in-status work, because "a plausible-looking stub is worse than a gap". A tab that
 * filled the space with a plausible chart would be exactly that stub.
 *
 * **Which endpoint this reads, so nobody merges the two on a hunch.** `ProgressView` reads
 * `GET /api/me/progress` — my pace *inside one team*, resolved by `useOrganiseTeam`, with
 * `EffectiveVelocity`, `DeliveredCycle[]` and `OpenLoad`. The other four tabs read
 * `GET /api/me/stats` — my counts and my twelve weeks *across every team*, which is why it
 * takes no `teamId` at all. Both endpoints survive: a rate cannot be added across teams
 * (a cycle is one team's calendar with one team's dates) while counts and point sums can,
 * and that difference is the whole reason there are two.
 *
 * At the merge this file's body becomes `<ProgressView />`, and `progress-view.tsx` needs
 * porting first: it renders `OrganiseShell`, which slice 1 of this branch deleted.
 */

export function ProgressTab() {
  return (
    <div className="flex flex-col gap-3 px-6 pt-4 pb-6">
      <GroupLabel className="px-0 pt-0">Your pace</GroupLabel>
      <p className="m-0 max-w-[620px] text-12 text-muted-foreground" data-testid="me-progress-host">
        Your pace, what you delivered per closed cycle and the load you are carrying
        against it are drawn by screen 40, which arrives in this tab. It is a per-team
        reading — a rate measured against one team&apos;s fortnights cannot be added to
        another&apos;s — where the four tabs beside it count across every team.
      </p>
      <p className="m-0 max-w-[620px] text-11 text-faint">
        Until then, the numbers a cycle in progress can answer are at the head of Done.
      </p>
    </div>
  );
}
