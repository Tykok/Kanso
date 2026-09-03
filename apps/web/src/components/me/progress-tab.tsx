"use client";

import { ProgressView } from "@/components/organise/progress-view";

/**
 * The pace, drawn by screen 40 and by nothing here.
 *
 * That screen landed on `main` while this branch was open, as `/progress` +
 * `components/organise/progress-view.tsx` + `lib/progress.ts`: the pace in force and which
 * of the two numbers it is, points delivered per closed cycle, the load being carried
 * against that pace, and how that load is cut. The ruling was that `/me` is the tabbed home
 * and that screen becomes this tab, so this file mounts it and redraws nothing.
 *
 * Nothing of it is redrawn, and that is the point. Two copies of a velocity chart is two
 * places for one arbitration to be explained differently — the specific failure
 * `lib/velocity.ts` exists to prevent, since the server arbitrates once and the client only
 * puts the verdict into words.
 *
 * It also made a ruling that must not be silently contradicted: median cycle time was absent
 * on purpose, waiting on the `activity` time-in-status work, because "a plausible-looking
 * stub is worse than a gap" — and a tab that filled the space with a plausible chart would
 * have been exactly that stub. KAN-23 did the `activity` work, so the gap is closed by the
 * measurement it was held open for. It is still not drawn *here*: it arrived inside
 * `ProgressView`, on the same request, which is why this file is still a host that redraws
 * nothing.
 *
 * **Which endpoint this reads, so nobody merges the two on a hunch.** `ProgressView` reads
 * `GET /api/me/progress` — my pace *inside one team*, resolved by `useOrganiseTeam`, with
 * `EffectiveVelocity`, `DeliveredCycle[]` and `OpenLoad`. The other four tabs read
 * `GET /api/me/stats` — my counts and my twelve weeks *across every team*, which is why it
 * takes no `teamId` at all. Both endpoints survive: a rate cannot be added across teams (a
 * cycle is one team's calendar with one team's dates) while counts and point sums can, and
 * that difference is the whole reason there are two.
 *
 * The scroller is this tab's rather than the view's, so the four tabs beside it scroll the
 * same way: `ProgressView`'s own body sets the padding and the gaps it needs and stops
 * there.
 */
export function ProgressTab() {
  return (
    <div className="flex min-h-0 flex-1 flex-col" data-testid="me-progress-host">
      <ProgressView />
    </div>
  );
}
