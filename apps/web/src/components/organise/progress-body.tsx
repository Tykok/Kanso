"use client";

import { GroupLabel } from "@/components/ui/group-label";
import type { Progress } from "@/lib/api";
import { about, loadSentence, readersSentence, YOURS } from "@/lib/progress";
import { formatRate, velocityCaption } from "@/lib/velocity";
import { Delivered, Projects, StatusBar } from "./progress-charts";

/**
 * One person's figures, drawn the same way whoever is reading.
 *
 * [own] is the only prop that varies, and everything the page says about its subject is
 * derived from it — the heading, the pronouns, the caption under the chart. One flag rather
 * than four strings, because four call sites passing four strings is four chances to hand
 * the admin view a sentence written in the second person, which would tell an administrator
 * that *they* are carrying somebody else's fortnight.
 *
 * Nothing else changes between the two readers, and that is deliberate: KAN-40's two
 * refusals — a waiting message rather than a lone bar, a visible stub rather than a missing
 * bar — are in `progress-charts.tsx` for both, and they matter more when the reader is not
 * the subject, not less. See the head of `lib/progress.ts` for why none of this may read as
 * a grade.
 */
export function ProgressBody({ progress, own }: { progress: Progress; own: boolean }) {
  const name = progress.person.displayName;

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-6 overflow-y-auto p-6">
      <Pace progress={progress} heading={own ? "Your pace" : `${name}'s pace`} />
      <Delivered delivered={progress.delivered} paceLabel={own ? "your pace" : "their pace"} />
      <Load progress={progress} own={own} />
      <Readers progress={progress} own={own} />
    </div>
  );
}

/**
 * The pace in force, and — the half of the feature that is not a number — which of the two
 * it is.
 *
 * `velocityCaption` is the settings screen's sentence, reused rather than reworded. The
 * arbitration runs once on the server and is put into words once here, so the two screens
 * that show this number cannot explain it differently.
 */
function Pace({ progress, heading }: { progress: Progress; heading: string }) {
  const caption = velocityCaption(progress.velocity);
  const rate = progress.velocity.perWorkingDay;

  return (
    <section className="flex flex-col gap-3">
      <GroupLabel className="px-0 pt-0">{heading}</GroupLabel>
      <div className="flex items-end gap-2.5">
        {/* An em dash, not a 0. A person Kanso has never measured does not deliver nothing. */}
        <span className="text-30 font-medium leading-none tracking-[-0.02em]">
          {rate === undefined ? "—" : formatRate(rate)}
        </span>
        <span className="pb-1 text-12 text-muted-foreground">
          {rate === undefined ? "no pace yet" : "points per working day"}
        </span>
      </div>
      <p className="m-0 max-w-[620px] text-12 text-muted-foreground">{caption.inForce}</p>
      {/* The losing number, kept beside the winner: watching the two converge, or not, is
          the most useful thing this pair of numbers produces. */}
      {caption.reference !== null && (
        <p className="m-0 max-w-[620px] text-11 text-faint">{caption.reference}</p>
      )}
    </section>
  );
}

/** What is on the plate now, in days, then cut by status and by project. */
function Load({ progress, own }: { progress: Progress; own: boolean }) {
  const { load } = progress;

  return (
    <section className="flex flex-col gap-3 border-t border-border pt-5">
      <GroupLabel className="px-0 pt-0">Carrying now</GroupLabel>
      <p className="m-0 max-w-[620px] text-13" data-testid="load-sentence">
        {loadSentence(load, progress.velocity, own ? YOURS : about(progress.person.displayName))}
      </p>
      {load.load.tickets > 0 && (
        <>
          <StatusBar load={load} />
          <Projects load={load} />
        </>
      )}
    </section>
  );
}

/**
 * Who else can read this page — the ticket's second guard-rail, and it costs one sentence.
 *
 * Drawn on both versions of the page and *especially* on the reader's own, which is the
 * only place a person will ever look to find out that somebody else can read this. The
 * ticket's argument is adoption, not compliance: individual productivity figures readable
 * without the subject knowing is the kind of detail that decides whether a team takes a
 * tool up, and saying so is cheaper than being found out.
 *
 * In the quietest ink on the page. It is a fact the subject should be able to find, not a
 * warning — a page that shouted it would make an ordinary permission look like a leak.
 */
function Readers({ progress, own }: { progress: Progress; own: boolean }) {
  return (
    <p
      className="m-0 max-w-[620px] border-t border-border pt-4 text-11 text-faint"
      data-testid="progress-readers"
    >
      {readersSentence(progress.readers, own ? null : progress.person.displayName)}
    </p>
  );
}
