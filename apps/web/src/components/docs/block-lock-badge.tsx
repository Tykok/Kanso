"use client";

import { useEffect, useState } from "react";
import type { DocBlockLock } from "@/lib/api";
import { lockBadge, lockSentence } from "@/lib/doc-locks";

/**
 * What the person who cannot type sees, on the block itself — `KAN-25`.
 *
 * Three facts and the brief insists on all three: **who** has it, that it **frees itself**,
 * and **when**. This repository has shipped a silent refusal twice — `useReportError` exists
 * because a refused unarchive said nothing on four routes — and a paragraph that greys out
 * with no explanation is that bug with better manners. So the name is on screen before
 * anybody presses a key, rather than only in the 409 they get for trying.
 *
 * The badge is short because it sits in the paragraph's own row; the full sentence is its
 * `title` and its `aria-label`, which is what a screen reader announces and what the e2e
 * spec reads. Both come from `lib/doc-locks.ts`, so the sentence a blocked person gets from
 * hovering and the one they get from typing are the same sentence.
 */
export function BlockLockBadge({ lock }: { lock: DocBlockLock }) {
  const now = useCountdown(lock);

  return (
    <span
      data-testid="doc-block-lock"
      data-holder={lock.displayName}
      title={lockSentence(lock.displayName, lock.freesAt, now)}
      aria-label={lockSentence(lock.displayName, lock.freesAt, now)}
      className="inline-flex h-5 shrink-0 items-center gap-1 rounded-sm bg-warning px-[7px] text-11 whitespace-nowrap text-warning-foreground"
    >
      {lockBadge(lock, now)}
    </span>
  );
}

/**
 * A second hand, and only while there is something to count down.
 *
 * The interval stops once the claim has lapsed rather than ticking for the life of the
 * page: a badge reading "freeing" has nothing left to say, and a document showing ten
 * expired blocks would otherwise hold ten timers waking the tab once a second for nothing.
 * The next event refreshes the block and the badge goes with it.
 *
 * Restarted when `freesAt` moves, so a renewal by the holder counts down from the new
 * instant rather than leaving a stopped clock beside a block somebody is still typing in.
 *
 * The effect does **not** set the clock on the way in, which is the one thing worth
 * pointing at here: React's own rule, and it costs nothing. A `freesAt` that has just
 * moved re-renders this component anyway — it is a prop — and the badge reads
 * `freesAt - now`, so a `now` up to a second stale changes a number nobody can see change.
 * Setting it synchronously would buy that invisible second for a cascading render.
 */
function useCountdown(lock: DocBlockLock): Date {
  const [now, setNow] = useState(() => new Date());

  useEffect(() => {
    if (new Date(lock.freesAt).getTime() <= Date.now()) return;
    const tick = setInterval(() => {
      setNow(new Date());
      if (new Date(lock.freesAt).getTime() <= Date.now()) clearInterval(tick);
    }, 1000);
    return () => clearInterval(tick);
  }, [lock.freesAt]);

  return now;
}
