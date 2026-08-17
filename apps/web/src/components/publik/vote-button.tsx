"use client";

import { useSyncExternalStore } from "react";
import { useVote } from "@/lib/queries/publik";
import { cn } from "@/lib/utils";
import {
  parseVoted,
  rememberVote,
  subscribeVoted,
  votedServerSnapshot,
  votedSnapshot,
} from "./voted";

/**
 * `▲ 128`, and it is a button.
 *
 * One control per row rather than a count with a separate control beside it: the drawing
 * draws the triangle and the number as one thing, and voting is the only gesture the
 * whole screen offers.
 *
 * The pressed state comes out of `localStorage` through `useSyncExternalStore`, which is
 * what makes the server's answer (nobody has voted) and the first client render agree
 * without a hydration mismatch — and what makes a vote cast in one tab show up in
 * another.
 */
export function VoteButton({ ticketKey, votes }: { ticketKey: string; votes: number }) {
  const stored = useSyncExternalStore(subscribeVoted, votedSnapshot, votedServerSnapshot);
  const voted = parseVoted(stored).has(ticketKey);
  const vote = useVote();

  return (
    <button
      type="button"
      data-chip
      // `aria-pressed` rather than a label that changes: a screen reader should hear the
      // same control in both states, with its state, not two different controls.
      aria-pressed={voted}
      aria-label={`Vote for ${ticketKey}`}
      disabled={vote.isPending}
      onClick={() => {
        // Remembered before the request, not after it: the control has to answer the
        // click immediately, and the server's answer is the same either way — a vote it
        // already holds is absorbed rather than refused.
        rememberVote(ticketKey);
        vote.mutate(ticketKey);
      }}
      className={cn(
        "inline-flex items-center gap-1.5 rounded-sm px-1 py-0.5 text-11 transition-colors",
        "hover:bg-accent disabled:opacity-60",
        voted ? "text-accent-ink" : "text-muted-foreground",
      )}
    >
      <span aria-hidden>▲</span>
      <span className="font-mono">{votes}</span>
    </button>
  );
}
